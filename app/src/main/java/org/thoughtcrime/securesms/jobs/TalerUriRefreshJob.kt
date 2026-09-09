package org.thoughtcrime.securesms.jobs

import kotlinx.coroutines.runBlocking
import net.taler.wallet.link.PaymentPreviewResult
import net.taler.wallet.link.TalerUriKind
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.taler.TalerCorrelation
import org.thoughtcrime.securesms.taler.TalerLinkClient
import org.thoughtcrime.securesms.taler.TalerLinkResult
import org.thoughtcrime.securesms.taler.TalerPaymentCardPresenter
import org.thoughtcrime.securesms.taler.TalerPaymentStatus
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Fragt Status/Vorschau eines einzelnen Taler-URI bei der lokalen
 * Taler-Schnittstelle ab (docs/API.md) und schreibt das Ergebnis in
 * [org.thoughtcrime.securesms.database.TalerPaymentTable]. Wird beim
 * erstmaligen Erkennen einer URI enqueued (siehe TalerPaymentTracker) und
 * spaeter erneut fuers Polling (Schritt 4g).
 *
 * Ein Vorgang wird pro URI verwaltet, nicht pro Nachricht (docs/API.md) -
 * die Queue ist deshalb pro URI dedupliziert (setQueue + setMaxInstancesForQueue),
 * damit doppelt zugestellte/weitergeleitete Nachrichten mit derselben URI nicht
 * mehrere parallele Abfragen ausloesen.
 *
 * Bug 3 Fix: Getrennte Queues pro TriggerType, um Dedup-Kollision zwischen
 * Routine-Polling und Return-Trigger zu vermeiden. RETURN-Jobs erhalten
 * hoehere Prioritaet (HIGH) fuer schnelle UI-Updates nach Accept-Flow.
 */
