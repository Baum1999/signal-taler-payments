package org.thoughtcrime.securesms.taler

import android.app.Activity
import android.os.Bundle
import androidx.annotation.StringRes
import kotlinx.serialization.json.Json
import net.taler.wallet.link.TalerUriKind
import net.taler.wallet.link.TalerUriParser
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.database.DraftTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.mms.QuoteId
import org.thoughtcrime.securesms.sms.MessageSender
import kotlin.time.Duration.Companion.seconds

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
          val talerPaymentDataJson = if (status == "ready" && data?.isHierarchical == true) data.getQueryParameter("talerPaymentData") else null
          
          // Versuche, talerPaymentData JSON zu parsen
          val paymentData = talerPaymentDataJson?.let {
            try {
              Json.decodeFromString<TalerPaymentData>(it)
            } catch (e: Exception) {
              Log.w(TAG, "Failed to parse talerPaymentData JSON", e)
              null
            }
          }
          
          // Priorität: talerPaymentData > talerUri (Fallback für ältere Taler-Versionen)
          if (paymentData != null && paymentData.uri.isNotEmpty()) {
            // Jede einzelne URI syntaktisch pruefen (nicht nur die erste) -
            // data kommt aus einem Intent-Extra und ist laut Kommentar oben
            // angreiferkontrolliert; bei einem Gruppen-Split-Versand
            // (Meilenstein 2, PROMPT_parallel_group_split.md) traegt
            // paymentData.uri mehrere Elemente. Nur ein grober Vorfilter -
            // die eigentliche autoritative Pruefung (previewForUri) macht
            // sendComposedPaymentWithData weiter unten fuer jede URI erneut.
            if (paymentData.uri.all { TalerUriDetector.isExactlyOneUri(it) }) {
              if (entry.intent == TalerCorrelationIntent.REFUND) {
                // Header + Taler-seitiger Verwendungszweck (legacyText) ZUERST,
                // dann die URIs im Klartext (nicht als kompaktes JSON -
                // urisFromMessageBody wuerde sonst versuchen, den GESAMTEN Text
                // strikt als TalerPaymentData zu dekodieren; ein Header davor
                // liesse das fehlschlagen und den Regex-Fallback auf noch
                // JSON-verklebte URIs anwenden, siehe Warnung in
                // TalerPaymentData.kt) - derselbe Body-Aufbau wie
                // sendComposedPaymentWithData, nur mit Header vor legacyText
                // statt direktem Legacy-Text. Text-zuerst-Reihenfolge (Bugfix
                // "Transkript kopieren -> Taler einfuegen"): legacyText sagt
                // "copy the URI below", das stimmt nur, wenn die URI(s)
                // tatsaechlich NACH dem Text stehen.
                draftText = getString(R.string.TalerFork_refund_message, paymentData.legacyText) +
                  "\n\n" + paymentData.uri.joinToString("\n\n")
              } else {
                // Für SEND: JSON direkt als Nachricht senden
                sendComposedPaymentWithData(entry.threadId, paymentData)
              }
            }
          } else if (talerUri != null && TalerUriDetector.isExactlyOneUri(talerUri)) {
            // Fallback für ältere Taler-Versionen ohne JSON-Unterstützung
            if (entry.intent == TalerCorrelationIntent.REFUND) {
              draftText = getString(R.string.TalerFork_refund_message, talerUri)
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
        val builder = ConversationIntents.createBuilderSync(this, recipientId, entry.threadId)
        val quoteMessageId = entry.quoteMessageId
        val quoteAuthor = entry.quoteAuthor
        if (draftText != null && quoteMessageId != null && quoteAuthor != null) {
          // Rueckerstattung als Zitat-Antwort auf die urspruengliche
          // Zahlungsnachricht statt als eigenstaendige Nachricht (Refund-Redesign) -
          // Draft-Table statt withDraftText: ConversationArgs.canInitializeFromDatabase()
          // (und damit der QUOTE-Draft-Ladepfad in DraftRepository) greift nur,
          // wenn draftText aus dem Intent (EXTRA_TEXT/withDraftText) null ist -
          // shareText wird dort VOR dem DB-Drafts-Pfad geprueft.
          SignalDatabase.drafts.replaceDrafts(
            entry.threadId,
            DraftTable.Drafts(
              listOf(
                DraftTable.Draft(DraftTable.Draft.QUOTE, QuoteId(quoteMessageId, quoteAuthor).serialize()),
                DraftTable.Draft(DraftTable.Draft.TEXT, draftText),
              )
            )
          )
        } else {
          builder.withDraftText(draftText)
        }
        builder.build().let { conversationIntent -> startActivity(conversationIntent) }
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
   * MessageSender.send() macht synchrone SQLite-Schreibzugriffe - die
   * gehoeren nicht auf den Main-Thread (onCreate). Deshalb laeuft die
   * komplette Methode auf SignalExecutors.BOUNDED. Fire-and-forget: onCreate
   * wartet nicht auf den Abschluss und ruft anschliessend ohnehin finish()
   * auf.
   *
   * Klassifizierung per TalerUriParser.classify() - reine String-Logik ohne
   * Netz und ohne Taler-App. Die Datei wird von scripts/sync-aidl.sh
   * byte-identisch aus dem Taler-Repo gespiegelt, kann also nicht unbemerkt
   * von Talers eigener Einteilung abdriften. Eine nicht klassifizierbare URI
   * gilt als gescheiterter Versuch - stiller Abbruch, kein Absturz, kein Log
   * mit der URI (gleiche Regel wie beim TalerUriDetector-Check oben).
   */
  private fun sendComposedPayment(threadId: Long, uri: String, @StringRes messageRes: Int) {
    val appContext = applicationContext
    SignalExecutors.BOUNDED.execute {
      val recipient = SignalDatabase.threads.getRecipientForThreadId(threadId) ?: return@execute
      if (recipient.isSelf || !(recipient.isIndividual || recipient.isGroup)) return@execute

      val uriKind = TalerUriParser.classify(uri) ?: return@execute
      if (uriKind != TalerUriKind.PAY_PUSH && uriKind != TalerUriKind.PAY_PULL) return@execute

      val kindLabel = TalerPaymentCardPresenter.kindLabel(appContext, uriKind.name)
      val resolvedMessageRes = if (uriKind == TalerUriKind.PAY_PULL) {
        R.string.TalerFork_request_message
      } else {
        messageRes
      }
      val body = appContext.getString(resolvedMessageRes, kindLabel, uri)

      val message = OutgoingMessage(
        threadRecipient = recipient,
        body = body,
        sentTimeMillis = System.currentTimeMillis(),
        expiresIn = recipient.expiresInSeconds.seconds.inWholeMilliseconds,
        isSecure = true,
        talerPayment = TalerPaymentPayload(uris = listOf(uri))
      )
      MessageSender.send(appContext, message, threadId, MessageSender.SendType.SIGNAL, null, null)
    }
  }

  /**
   * Sendet eine Zahlungsnachricht mit strukturierten Metadaten (fuer
   * Gruppen-Split-Transaktionen: includeSelf, totalAmount). Die Struktur
   * geht ueber DataMessage.talerPayment (Feld 9000, SignalService.proto),
   * nicht mehr als JSON in `body` - `body` traegt nur noch die rohen URIs
   * plus den Legacy-Text in Klartext, fuer Clients ohne Kenntnis dieses
   * Feldes (docs/API.md).
   */
  private fun sendComposedPaymentWithData(threadId: Long, paymentData: TalerPaymentData) {
    val appContext = applicationContext
    SignalExecutors.BOUNDED.execute {
      val recipient = SignalDatabase.threads.getRecipientForThreadId(threadId) ?: return@execute
      if (recipient.isSelf || !(recipient.isIndividual || recipient.isGroup)) return@execute

      // Prueft JEDE URI im paymentData (nicht nur die erste) auf syntaktische
      // Gueltigkeit - Schutzmechanismus, falls das JSON manipuliert wurde
      // (Meilenstein 2, PROMPT_parallel_group_split.md). Rein lokale
      // Klassifikation, kein Exchange-Roundtrip: bei einem Gruppen-Split mit N
      // Mitgliedern waere ein Abruf pro URI eine spuerbare Verzoegerung beim
      // Versenden. Schlaegt auch nur eine Pruefung fehl, wird NICHTS
      // verschickt (gleiche konservative Regel wie beim Einzel-URI-Pfad oben)
      // - kein unvollstaendiges Paket.
      if (paymentData.uri.isEmpty()) return@execute
      if (paymentData.uri.any { TalerUriParser.classify(it) == null }) return@execute

      val isGroupSplit = recipient.isGroup && paymentData.uri.size > 1
      val talerPayment = TalerPaymentPayload(
        uris = paymentData.uri,
        version = paymentData.version,
        isGroupSplit = isGroupSplit,
        includeSelf = paymentData.includeSelf,
        totalAmount = paymentData.totalAmount
      )

      // body traegt zuerst den Legacy-Text - fuer Clients ohne Kenntnis von
      // DataMessage.talerPayment -, gefolgt von den rohen URIs (mit Leerzeile
      // dazwischen, damit TalerUriDetector sie einzeln findet UND der
      // Klartext lesbar bleibt). Text-zuerst-Reihenfolge (Bugfix "Transkript
      // kopieren -> Taler einfuegen"): legacyText sagt "copy the URI below",
      // das stimmt nur, wenn die URI(s) tatsaechlich NACH dem Text stehen -
      // vorher standen sie davor, was sowohl die eigene Anleitung
      // widersprach als auch (Root Cause des eigentlichen Bugs)
      // TalerUriExtractor.extract() auf Taler-Seite dazu brachte, den
      // GESAMTEN Text inklusive Legacy-Satz als eine einzige "URI"
      // misszuverstehen (siehe TalerUriExtractor.kt: der Fix dort macht
      // dieses Umdrehen nicht ueberfluessig - bereits verschickte
      // Nachrichten in bestehenden Chats haben weiterhin die alte
      // Reihenfolge, und der Extractor muss beide Richtungen abdecken).
      val body = paymentData.legacyText + "\n\n" + paymentData.uri.joinToString("\n\n")

      val message = OutgoingMessage(
        threadRecipient = recipient,
        body = body,
        sentTimeMillis = System.currentTimeMillis(),
        expiresIn = recipient.expiresInSeconds.seconds.inWholeMilliseconds,
        isSecure = true,
        talerPayment = talerPayment
      )
      MessageSender.send(appContext, message, threadId, MessageSender.SendType.SIGNAL, null, null)
    }
  }
}
