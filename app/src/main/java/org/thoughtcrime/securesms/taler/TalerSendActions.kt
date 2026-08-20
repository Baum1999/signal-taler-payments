package org.thoughtcrime.securesms.taler

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import net.taler.wallet.link.PrepareSendRequest
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Klick-Handler fuer "Send with Taler" im Anhang-Menue (Meilenstein 5).
 * "Eingabe X" passiert hier (rein lokal, keine Balance-Abfrage moeglich -
 * Signal darf Taler-Salden nicht abfragen) - Gebuehr und die eigentliche
 * Bestaetigung passieren ausschliesslich in Talers eigener UI (Regel: Signal
 * bestaetigt niemals selbst eine Zahlung).
 */
object TalerSendActions {

  fun onSendClicked(context: Context, recipient: Recipient, threadId: Long) {
    val view = android.view.LayoutInflater.from(context).inflate(R.layout.taler_send_amount_dialog, null)
    val amountField = view.findViewById<EditText>(R.id.taler_send_amount)
    val currencyField = view.findViewById<EditText>(R.id.taler_send_currency)
    val purposeField = view.findViewById<EditText>(R.id.taler_send_purpose)

    AlertDialog.Builder(context)
      .setTitle(R.string.TalerFork_send_dialog_title)
      .setView(view)
      .setPositiveButton(R.string.TalerFork_send_dialog_confirm) { _, _ ->
        val amount = amountField.text?.toString()?.trim().orEmpty()
        val currency = currencyField.text?.toString()?.trim().orEmpty()
        val purpose = purposeField.text?.toString()?.trim()?.ifBlank { null }
        if (amount.isNotEmpty() && currency.isNotEmpty()) {
          startCompose(context, recipient, threadId, amount, currency, purpose)
        }
      }
      .setNegativeButton(R.string.TalerFork_send_dialog_cancel, null)
      .show()
  }

  private fun startCompose(
    context: Context,
    recipient: Recipient,
    threadId: Long,
    amount: String,
    currency: String,
    purpose: String?,
  ) {
    val correlationId = java.util.UUID.randomUUID().toString()
    TalerCorrelationStore.put(correlationId, uri = null, threadId = threadId)
    val returnUri = "signalfuergnu://taler-return"

    val request = PrepareSendRequest(
      amount = amount,
      currency = currency,
      recipientHint = recipient.getDisplayName(context),
      purpose = purpose,
      correlationId = correlationId,
      returnUri = returnUri,
    )

    // Kein Fragment/Activity-Referenz mit eigenem lifecycleScope hier
    // verfuegbar (reiner Dialog-Callback) - ein einmaliger MainScope() ist
    // vertretbar, weil der einzige sichtbare Effekt (startActivity) harmlos
    // bleibt, falls der Dialog laengst geschlossen ist.
    MainScope().launch {
      val client = TalerLinkClient(context.applicationContext)
      when (val result = client.prepareSend(request)) {
        is TalerLinkResult.Ergebnis -> {
          val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.value.deepLink)).apply {
            setClassName(TalerAllowlist.PACKAGE, "net.taler.wallet.main.MainActivity")
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
