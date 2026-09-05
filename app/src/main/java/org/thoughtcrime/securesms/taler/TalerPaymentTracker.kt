package org.thoughtcrime.securesms.taler

import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.TalerUriRefreshJob
import org.thoughtcrime.securesms.jobs.TalerUriRefreshJob.TriggerType
import org.whispersystems.signalservice.internal.push.DataMessage

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
    val uris = urisFromMessageBody(body)
    if (uris.isEmpty()) return

    trackUris(uris, threadId)
  }

  /**
   * Wie [trackUrisInBody], aber fuer Nachrichten mit dem strukturierten
   * DataMessage.talerPayment-Feld (Feld 9000) statt JSON in `body`.
   * Persistiert zusaetzlich die Struktur-Metadaten (Gruppen-Split etc.) pro
   * Nachricht, damit das Rendern sie nicht mehr aus `body` herleiten muss.
   */
  @JvmStatic
  fun trackStructuredPayment(talerPayment: DataMessage.TalerPayment, messageId: Long, threadId: Long) {
    val uris = talerPayment.uris
    if (uris.isEmpty()) return

    SignalDatabase.talerPaymentMessages.insert(
      messageId = messageId,
      uris = uris,
      version = talerPayment.version ?: 1,
      isGroupSplit = talerPayment.isGroupSplit ?: false,
      includeSelf = talerPayment.includeSelf,
      totalAmount = talerPayment.totalAmount,
    )

    trackUris(uris, threadId)
  }

  private fun trackUris(uris: List<String>, threadId: Long) {
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
