package org.thoughtcrime.securesms.jobs

import kotlinx.coroutines.runBlocking
import net.taler.wallet.link.PaymentPreviewResult
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
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
    Parameters.Builder()
      .setQueue("TalerUriRefreshJob::$uri")
      .setMaxInstancesForQueue(1)
      .setLifespan(TimeUnit.MINUTES.toMillis(1))
      .setMaxAttempts(3)
      .build(),
    uri,
  )

  override fun onRun() {
    val result = runBlocking { TalerLinkClient(context).previewForUri(uri) }
    when (result) {
      is TalerLinkResult.Ergebnis -> applyPreview(result.value)
      is TalerLinkResult.NichtInstalliert,
      is TalerLinkResult.NichtVertrauenswuerdig,
      is TalerLinkResult.KeinConsent -> {
        SignalDatabase.talerPayments.updateStatus(uri, TalerPaymentStatus.TALER_NICHT_VERBUNDEN)
      }
      is TalerLinkResult.Fehler -> {
        Log.w(TAG, "previewForUri fehlgeschlagen fuer $uri")
        // Kein DB-Update - naechster Poll-Durchlauf (Schritt 4g) versucht es erneut.
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
    Log.w(TAG, "Konnte $uri nicht aktualisieren")
  }

  class Factory : Job.Factory<TalerUriRefreshJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): TalerUriRefreshJob {
      val data = JsonJobData.deserialize(serializedData)
      return TalerUriRefreshJob(parameters, data.getString(KEY_URI))
    }
  }
}
