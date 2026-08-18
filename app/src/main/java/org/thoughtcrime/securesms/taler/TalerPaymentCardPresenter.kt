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
  fun present(
    root: ViewGroup,
    stub: ViewStub?,
    messageBody: String,
    onAccept: (uri: String, threadId: Long) -> Unit = { _, _ -> },
    onReject: (uri: String, threadId: Long) -> Unit = { _, _ -> },
  ) {
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
      bind(cardView, record, onAccept, onReject)
      container.addView(cardView)
    }
  }

  private fun bind(
    view: View,
    record: TalerPaymentRecord?,
    onAccept: (uri: String, threadId: Long) -> Unit,
    onReject: (uri: String, threadId: Long) -> Unit,
  ) {
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

    val actionsRow = view.findViewById<android.view.View>(R.id.taler_card_actions)
    val acceptButton = view.findViewById<android.widget.Button>(R.id.taler_card_accept)
    val rejectButton = view.findViewById<android.widget.Button>(R.id.taler_card_reject)

    // Annehmen/Ablehnen nur bei einer Karte mit konkretem DB-Eintrag im
    // Zustand OFFEN - bei einer noch unbekannten (record == null) oder
    // bereits entschiedenen Karte gibt es nichts mehr zu entscheiden
    // (siehe docs/API.md 3.6, "Bewusst noch nicht enthalten").
    //
    // Zusaetzlich auf PAY_PUSH beschraenkt: der Taler-seitige Ruecksprung-
    // Mechanismus (TalerReturnActivity/TalerCorrelationStore) ist bisher nur
    // fuer pay-push-Vorgaenge Ende-zu-Ende verdrahtet. Bei PAY_PULL wuerde
    // Annehmen zwar Talers Bestaetigungs-UI oeffnen, aber nie einen
    // funktionierenden Ruecksprung nach Signal ausloesen - ein funktionierend
    // aussehender Button ohne Wirkung waere schlimmer als gar keiner. Das ist
    // eine bewusste Umfangsbegrenzung fuer diesen Meilenstein, kein Versehen -
    // volle pay-pull-Unterstuetzung folgt in einem spaeteren Meilenstein.
    val showActions = record?.status == TalerPaymentStatus.OFFEN &&
      record.uriKind == net.taler.wallet.link.TalerUriKind.PAY_PUSH.name
    actionsRow.visibility = if (showActions) android.view.View.VISIBLE else android.view.View.GONE
    if (showActions && record != null) {
      acceptButton.setOnClickListener { onAccept(record.uri, record.threadId) }
      rejectButton.setOnClickListener { onReject(record.uri, record.threadId) }
    }
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
