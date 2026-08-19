package org.thoughtcrime.securesms.taler

import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.TalerUriRefreshJob
import org.thoughtcrime.securesms.jobs.TalerUriRefreshJob.TriggerType

/**
 * Gemeinsame Erkennung+Nachverfolgung fuer Taler-URIs in Nachrichtentexten -
 * von Empfangs- (DataMessageProcessor) und Sendepfad (MessageSender) genutzt,
 * damit beide Seiten eines 1:1-Chats denselben Vorgang lokal nachverfolgen.
 * Ein Vorgang wird pro URI verwaltet (docs/API.md) - [TalerUriRefreshJob]
 * wird nur beim allerersten Erkennen einer neuen URI enqueued.
 */
object TalerPaymentTracker {
  @JvmStatic
  fun trackUrisInBody(body: String?, threadId: Long) {
    if (body.isNullOrBlank()) return
    val uris = TalerUriDetector.findUris(body)
    if (uris.isEmpty()) return

    TalerPollingCoordinator.ensureStarted()
    for (uri in uris) {
      val isNew = SignalDatabase.talerPayments.upsertDetected(uri, threadId)
      if (isNew) {
        // Bug 3 Fix: ROUTINE-TriggerType fuer initialen Poll-Job.
        // Neue URIs werden mit ROUTINE gepollt, RETURN-Jobs (aus TalerReturnActivity)
        // koennen parallel laufen ohne Dedup-Kollision.
        AppDependencies.jobManager.add(TalerUriRefreshJob(uri, TriggerType.ROUTINE))
      }
    }
  }
}
