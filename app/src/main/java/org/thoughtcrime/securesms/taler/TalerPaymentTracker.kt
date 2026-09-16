package org.thoughtcrime.securesms.taler

import net.taler.wallet.link.TalerUriKind
import net.taler.wallet.link.TalerUriParser
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
 *
 * Nachverfolgt werden ausschliesslich `pay-push`/`pay-pull`: nur fuer diese
 * beiden Arten kann Signal den Zustand selbst ermitteln (siehe
 * [TalerPeerContractResolver]). `pay`/`withdraw`/`refund` haben keine solche
 * Quelle und bleiben deshalb bewusst blosser Linktext ohne Karte, Vorgang und
 * Polling.
 */
object TalerPaymentTracker {
  @JvmStatic
  fun trackUrisInBody(body: String?, threadId: Long, isOwnPayment: Boolean) {
    if (body.isNullOrBlank()) return
    val uris = urisFromMessageBody(body)
    if (uris.isEmpty()) return

    trackUris(uris, threadId, isOwnPayment)
  }

  /**
   * Wie [trackUrisInBody], aber fuer Nachrichten mit dem strukturierten
   * DataMessage.talerPayment-Feld (Feld 9000) statt JSON in `body`.
   * Persistiert zusaetzlich die Struktur-Metadaten (Gruppen-Split etc.) pro
   * Nachricht, damit das Rendern sie nicht mehr aus `body` herleiten muss.
   */
  @JvmStatic
  fun trackStructuredPayment(
    talerPayment: DataMessage.TalerPayment,
    messageId: Long,
    threadId: Long,
    isOwnPayment: Boolean
  ) {
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

    trackUris(uris, threadId, isOwnPayment)
  }

  private fun trackUris(uris: List<String>, threadId: Long, isOwnPayment: Boolean) {
    val trackable = uris.filter { isTrackable(it) }
    if (trackable.isEmpty()) return

    TalerPollingCoordinator.ensureStarted()
    for (uri in trackable) {
      val isNew = SignalDatabase.talerPayments.upsertDetected(uri, threadId, isOwnPayment)
      if (isNew) {
        // Bug 3 Fix: ROUTINE-TriggerType fuer initialen Poll-Job.
        // Neue URIs werden mit ROUTINE gepollt, RETURN-Jobs (aus TalerReturnActivity)
        // koennen parallel laufen ohne Dedup-Kollision.
        AppDependencies.jobManager.add(TalerUriRefreshJob(uri, TriggerType.ROUTINE))
      }
    }
  }

  /**
   * Ob Signal zu dieser URI einen eigenen Vorgang fuehrt. Auch die Karte
   * richtet sich danach, damit nicht fuer URIs gerendert wird, zu denen es
   * nie einen Datensatz geben kann.
   */
  @JvmStatic
  fun isTrackable(uri: String): Boolean {
    val kind = TalerUriParser.classify(uri)
    return kind == TalerUriKind.PAY_PUSH || kind == TalerUriKind.PAY_PULL
  }
}
