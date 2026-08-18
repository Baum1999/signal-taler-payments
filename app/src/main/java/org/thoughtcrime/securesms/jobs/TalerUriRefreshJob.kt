package org.thoughtcrime.securesms.jobs

import kotlinx.coroutines.runBlocking
import net.taler.wallet.link.PaymentPreviewResult
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.taler.TalerCorrelation
import org.thoughtcrime.securesms.taler.TalerLinkClient
import org.thoughtcrime.securesms.taler.TalerLinkResult
import org.thoughtcrime.securesms.taler.TalerPaymentStatus
import java.util.concurrent.TimeUnit

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
 */
class TalerUriRefreshJob private constructor(
  parameters: Parameters,
  private val uri: String,
) : BaseJob(parameters) {

  companion object {
    private val TAG = Log.tag(TalerUriRefreshJob::class.java)
    const val KEY = "TalerUriRefreshJob"
    private const val KEY_URI = "uri"
  }

  constructor(uri: String) : this(
    // B1b (REVIEW.md): der Queue-Name landet persistent in Signals eigener
    // Job-Datenbank (JobDatabase.QUEUE_KEY) und wird vom JobManager bei
    // jedem Lauf mehrfach geloggt - deshalb Hash statt Klartext-URI. Die
    // Dedup-Eigenschaft (ein Vorgang pro URI, nicht pro Nachricht) bleibt
    // erhalten, solange der Hash kollisionsfrei ist.
    Parameters.Builder()
      .setQueue("TalerUriRefreshJob::${TalerCorrelation.shortHash(uri)}")
      .setMaxInstancesForQueue(1)
      .setLifespan(TimeUnit.MINUTES.toMillis(1))
      .setMaxAttempts(3)
      .build(),
    uri,
  )

  override fun onRun() {
    val previousStatus = SignalDatabase.talerPayments.getByUri(uri)?.status
    val result = runBlocking { TalerLinkClient(context).previewForUri(uri) }
    when (result) {
      is TalerLinkResult.Ergebnis -> {
        applyPreview(result.value)
        val newStatus = TalerPaymentStatus.fromTalerStatus(result.value.status)
        maybeInsertLocalStatusLine(previousStatus, newStatus)
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
   * Lokale Info-Zeile nur bei tatsaechlichem Wechsel IN einen Endzustand
   * (nicht bei jedem Poll-Tick mit unveraendertem Status) - docs/API.md 2.10.
   */
  private fun maybeInsertLocalStatusLine(previous: TalerPaymentStatus?, new: TalerPaymentStatus) {
    val terminalStates = setOf(TalerPaymentStatus.ANGENOMMEN, TalerPaymentStatus.ABGELAUFEN)
    if (new !in terminalStates || previous == new) return
    SignalDatabase.talerPayments.getByUri(uri)?.let { record ->
      SignalDatabase.talerPayments.insertLocalStatusLine(record.threadId, new)
    }
  }

  private fun applyPreview(preview: PaymentPreviewResult) {
    SignalDatabase.talerPayments.updateFromPreview(
      uri = uri,
      uriKind = preview.uriKind.name,
      status = TalerPaymentStatus.fromTalerStatus(preview.status),
      amount = preview.amount,
      currency = preview.currency,
      exchangeBaseUrl = preview.exchangeBaseUrl,
      summary = preview.summary,
    )
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putString(KEY_URI, uri)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  override fun onFailure() {
    Log.w(TAG, "Konnte Vorgang nicht aktualisieren (uri=${TalerCorrelation.shortHash(uri)})")
  }

  class Factory : Job.Factory<TalerUriRefreshJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): TalerUriRefreshJob {
      val data = JsonJobData.deserialize(serializedData)
      return TalerUriRefreshJob(parameters, data.getString(KEY_URI))
    }
  }
}
