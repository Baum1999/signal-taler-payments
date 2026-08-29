package org.thoughtcrime.securesms.taler

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import net.taler.wallet.link.PrepareRefundRequest

/**
 * Klick-Handler fuer den "Refund"-Button auf einer angenommenen, eingehenden
 * Taler-Zahlungskarte (Meilenstein 6).
 *
 * Nutzt den eigenen prepareRefund-AIDL-Kanal (PrepareRefundRequest), NICHT
 * prepareSend - bewusst getrennt von PrepareSendRequest, das seit dem
 * Sende-Redesign keine Betrags-/Zweck-Felder mehr traegt. originalUri ist
 * die einzige Angabe, die Signal macht; Taler loest daraus selbst die
 * Original-Transaktion auf (TalerLinkService.resolveReceivedPeerPushCreditId)
 * und befuellt den Rueckerstattungs-Screen (ComposeRefundScreen, taler-android)
 * anhand dieser autoritativen Daten vor - Signal erfindet oder cached keinen
 * eigenen Betrag/Zweck fuer diesen Aufruf (Regel 4).
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

  fun onRefundClicked(context: Context, uri: String, threadId: Long) {
    val correlationId = java.util.UUID.randomUUID().toString()
    TalerCorrelationStore.put(correlationId, TalerCorrelationIntent.REFUND, uri = uri, threadId = threadId)
    val returnUri = "signalfuergnu://taler-return"

    val request = PrepareRefundRequest(
      originalUri = uri,
      correlationId = correlationId,
      returnUri = returnUri,
    )

    // Kein Fragment/Activity-Referenz mit eigenem lifecycleScope hier
    // verfuegbar (reiner Klick-Handler von der Zahlungskarte) - gleiches
    // Muster wie TalerSendActions.onSendClicked.
    MainScope().launch {
      val client = TalerLinkClient(context.applicationContext)
      when (val result = client.prepareRefund(request)) {
        is TalerLinkResult.Ergebnis -> {
          val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.value.deepLink)).apply {
            setClassName(TalerAllowlist.PACKAGE, "net.taler.wallet.main.MainActivity")
            setPackage(TalerAllowlist.PACKAGE)
          }
          runCatching { context.startActivity(intent) }
        }
        else -> Unit // NichtInstalliert/NichtVertrauenswuerdig/KeinConsent/Fehler:
                      // Button sollte hier ohnehin nicht erreichbar gewesen sein
                      // (Gating siehe TalerPaymentCardPresenter) - stiller Abbruch.
      }
    }
  }
}
