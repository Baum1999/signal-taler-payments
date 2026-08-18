package org.thoughtcrime.securesms.taler

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase

/**
 * Klick-Handler fuer die Annehmen-/Ablehnen-Buttons der Taler-Zahlungskarte -
 * aus TalerPaymentCardPresenter ausgelagert, damit diese Datei nicht ueber
 * ~150 Zeilen waechst (siehe Planungsnotiz Task 8, Schritt 3).
 */
object TalerAcceptRejectActions {

  /**
   * Oeffnet Talers eigene Bestaetigungs-UI ueber einen expliziten Deep Link
   * (setPackage gegen TalerAllowlist.PACKAGE - kein impliziter Intent, siehe
   * docs/API.md 2.2). Die correlationId erlaubt TalerReturnActivity spaeter,
   * den Ruecksprung eindeutig diesem Annehmen-Versuch zuzuordnen
   * (TalerCorrelationStore, Task 4).
   */
  fun onAcceptClicked(context: Context, uri: String, threadId: Long) {
    val correlationId = java.util.UUID.randomUUID().toString()
    TalerCorrelationStore.put(correlationId, uri, threadId)

    val returnUri = "signalfuergnu://taler-return"
    val separator = if (uri.contains("?")) "&" else "?"
    val target = "$uri${separator}correlationId=${Uri.encode(correlationId)}&returnUri=${Uri.encode(returnUri)}"

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
      setPackage(TalerAllowlist.PACKAGE)
    }
    context.startActivity(intent)
  }

  /**
   * Rein lokale Ablehnung - kein Netzwerkzugriff, keine Benachrichtigung des
   * Absenders. Der Bestaetigungsdialog-Text (strings.xml) darf nie
   * suggerieren, dass Geld zurueckfliesst - nur, dass dieses Geraet den
   * Vorgang nicht mehr verfolgt (REVIEW.md, globale Vorgabe).
   */
  fun onRejectClicked(context: Context, uri: String, threadId: Long) {
    AlertDialog.Builder(context)
      .setTitle(R.string.TalerFork_decline_dialog_title)
      .setMessage(R.string.TalerFork_decline_dialog_message)
      .setPositiveButton(R.string.TalerFork_decline_dialog_confirm) { _, _ ->
        // uri/threadId wurden beim Button-Bind erfasst, seitdem ist echte
        // Zeit vergangen (der Bestaetigungsdialog war offen) - der Vorgang
        // koennte sich in der Zwischenzeit z.B. durch einen abgeschlossenen
        // Poll bereits geaendert haben (REVIEW.md, Finding 3b). Deshalb hier
        // frisch nachlesen und nur noch anwenden, wenn er wirklich noch OFFEN
        // ist - sonst stillschweigend nichts tun (verlorenes Rennen, kein
        // Fehler; die Karte zeigt beim naechsten Rebind ohnehin den echten
        // aktuellen Zustand).
        if (SignalDatabase.talerPayments.getByUri(uri)?.status == TalerPaymentStatus.OFFEN) {
          SignalDatabase.talerPayments.updateStatus(uri, TalerPaymentStatus.LOKAL_ABGELEHNT)
          SignalDatabase.talerPayments.insertLocalStatusLine(threadId, TalerPaymentStatus.LOKAL_ABGELEHNT)
        }
      }
      .setNegativeButton(R.string.TalerFork_decline_dialog_cancel, null)
      .show()
  }
}
