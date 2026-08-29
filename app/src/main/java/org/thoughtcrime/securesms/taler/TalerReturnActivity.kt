package org.thoughtcrime.securesms.taler

import android.app.Activity
import android.os.Bundle
import androidx.annotation.StringRes
import kotlinx.coroutines.runBlocking
import net.taler.wallet.link.TalerUriKind
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.sms.MessageSender

/**
 * Ruecksprungziel fuer Annehmen/Ablehnen/Abbrechen (docs/API.md 2.10,
 * Meilenstein 4), Compose-Send (Meilenstein 5) und Compose-Refund
 * (Meilenstein 6) - signalfuergnu://taler-return. Exportiert, aber die
 * eingehenden Parameter
 * sind ANGREIFERKONTROLLIERT (Regel: jede angezeigte Zahl/jeder Zustand
 * kommt aus dem Taler-AIDL-Aufruf, nie aus einem Intent-Extra) - `status`
 * aus dem Intent wird deshalb NIE angezeigt oder fuer die Kartenanzeige
 * uebernommen, nur als Trigger fuer einen sofortigen Re-Check per
 * TalerUriRefreshJob genutzt. Eine unbekannte/abgelaufene correlationId
 * wird kommentarlos verworfen (finish(), kein Fehlertext, kein Log mit der
 * URI).
 */
class TalerReturnActivity : Activity() {

  companion object {
    private val TAG = Log.tag(TalerReturnActivity::class.java)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Sicherheit (ruled during execution, round 1 - siehe Ledger): exported=true
    // bedeutet, dass der Manifest-<data>-Filter (scheme/host) NUR fuer implizite
    // Intents gilt - ein expliziter Intent (setClassName) jeder anderen App
    // erreicht diese Activity mit BELIEBIGER data-Uri, am Filter vorbei.
    // Uri.getQueryParameter() wirft UnsupportedOperationException auf einer
    // opaken (nicht-hierarchischen) Uri (z. B. "signalfuergnu:foo" ohne "//")
    // - deshalb der isHierarchical-Check VOR dem Zugriff. try/finally macht
    // "immer beenden, nie sichtbar abstuerzen" strukturell garantiert statt
    // von manuell platzierten finish()-Aufrufen abhaengig.
    try {
      val data = intent?.data
      val correlationId = if (data?.isHierarchical == true) data.getQueryParameter("correlationId") else null
      val entry = correlationId?.let { TalerCorrelationStore.take(it) }
      if (entry == null) {
        // Kein Log mit der correlationId oder einer URI (Regel s.o.) - nur ein
        // nackter Hinweis, dass dieser Fall (abgelaufen/Prozess-Neustart statt
        // regulaerem "kein correlationId im Intent" - z. B. Direktaufruf) ueberhaupt
        // aufgetreten ist, damit er beim Testen/Triage wenigstens sichtbar ist.
        if (correlationId != null) {
          Log.w(TAG, "Ruecksprung ohne bekannte correlationId - abgelaufen oder Prozess-Neustart")
        }
        return
      }

      var draftText: String? = null

      when (entry.intent) {
        TalerCorrelationIntent.ACCEPT_OR_CANCEL -> {
          // Meilenstein 4: Annehmen/Ablehnen einer bereits bekannten URI.
          entry.uri?.let { TalerPollingCoordinator.requestFastPoll(it) }
        }
        TalerCorrelationIntent.SEND, TalerCorrelationIntent.REFUND -> {
          // Meilenstein 5 (SEND) / Refund-Redesign: Taler hat gerade erst
          // eine neue URI erzeugt und haengt sie (nur bei Erfolg) als
          // talerUri an. status wird NIE direkt angezeigt (gleiche Regel wie
          // oben) - nur als Trigger genutzt, ob ueberhaupt etwas passiert.
          // Beide Intents committen bereits VOR dem Ruecksprung
          // (initiatePeerPushDebit) - REFUND unterscheidet sich seit dem
          // Redesign aber darin, WAS mit der fertigen URI passiert: SEND
          // verschickt sie automatisch als Nachricht (sendComposedPayment),
          // REFUND legt sie nur als Entwurf ins Nachrichtenfeld (siehe
          // draftText unten) - der Nutzer soll die Rueckerstattungs-URI vor
          // dem Versand noch sehen/anpassen koennen.
          val status = if (data?.isHierarchical == true) data.getQueryParameter("status") else null
          val talerUri = if (status == "ready" && data?.isHierarchical == true) data.getQueryParameter("talerUri") else null
          // Vor der Uebernahme wird per TalerUriDetector geprueft, dass der
          // GESAMTE String ein wohlgeformtes Taler-URI-Muster ist (nicht nur
          // irgendwo eins enthaelt) - TalerUriDetector.findUris() erkennt
          // auch ext+taler:// und payto://-URIs jeder Art, der
          // Compose-Send-Flow erzeugt aber ausschliesslich pay-push-URIs.
          // Diese erste Pruefung ist reine Textmustererkennung und billig
          // genug fuer den Main-Thread ("ist das ueberhaupt ein plausibler
          // Kandidat"). Fuer SEND entscheidet sendComposedPayment per
          // TalerLinkClient.previewForUri() (echter, autoritativer
          // AIDL-Aufruf an Taler), ob der Kandidat WIRKLICH pay-push ist -
          // fuer REFUND (nur Entwurfstext, keine automatische Nachricht)
          // reicht die guenstige Textmuster-Pruefung hier, da der Nutzer die
          // eingefuegte URI ohnehin vor dem Senden sieht.
          if (talerUri != null && TalerUriDetector.isExactlyOneUri(talerUri)) {
            if (entry.intent == TalerCorrelationIntent.REFUND) {
              draftText = talerUri
            } else {
              sendComposedPayment(entry.threadId, talerUri, R.string.TalerFork_send_message)
            }
          }
          // status == "cancelled" (oder talerUri fehlt trotz "ready", oder
          // talerUri besteht die TalerUriDetector-Pruefung nicht, oder
          // previewForUri klassifiziert sie bei SEND nicht als pay-push):
          // nichts tun, keine sichtbare Aenderung im Chat - gleiches Prinzip
          // wie lokales Ablehnen in Meilenstein 4.
        }
      }

      val recipientId = SignalDatabase.threads.getRecipientIdForThreadId(entry.threadId)
      if (recipientId != null) {
        ConversationIntents.createBuilderSync(this, recipientId, entry.threadId)
          .withDraftText(draftText)
          .build()
          .let { conversationIntent -> startActivity(conversationIntent) }
      }
    } finally {
      finish()
    }
  }

