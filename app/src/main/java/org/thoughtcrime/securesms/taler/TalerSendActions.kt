package org.thoughtcrime.securesms.taler

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import net.taler.wallet.link.TalerUriKind
import org.thoughtcrime.securesms.providers.AvatarProvider
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Klick-Handler fuer "Send with Taler" im Anhang-Menue (Meilenstein 5,
 * seither umgebaut). Signal fragt Betrag/Waehrung/Zweck NICHT selbst ab
 * (Regel: Signal darf keine Taler-Salden/Betraege kennen) - der Button
 * oeffnet Taler direkt und gibt nur unbedenkliche Kontextinfo mit
 * (Empfaenger-Hinweis, Gruppe/Mitgliederzahl, Verschwinde-Nachrichten-Status),
 * anhand derer Taler seinen eigenen Betrags-Screen zeigt. Frueher zusaetzlich
 * ueber einen zweiten Button (TalerPaymentActions) fuer Gruppen erreichbar -
 * inzwischen vereinheitlicht, dieser eine Handler deckt 1:1 und Gruppen ab
 * (Gating in AttachmentKeyboardFragment.kt, Backstop in
 * TalerReturnActivity.kt).
 *
 * Der Kontext steht direkt in den Query-Parametern des geoeffneten Links.
 * Frueher lief er ueber einen vorgelagerten Binder-Aufruf (prepareSend), der
 * nichts weiter tat, als denselben Link zusammenzusetzen - der Umweg ist mit
 * der App-zu-App-Schnittstelle entfallen.
 */
object TalerSendActions {

  // Nur EIN Button in Signal (Nutzer-Vorgabe 2026-09-05: "in Signal nur ein
  // Button") - die Richtung (Senden/Anfordern) waehlt der Nutzer stattdessen
  // ueber ein Toggle ganz oben in Talers eigenem Compose-Screen. direction
  // hier ist deshalb nur der Anfangszustand dieses Toggles, keine feste
  // Vorgabe.
  fun onSendClicked(context: Context, recipient: Recipient, threadId: Long) {
    val correlationId = java.util.UUID.randomUUID().toString()
    TalerCorrelationStore.put(correlationId, TalerCorrelationIntent.SEND, uri = null, threadId = threadId)

    // AvatarProvider liefert bereits eine content://-URI ueber den
    // vorhandenen, nur intern (exported=false) exportierten Avatar-Provider
    // (grantUriPermissions=true) - kein eigener Temp-File-/FileProvider-Weg
    // noetig. Wie recipientHint nur ein unbedenklicher Anzeige-Hinweis
    // (Regel: Signal gibt nur Kontext mit, nie Betrags-/Guthabendaten).
    val avatarUri = AvatarProvider.getContentUri(recipient.id)

    val link = Uri.Builder()
      .scheme("talerlink")
      .authority("compose-send")
      .appendQueryParameter("recipientHint", recipient.getDisplayName(context))
      .appendQueryParameter("isGroup", recipient.isGroup.toString())
      .appendQueryParameter("direction", TalerUriKind.PAY_PUSH.name)
      .appendQueryParameter("disappearingMessagesSeconds", recipient.expiresInSeconds.toString())
      .appendQueryParameter("correlationId", correlationId)
      .appendQueryParameter("returnUri", "signalfuergnu://taler-return")
      .appendQueryParameter("avatarUri", avatarUri.toString())
      .apply {
        if (recipient.isGroup) {
          appendQueryParameter("memberCount", recipient.participantIds.size.toString())
        }
      }
      .build()

    val intent = Intent(Intent.ACTION_VIEW, link).apply {
      setClassName(TalerAllowlist.PACKAGE, "net.taler.wallet.main.MainActivity")
      setPackage(TalerAllowlist.PACKAGE)
      // avatarUri steht nur als String im Query-Teil von "link" - das
      // Leserecht fuer den exported=false-Provider muss separat erteilt
      // werden. ClipData + FLAG_GRANT_READ_URI_PERMISSION auf einem
      // expliziten Intent grantet es dem Zielpaket automatisch beim
      // Zustellen, ohne manuelles grantUriPermission/revoke.
      clipData = ClipData.newRawUri("avatar", avatarUri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // Taler koennte zwischen Anzeige des Menuepunkts und diesem Aufruf
    // deinstalliert oder die Ziel-Activity umbenannt worden sein - eine
    // ActivityNotFoundException wuerde sonst ungefangen durchschlagen und
    // Signal abstuerzen lassen. runCatching verwirft das Ergebnis bewusst
    // (stiller Abbruch, kein Toast, kein erwarteter Fall).
    runCatching { context.startActivity(intent) }
  }
}