class TalerUriRefreshJob private constructor(
  parameters: Parameters,
  private val uri: String,
  private val triggerType: TriggerType = TriggerType.ROUTINE,
) : BaseJob(parameters) {

  enum class TriggerType {
    /** Regulaeres Polling durch TalerPollingCoordinator */
    ROUTINE,
    /** Sofort-Refresh nach Ruecksprung aus Taler-App (Accept/Reject) */
    RETURN,
    /** Manueller Trigger (z.B. Pull-to-Refresh) */
    MANUAL
  }

  companion object {
    private val TAG = Log.tag(TalerUriRefreshJob::class.java)
    const val KEY = "TalerUriRefreshJob"
    private const val KEY_URI = "uri"
    private const val KEY_TRIGGER_TYPE = "triggerType"
  }

  constructor(uri: String, triggerType: TriggerType = TriggerType.ROUTINE) : this(
    // B1b (REVIEW.md): der Queue-Name landet persistent in Signals eigener
    // Job-Datenbank (JobDatabase.QUEUE_KEY) und wird vom JobManager bei
    // jedem Lauf mehrfach geloggt - deshalb Hash statt Klartext-URI. Die
    // Dedup-Eigenschaft (ein Vorgang pro URI, nicht pro Nachricht) bleibt
    // erhalten, solange der Hash kollisionsfrei ist.
    //
    // Bug 3 Fix: Getrennte Queues pro TriggerType, damit ein RETURN-Trigger
    // nicht durch einen laufenden ROUTINE-Job fuer dieselbe URI blockiert wird.
    Parameters.Builder()
      .setQueue("TalerUriRefreshJob::${triggerType.name}::${TalerCorrelation.shortHash(uri)}")
      .setMaxInstancesForQueue(1)
      .setLifespan(TimeUnit.MINUTES.toMillis(1))
      .setMaxAttempts(3)
      .setQueuePriority(if (triggerType == TriggerType.RETURN) Job.Parameters.PRIORITY_HIGH else Job.Parameters.PRIORITY_DEFAULT)
      .build(),
    uri,
    triggerType,
  )

  override fun onRun() {
    val result = runBlocking { TalerLinkClient(context).previewForUri(uri) }
    when (result) {
      is TalerLinkResult.Ergebnis -> {
        val newStatus = TalerPaymentStatus.fromTalerStatus(result.value.status)
        val previousStatus = applyPreview(result.value, newStatus)
        maybeSendAcceptConfirmation(previousStatus, newStatus)
      }
      // P1 (REVIEW.md): drei fuer den Nutzer unterschiedliche Faelle nicht
      // mehr auf einen gemeinsamen Fallback-Zustand zusammenfassen - "App
      // fehlt" ist ein Installationshinweis, "Signatur stimmt nicht" ein
      // Sicherheitshinweis, "kein Consent" ein reiner Verbindungshinweis.
      is TalerLinkResult.NichtInstalliert -> {
        SignalDatabase.talerPayments.updateStatus(uri, TalerPaymentStatus.NICHT_INSTALLIERT)
      }
      is TalerLinkResult.NichtVertrauenswuerdig -> {
        SignalDatabase.talerPayments.updateStatus(uri, TalerPaymentStatus.NICHT_VERTRAUENSWUERDIG)
      }
      is TalerLinkResult.KeinConsent -> {
        SignalDatabase.talerPayments.updateStatus(uri, TalerPaymentStatus.TALER_NICHT_VERBUNDEN)
      }
      is TalerLinkResult.Fehler -> {
        Log.w(TAG, "previewForUri fehlgeschlagen (uri=${TalerCorrelation.shortHash(uri)})")
        // B2 (REVIEW.md): consecutive_failures hochzaehlen statt keinem
        // DB-Update - TalerPollingCoordinator braucht das fuer den
        // exponentiellen Backoff, sonst wird ein dauerhaft fehlschlagender
        // URI weiter im festen 20s-Takt angefragt.
        SignalDatabase.talerPayments.recordFailure(uri)
        return
      }
    }

    // Damit eine ggf. offene Konversation die neue Karte sofort zeigt, statt
    // erst beim naechsten natuerlichen Rebind (Scroll, Wiederoeffnen) - siehe
    // Schritt 4g/docs/API.md. Grobkoerniger als notifyMessageUpdateObservers
    // (laedt den ganzen Thread neu statt nur eine Nachricht), aber ohne
    // URI->messageId-Zuordnung, die unser Datenmodell bewusst nicht fuehrt
    // ("ein Vorgang pro URI, nicht pro Nachricht").
    SignalDatabase.talerPayments.getByUri(uri)?.let { record ->
      AppDependencies.databaseObserver.notifyConversationListeners(record.threadId)
    }
  }

  /**
   * Sendet die Bestaetigungsnachricht ("Zahlung fuer [Kind] akzeptiert") an
   * den urspruenglichen Zahlungs-Absender - nur bei einem tatsaechlichen
   * Uebergang OFFEN->ANGENOMMEN (nicht bei jedem Poll-Tick), nur wenn dieses
   * Geraet der Annehmer war (PAY_PUSH, !isOwnPayment - dieselbe Bedingung wie
   * fuer den Accept-Button in TalerPaymentCardPresenter) und nur in 1:1-Chats
   * (keine Gruppen). Der Versand laeuft ueber den normalen
   * MessageSender/JobManager-Pfad - Netzwerkfehler werden von dessen
   * Retry-/Offline-Queue-Logik abgefangen wie bei jeder anderen ausgehenden
   * Nachricht, daher hier keine eigene Fehlerbehandlung noetig; ein fehlender
   * Recipient/Thread fuehrt lediglich dazu, dass gar nichts gesendet wird.
   *
   * Bug 2 Fix: Self-Chat war zuvor zusaetzlich ausgeschlossen (Annahme: "man
   * muss sich selbst nichts bestaetigen"). Diese Annahme stimmt nicht, wenn
   * Sender und Empfaenger zwar denselben Signal-Thread (Notiz an mich), aber
   * zwei verschiedene Taler-Wallets sind - wirtschaftlich zwei Parteien,
   * technisch ein Self-Chat. isOwnPayment (oben) ist bereits die korrekte,
   * Taler-seitig ermittelte Unterscheidung dafuer; ein zusaetzlicher
   * Self-Chat-Ausschluss ist deshalb unnoetig und im echten
   * Nur-ich-selbst-Fall harmlos (dann ist isOwnPayment ohnehin true und die
   * Methode kehrt oben schon zurueck).
   */
  private fun maybeSendAcceptConfirmation(previous: TalerPaymentStatus?, new: TalerPaymentStatus) {
    if (previous != TalerPaymentStatus.OFFEN || new != TalerPaymentStatus.ANGENOMMEN) return

    val record = SignalDatabase.talerPayments.getByUri(uri) ?: return
    if (record.uriKind != TalerUriKind.PAY_PUSH.name || record.isOwnPayment) return

    val recipient = SignalDatabase.threads.getRecipientForThreadId(record.threadId) ?: return
    if (!recipient.isIndividual) return

    val kindLabel = TalerPaymentCardPresenter.kindLabel(context, record.uriKind)
    val body = context.getString(R.string.TalerFork_accept_confirmation_message, kindLabel, uri)

    val message = OutgoingMessage(
      threadRecipient = recipient,
      body = body,
      sentTimeMillis = System.currentTimeMillis(),
      expiresIn = recipient.expiresInSeconds.seconds.inWholeMilliseconds,
      isSecure = true
    )
    MessageSender.send(context, message, record.threadId, MessageSender.SendType.SIGNAL, null, null)
  }

  /**
   * Schreibt die neue Vorschau und entscheidet atomar (siehe
   * [org.thoughtcrime.securesms.database.TalerPaymentTable.applyPreviewAndRecordTransition])
   * ueber die lokale Statuszeile, statt bisherigen Status und neuen Status
   * an zwei unsynchronisierten Stellen zu vergleichen - Root Cause des
   * "abgelaufen"-Bugreports (siehe dortiger Kommentar).
   */
  private fun applyPreview(preview: PaymentPreviewResult, status: TalerPaymentStatus): TalerPaymentStatus? =
    SignalDatabase.talerPayments.applyPreviewAndRecordTransition(
      uri = uri,
      uriKind = preview.uriKind.name,
      status = status,
      amount = preview.amount,
      currency = preview.currency,
      exchangeBaseUrl = preview.exchangeBaseUrl,
      summary = preview.summary,
      isOwnPayment = preview.isOwnPayment,
    )

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putString(KEY_URI, uri)
      .putString(KEY_TRIGGER_TYPE, triggerType.name)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  override fun onFailure() {
    Log.w(TAG, "Konnte Vorgang nicht aktualisieren (uri=${TalerCorrelation.shortHash(uri)})")
  }

  class Factory : Job.Factory<TalerUriRefreshJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): TalerUriRefreshJob {
      val data = JsonJobData.deserialize(serializedData)
      val uri = data.getString(KEY_URI)
      val triggerTypeName = data.getString(KEY_TRIGGER_TYPE) ?: TriggerType.ROUTINE.name
      val triggerType = try {
        TriggerType.valueOf(triggerTypeName)
      } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Unbekannter TriggerType: $triggerTypeName, verwende ROUTINE")
        TriggerType.ROUTINE
      }
      // Die uebergebenen parameters sind bereits die persistierten Parameters
      // des Jobs (inkl. korrekter Queue/Prioritaet, gesetzt vom oeffentlichen
      // Konstruktor zum Zeitpunkt des Enqueuens) - kein Rebuild noetig.
      // Job.Parameters.Builder hat keinen (Parameters)-Copy-Constructor,
      // ein Rebuild-Versuch waere ohnehin ein Kompilierfehler.
      return TalerUriRefreshJob(parameters, uri, triggerType)
    }
  }
}
