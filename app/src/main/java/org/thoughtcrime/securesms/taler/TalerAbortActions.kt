package org.thoughtcrime.securesms.taler

import android.content.Context
import android.content.Intent
import android.net.Uri
import net.taler.wallet.link.TalerHttpsLink
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord

/**
 * "Abbrechen" eigener offener Zahlungen ueber talerlink://compose-abort
 * (docs/cross-platform/link-protokoll.md Abschnitt 5). Ersetzt den frueheren
 * Aktionslink taler://pay-push/...?correlationId=..., den Taler fuer jede URI
 * oeffnete, die seine Wallet nicht selbst erzeugt hat, als "Annehmen" -
 * wurde die Zahlung auf einem anderen Geraet desselben Kontos gesendet,
 * landete so ein "Abbrechen" auf dem Annehmen-Bildschirm der eigenen zweiten
 * Wallet. compose-abort dagegen bricht nur eigene Transaktionen ab und meldet
 * alle anderen als `not_own`.
 *
 * Ein Link fuer alle betroffenen Anteile (statt einem startActivity() pro URI,
 * von denen nur der letzte sicher ankommt), bei mehr als
 * [TalerAbortPlan.MAX_URIS_PER_LINK] in Stapeln, der naechste nach dem
 * Ruecksprung (TalerReturnActivity).
 */
object TalerAbortActions {

  fun onCancelFromMenu(context: Context, messageRecord: MessageRecord, uris: List<String>) {
    val batches = TalerAbortPlan.batches(uris)
    if (batches.isEmpty()) return

    val recipient = SignalDatabase.threads.getRecipientForThreadId(messageRecord.threadId)
    val meta = TalerAbortMeta(
      reason = TalerAbortMeta.REASON_MENU,
      isGroup = recipient?.isGroup == true,
      recipientHint = recipient?.getDisplayName(context)?.take(TalerAbortMeta.MAX_RECIPIENT_HINT_LENGTH),
      memberCount = recipient?.takeIf { it.isGroup }?.participantIds?.size,
    )
    launch(context, batches.first(), batches.drop(1), meta, messageRecord.threadId)
  }

  /** Startet einen Stapel; auch fuer die Folgestapel aus TalerReturnActivity. */
  fun launch(context: Context, batch: List<String>, remainingBatches: List<List<String>>, meta: TalerAbortMeta, threadId: Long) {
    val correlationId = java.util.UUID.randomUUID().toString()
    TalerCorrelationStore.putAbort(correlationId, TalerAbortRequest(batch, remainingBatches, meta, threadId))

    val link = Uri.Builder()
      .scheme("talerlink")
      .authority("compose-abort")
      .appendQueryParameter("v", "1")
      .apply {
        // Taler kennt nur taler://, DD-103-https-Links werden vorher
        // entpackt; TalerAbortPlan.apply ordnet die Ergebnisse wieder zu.
        batch.forEach { appendQueryParameter("uri", TalerHttpsLink.unwrap(it)) }
        appendQueryParameter("reason", meta.reason)
        appendQueryParameter("isGroup", meta.isGroup.toString())
        meta.recipientHint?.let { appendQueryParameter("recipientHint", it) }
        meta.memberCount?.let { appendQueryParameter("memberCount", it.toString()) }
      }
      .appendQueryParameter("correlationId", correlationId)
      .appendQueryParameter("returnUri", TalerAllowlist.RETURN_URI)
      .build()

    val intent = Intent(Intent.ACTION_VIEW, link).apply {
      setClassName(TalerAllowlist.PACKAGE, "net.taler.wallet.main.MainActivity")
      setPackage(TalerAllowlist.PACKAGE)
    }
    // Gleicher Grund wie TalerSendActions: Taler kann inzwischen fehlen.
    // Beim Menue-Abbrechen gibt es keinen lokalen Fallback, die Zahlung
    // bleibt OFFEN.
    runCatching { context.startActivity(intent) }
  }
}
