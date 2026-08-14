package org.thoughtcrime.securesms.taler

import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.TalerUriRefreshJob

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
        AppDependencies.jobManager.add(TalerUriRefreshJob(uri))
      }
    }
  }
}
