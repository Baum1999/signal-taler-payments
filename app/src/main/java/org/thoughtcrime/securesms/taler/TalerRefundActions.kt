package org.thoughtcrime.securesms.taler

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Klick-Handler fuer den "Refund"-Button auf einer angenommenen, eingehenden
 * Taler-Zahlungskarte (Meilenstein 6).
 *
 * Oeffnet einen eigenen Compose-Einstieg in Taler (compose-refund), getrennt
 * vom Sende-Einstieg. originalUri ist die einzige Angabe, die Signal macht;
 * Taler loest daraus selbst die Original-Transaktion auf und befuellt den
 * Rueckerstattungs-Screen (ComposeRefundScreen, taler-android) anhand dieser
 * autoritativen Daten vor - Signal erfindet oder cached keinen eigenen
 * Betrag/Zweck fuer diesen Aufruf (Regel 4).
 *
 * Unterschied zum normalen Send-Rueckprung: TalerReturnActivity behandelt
 * TalerCorrelationIntent.REFUND weiterhin separat von SEND - die neue,
 * erfolgreich erzeugte Rueckerstattungs-URI wird beim Ruecksprung NICHT
 * automatisch als Nachricht verschickt, sondern als Entwurf ins
 * Signal-Nachrichtenfeld eingefuegt, damit der Nutzer sie vor dem Senden noch
 * sehen/anpassen kann (siehe TalerReturnActivity.insertRefundDraft).
 *
 * Das UI-Gate (Refund-Button nur bei status==ANGENOMMEN && !isOwnPayment
 * sichtbar, siehe TalerPaymentCardPresenter) bleibt die einzige
 * Zugriffsschranke.
 */
object TalerRefundActions {

  /**
   * quoteMessageId/quoteAuthor: optional, wenn der Aufrufer die urspruengliche
   * Zahlungsnachricht kennt (Long-Press-Menue - TalerMenuActions.onRefundFromMenu
   * ruft mit messageRecord.id/fromRecipient auf). Steuert, ob die
   * Rueckerstattungs-URI beim Ruecksprung als Zitat-Antwort auf diese Nachricht
   * vorbereitet wird statt als reiner Text-Entwurf (siehe TalerReturnActivity,
   * TalerCorrelationStore.Entry).
   */
  fun onRefundClicked(
    context: Context,
    uri: String,
    threadId: Long,
    quoteMessageId: Long? = null,
    quoteAuthor: RecipientId? = null,
  ) {
    val correlationId = java.util.UUID.randomUUID().toString()
    TalerCorrelationStore.put(
      correlationId,
      TalerCorrelationIntent.REFUND,
      uri = uri,
      threadId = threadId,
      quoteMessageId = quoteMessageId,
      quoteAuthor = quoteAuthor,
    )

    val link = Uri.Builder()
      .scheme("talerlink")
      .authority("compose-refund")
      .appendQueryParameter("originalUri", uri)
      .appendQueryParameter("correlationId", correlationId)
      .appendQueryParameter("returnUri", "signalfuergnu://taler-return")
      .build()

    val intent = Intent(Intent.ACTION_VIEW, link).apply {
      setClassName(TalerAllowlist.PACKAGE, "net.taler.wallet.main.MainActivity")
      setPackage(TalerAllowlist.PACKAGE)
    }
    // Stiller Abbruch, falls Taler inzwischen fehlt - gleiches Muster wie
    // TalerSendActions.onSendClicked.
    runCatching { context.startActivity(intent) }
  }
}
