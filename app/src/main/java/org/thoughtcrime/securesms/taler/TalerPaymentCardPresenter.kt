package org.thoughtcrime.securesms.taler

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.LinearLayout
import android.widget.TextView
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.TalerPaymentRecord
import org.thoughtcrime.securesms.util.visible
import java.net.IDN

/**
 * Baut die Taler-Zahlungskarte(n) unterhalb einer Nachricht - ausgelagert aus
 * V2ConversationItemTextOnlyViewHolder (REVIEW.md H4), damit der Eingriff in
 * diese Upstream-Datei auf den einen Aufruf in presentTalerCard() beschraenkt
 * bleibt. Alle nutzersichtbaren Strings kommen aus strings.xml (REVIEW.md P2).
 */
object TalerPaymentCardPresenter {

  /**
   * [messageBody] ist der unveraenderte Klartext-Body - fremde Signal-Clients
   * sehen weiterhin nur reinen Text, die Karte ist ausschliesslich lokale
   * Darstellung. Es werden nur URIs angezeigt, die die lokale
   * Taler-Schnittstelle bereits (durch TalerUriRefreshJob) bestaetigt hat;
   * solange kein Ergebnis vorliegt, zeigt die Karte [TalerPaymentStatus.UNBEKANNT_OFFLINE].
   */
  fun present(root: ViewGroup, stub: ViewStub?, messageBody: String) {
    if (stub == null) return
    val uris = TalerUriDetector.findUris(messageBody)

    // Deckt auch den Fall ab, dass ein Chat mit einer laengst getrackten URI
    // nur geoeffnet/gescrollt wird (kein neuer Sende-/Empfangs-Hook noetig,
    // um Polling in Gang zu setzen) - siehe Schritt 4g/docs/API.md.
    if (uris.isNotEmpty()) {
      TalerPollingCoordinator.ensureStarted()
    }

    val container = root.findViewById<LinearLayout?>(R.id.taler_payment_cards)
      ?: if (uris.isEmpty()) null else stub.inflate() as LinearLayout

    if (container == null) return

    container.removeAllViews()
    if (uris.isEmpty()) {
      container.visibility = View.GONE
      return
    }

    container.visibility = View.VISIBLE
    val inflater = LayoutInflater.from(container.context)
    for (uri in uris) {
      val record = SignalDatabase.talerPayments.getByUri(uri)
      val cardView = inflater.inflate(R.layout.taler_payment_card, container, false)
      bind(cardView, record)
      container.addView(cardView)
    }
  }

  private fun bind(view: View, record: TalerPaymentRecord?) {
    val context = view.context
    val kind = view.findViewById<TextView>(R.id.taler_card_kind)
    val amount = view.findViewById<TextView>(R.id.taler_card_amount)
    val summary = view.findViewById<TextView>(R.id.taler_card_summary)
    val exchange = view.findViewById<TextView>(R.id.taler_card_exchange)
    val status = view.findViewById<TextView>(R.id.taler_card_status)

    kind.text = kindLabel(context, record?.uriKind)

    if (record?.amount != null && record.currency != null) {
      amount.text = "${record.amount} ${record.currency}"
      amount.visible = true
    } else {
      amount.visible = false
    }

    summary.text = record?.summary
    summary.visible = !record?.summary.isNullOrBlank()

    val host = displayHost(record?.exchangeBaseUrl)
    exchange.text = host
    exchange.visible = host != null

    val talerStatus = record?.status ?: TalerPaymentStatus.UNBEKANNT_OFFLINE
    status.text = statusLabel(context, talerStatus)
  }

  private fun kindLabel(context: Context, kind: String?): String = context.getString(
    when (kind) {
      "PAY_PUSH" -> R.string.TalerFork_kind_pay_push
      "PAY_PULL" -> R.string.TalerFork_kind_pay_pull
      "PAY" -> R.string.TalerFork_kind_pay
      "WITHDRAW" -> R.string.TalerFork_kind_withdraw
      "REFUND" -> R.string.TalerFork_kind_refund
      else -> R.string.TalerFork_kind_unknown
    }
  )

  private fun statusLabel(context: Context, status: TalerPaymentStatus): String = context.getString(
    when (status) {
      TalerPaymentStatus.OFFEN -> R.string.TalerFork_status_open
      TalerPaymentStatus.ANGENOMMEN -> R.string.TalerFork_status_accepted
      TalerPaymentStatus.LOKAL_ABGELEHNT -> R.string.TalerFork_status_declined
      TalerPaymentStatus.ABGELAUFEN -> R.string.TalerFork_status_expired
      TalerPaymentStatus.UNBEKANNT_OFFLINE -> R.string.TalerFork_status_checking
      TalerPaymentStatus.UNGUELTIG -> R.string.TalerFork_status_invalid
      TalerPaymentStatus.TALER_NICHT_VERBUNDEN -> R.string.TalerFork_status_not_connected
      TalerPaymentStatus.NICHT_INSTALLIERT -> R.string.TalerFork_status_not_installed
      TalerPaymentStatus.NICHT_VERTRAUENSWUERDIG -> R.string.TalerFork_status_untrusted
    }
  )

  /**
   * P4 (REVIEW.md): [exchangeBaseUrl]s Host kommt vom Absender der Nachricht
   * (der die URI formuliert hat), nicht von Taler selbst - Uri.host normiert
   * nicht auf ASCII/Punycode, ein Host mit Nicht-ASCII-Zeichen wuerde sonst
   * ungeprueft in seiner potenziell irrefuehrenden Unicode-Form gerendert
   * (Homograph-Risiko, z.B. kyrillische Look-alikes). ASCII-Hosts (der
   * Normalfall) bleiben unveraendert. Schlaegt IDN.toASCII fehl, wird der
   * Host lieber ganz ausgeblendet als in ungeprüfter Form gezeigt.
   */
  private fun displayHost(exchangeBaseUrl: String?): String? {
    val host = exchangeBaseUrl?.let { Uri.parse(it).host } ?: return null
    if (host.any { it.code > 127 }) {
      return try {
        IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
      } catch (e: IllegalArgumentException) {
        null
      }
    }
    return host
  }
}