  /**
   * Meilenstein 5 (SEND): der eigentliche, echte Versand. body enthaelt die
   * rohe URI im Klartext (TalerPaymentCardPresenter.present() findet sie
   * darueber per Regex) und ist auch ohne unsere Karte verstaendlich -
   * gleiches Muster wie TalerUriRefreshJob.maybeSendAcceptConfirmation(),
   * aber mit "sent"-Wortlaut statt "accepted" (Regel: nie "erhalten" fuer
   * eine ausgehende Zahlung). Nur noch fuer SEND aufgerufen - REFUND legt
   * die URI seit dem Refund-Redesign nur noch als Entwurf ins
   * Nachrichtenfeld (siehe draftText in onCreate), statt automatisch zu
   * senden.
   *
   * Gleiche Einschraenkung wie beim UI-Gate in AttachmentKeyboardFragment
   * (Defense-in-depth-Backstop): 1:1-Chats UND Gruppen, KEIN Self-Chat
   * (Notiz an mich). Frueher war dieser Backstop auf isIndividual allein
   * beschraenkt (Gruppen ausgeschlossen) - das machte den separaten
   * Gruppen-Sendeweg (frueher: TalerPaymentActions) strukturell kaputt: Taler
   * hatte das Geld laengst abgebucht (initiatePeerPushDebit, s.o.), aber
   * dieser Backstop verwarf den Ruecksprung fuer Gruppen-Empfaenger
   * kommentarlos - die versendete Nachricht (und damit die einzige
   * Moeglichkeit, die URI zu beanspruchen) ging nie raus. Ein Taler-URI
   * bleibt trotzdem ein Inhaberpapier: ein Versand in eine Gruppe broadcastet
   * einen EINZELNEN einloesbaren Link an alle Mitglieder ("wer zuerst
   * bestaetigt") - das ist jetzt eine bewusste, im Compose-Screen selbst
   * (ComposeSendScreen.kt, taler-android) klar beworbene Eigenschaft, keine
   * verschwiegene. isIndividual/isGroup allein schliessen Notiz an mich NICHT
   * aus (Recipient.isIndividual/isGroup pruefen nicht isSelf) - deshalb der
   * zusaetzliche isSelf-Check, als echte Parity zum UI-Gate (das isSelf
   * ebenfalls separat ausschliesst, siehe isTalerRecipientAllowed dort).
   *
   * MessageSender.send() macht synchrone SQLite-Schreibzugriffe, und die
   * Pay-Push-Klassifizierung unten macht einen echten AIDL-Roundtrip zu
   * Taler (previewForUri, suspend) - beides gehoert nicht auf den Main-Thread
   * (onCreate). Deshalb laeuft die komplette Methode auf
   * SignalExecutors.BOUNDED, EIN Background-Mechanismus fuer beide Zwecke
   * statt zwei getrennter (z. B. zusaetzlich Main-Thread-Klassifizierung vor
   * dem Dispatch) - gleiches Muster wie TalerUriRefreshJob.onRun(), der
   * previewForUri() ebenfalls per runBlocking von einem bereits laufenden
   * Background-Thread aus aufruft (dort ist das ein Job-Worker-Thread, hier
   * ein SignalExecutors.BOUNDED-Thread). Fire-and-forget: onCreate wartet
   * nicht auf den Abschluss und ruft anschliessend ohnehin finish() auf.
   *
   * Klassifizierung per TalerLinkClient.previewForUri() statt eines lokal
   * nachgebauten Parsers (fruehere Fassung kopierte TalerUriParser.kt aus
   * dem Taler-Repo - das ist genau die Datei, die sync-aidl.sh explizit als
   * "Talers interne Implementierung" von der Synchronisation ausschliesst,
   * eine lokale Kopie kann also unbemerkt von Talers echtem classify()
   * abdriften). previewForUri ist Taler-seitig autoritativ und ohnehin schon
   * der etablierte Mechanismus fuer exakt diese Frage (siehe
   * TalerUriRefreshJob). Jedes Nicht-Ergebnis (App fehlt, nicht
   * vertrauenswuerdig, kein Consent, sonstiger Fehler) wird wie ein
   * gescheiterter Klassifizierungsversuch behandelt - stiller Abbruch, kein
   * Absturz, kein Log mit der URI (gleiche Regel wie beim
   * TalerUriDetector-Check oben).
   */
  private fun sendComposedPayment(threadId: Long, uri: String, @StringRes messageRes: Int) {
    val appContext = applicationContext
    SignalExecutors.BOUNDED.execute {
      val recipient = SignalDatabase.threads.getRecipientForThreadId(threadId) ?: return@execute
      if (recipient.isSelf || !(recipient.isIndividual || recipient.isGroup)) return@execute

      // Gleiches exhaustives when() wie TalerUriRefreshJob.onRun() - jeder
      // Nicht-Ergebnis-Fall (App fehlt/nicht vertrauenswuerdig/kein
      // Consent/sonstiger Fehler) gilt hier als gescheiterte
      // Klassifizierung, nicht als eigener Zustand.
      val preview = when (val result = runBlocking { TalerLinkClient(appContext).previewForUri(uri) }) {
        is TalerLinkResult.Ergebnis -> result.value
        is TalerLinkResult.NichtInstalliert,
        is TalerLinkResult.NichtVertrauenswuerdig,
        is TalerLinkResult.KeinConsent,
        is TalerLinkResult.Fehler -> return@execute
      }
      if (preview.uriKind != TalerUriKind.PAY_PUSH) return@execute

      val kindLabel = TalerPaymentCardPresenter.kindLabel(appContext, TalerUriKind.PAY_PUSH.name)
      val body = appContext.getString(messageRes, kindLabel, uri)

      val message = OutgoingMessage(
        threadRecipient = recipient,
        body = body,
        sentTimeMillis = System.currentTimeMillis(),
        isSecure = true
      )
      MessageSender.send(appContext, message, threadId, MessageSender.SendType.SIGNAL, null, null)
    }
  }
}
