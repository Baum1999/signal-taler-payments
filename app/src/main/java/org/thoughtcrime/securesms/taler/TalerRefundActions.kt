package org.thoughtcrime.securesms.taler

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import net.taler.wallet.link.PrepareRefundRequest

/**
 * Klick-Handler fuer den "Refund"-Button auf einer angenommenen, eingehenden
 * Taler-Zahlungskarte (Meilenstein 6). Anders als Annehmen/Ablehnen/Abbrechen
 * (TalerAcceptRejectActions - dort wird die URI direkt mit angehaengten
 * correlationId/returnUri-Parametern geoeffnet) folgt eine Rueckerstattung
 * dem AIDL-prepare*-Muster von TalerSendActions: sie ist eine NEUE Zahlung,
 * kein Rueckgriff auf die urspruengliche URI. Kein Eingabedialog - Betrag und
 * Zweck kommen aus Talers eigener, bereits von TalerLinkService.prepareRefund
 * aufgeloester Original-Transaktion (docs/API.md 2.4), nicht von Signal.
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

    // Gleicher Grund fuer den einmaligen MainScope() wie TalerSendActions.kt:
    // reiner Klick-Handler ohne eigenen lifecycleScope, einziger sichtbarer
    // Effekt ist startActivity(), das auch nach Verlassen des aufrufenden
    // Screens harmlos bleibt.
    MainScope().launch {
      val client = TalerLinkClient(context.applicationContext)
      when (val result = client.prepareRefund(request)) {
        is TalerLinkResult.Ergebnis -> {
          val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.value.deepLink)).apply {
            setClassName(TalerAllowlist.PACKAGE, "net.taler.wallet.main.MainActivity")
            setPackage(TalerAllowlist.PACKAGE)
          }
          // Gleicher Grund wie TalerSendActions.kt: Taler koennte zwischen
          // dem erfolgreichen prepareRefund()-Aufruf und diesem startActivity()
          // deinstalliert worden sein.
          runCatching { context.startActivity(intent) }
        }
        // NichtInstalliert/NichtVertrauenswuerdig/KeinConsent/Fehler: der
        // Refund-Button sollte hier ohnehin nicht erreichbar gewesen sein
        // (Gating siehe TalerPaymentCardPresenter) - stiller Abbruch statt
        // Toast, kein erwarteter Fall. KeinConsent deckt hier zusaetzlich den
        // Fall ab, dass TalerLinkService.prepareRefund die originalUri
        // eigenstaendig nicht als empfangene Zahlung aufloesen konnte (wirft
        // IllegalStateException, siehe dortiger Kommentar) - ohne eigene
        // Fehlervariante in PrepareRefundResult nicht von echtem
        // Consent-Fehlen unterscheidbar; siehe Meilenstein-6-Bericht.
        else -> Unit
      }
    }
  }
}
