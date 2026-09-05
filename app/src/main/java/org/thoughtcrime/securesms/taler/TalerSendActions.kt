package org.thoughtcrime.securesms.taler

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import net.taler.wallet.link.PrepareSendRequest
import net.taler.wallet.link.TalerUriKind
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Klick-Handler fuer "Send with Taler" im Anhang-Menue (Meilenstein 5,
 * seither umgebaut). Signal fragt Betrag/Waehrung/Zweck NICHT mehr selbst ab
 * (Regel: Signal darf keine Taler-Salden/Betraege kennen) - der Button
 * oeffnet Taler direkt und gibt nur unbedenkliche Kontextinfo mit
 * (Empfaenger-Hinweis, Gruppe/Mitgliederzahl, Verschwinde-Nachrichten-Status),
 * anhand derer Taler seinen eigenen Betrags-Screen zeigt. Frueher zusaetzlich
 * ueber einen zweiten Button (TalerPaymentActions) fuer Gruppen erreichbar -
 * inzwischen vereinheitlicht, dieser eine Handler deckt 1:1 und Gruppen ab
 * (Gating in AttachmentKeyboardFragment.kt, Backstop in
 * TalerReturnActivity.kt).
 */
object TalerSendActions {

  // direction: PAY_PUSH (Geld senden) oder PAY_PULL (Geld anfordern) - die
  // beiden vom Anhang-Menue aus erreichbaren Taler-Aktionen (TALER_SEND/
  // TALER_REQUEST in AttachmentKeyboardButton). Andere TalerUriKind-Werte
  // sind hier nicht sinnvoll (siehe PrepareSendRequest.direction).
  fun onSendClicked(context: Context, recipient: Recipient, threadId: Long, direction: TalerUriKind) {
    val correlationId = java.util.UUID.randomUUID().toString()
    TalerCorrelationStore.put(correlationId, TalerCorrelationIntent.SEND, uri = null, threadId = threadId)
    val returnUri = "signalfuergnu://taler-return"

    val request = PrepareSendRequest(
      recipientHint = recipient.getDisplayName(context),
      isGroup = recipient.isGroup,
      memberCount = if (recipient.isGroup) recipient.participantIds.size else null,
      disappearingMessagesSeconds = recipient.expiresInSeconds,
      correlationId = correlationId,
      returnUri = returnUri,
      direction = direction,
    )

    // Kein Fragment/Activity-Referenz mit eigenem lifecycleScope hier
    // verfuegbar (Aufruf direkt aus dem Anhang-Menue-Callback) - ein
    // einmaliger MainScope() ist vertretbar, weil der einzige sichtbare
    // Effekt (startActivity) harmlos bleibt, falls der Aufrufer laengst
    // verschwunden ist.
    MainScope().launch {
      val client = TalerLinkClient(context.applicationContext)
      when (val result = client.prepareSend(request)) {
        is TalerLinkResult.Ergebnis -> {
          val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.value.deepLink)).apply {
            setClassName(TalerAllowlist.PACKAGE, "net.taler.wallet.main.MainActivity")
            setPackage(TalerAllowlist.PACKAGE)
          }
          // Zwischen dem erfolgreichen prepareSend()-Aufruf oben und diesem
          // startActivity() koennte Taler deinstalliert oder die Ziel-Activity
          // umbenannt worden sein - eine ActivityNotFoundException wuerde sonst
          // ungefangen durchschlagen und Signal abstuerzen lassen. runCatching
          // verwirft das Ergebnis bewusst (gleiche stille-Abbruch-Haltung wie
          // der else-Zweig unten, kein Toast, kein erwarteter Fall).
          runCatching { context.startActivity(intent) }
        }
        else -> Unit // NichtInstalliert/NichtVertrauenswuerdig/KeinConsent/Fehler:
                      // Menuepunkt sollte hier ohnehin nicht erreichbar gewesen
                      // sein (Gating passiert in einem separaten Task) - stiller
                      // Abbruch statt Toast, kein erwarteter Fall.
      }
    }
  }
}
