package org.thoughtcrime.securesms.taler

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.LinearLayout
import android.widget.TextView
import net.taler.wallet.link.TalerUriKind
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.TalerPaymentRecord
import org.thoughtcrime.securesms.recipients.Recipient
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
    onCancel: (uri: String, threadId: Long) -> Unit = { _, _ -> },
    onRefresh: (uri: String, threadId: Long) -> Unit = { _, _ -> },
    onRefund: (uri: String, threadId: Long) -> Unit = { _, _ -> },
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
      bind(cardView, record, onAccept, onReject, onCancel, onRefresh, onRefund)
      container.addView(cardView)
    }
  }

  /**
   * Rendert eine einzelne Zahlungskarte losgeloest von einer Nachricht - fuer
   * den "Als Bild"-Weiterleiten-Snapshot (siehe TalerForwardGate). Nutzt
   * dieselbe bind()-Logik wie present(), blendet die Aktionsreihe aber immer
   * aus: ein statisches PNG kann Annehmen/Ablehnen/Abbrechen/Refresh/Refund
   * nicht ausfuehren, ein funktional wirkender Button waere irrefuehrend.
   */
  fun renderStandalone(context: Context, record: TalerPaymentRecord?): View {
    val view = LayoutInflater.from(context).inflate(R.layout.taler_payment_card, null, false)
    bind(view, record, { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> })
    view.findViewById<View>(R.id.taler_card_actions).visibility = View.GONE
    return view
  }

  private fun bind(
    view: View,
    record: TalerPaymentRecord?,
    onAccept: (uri: String, threadId: Long) -> Unit,
    onReject: (uri: String, threadId: Long) -> Unit,
    onCancel: (uri: String, threadId: Long) -> Unit,
    onRefresh: (uri: String, threadId: Long) -> Unit,
    onRefund: (uri: String, threadId: Long) -> Unit,
  ) {
    val context = view.context
    val kind = view.findViewById<TextView>(R.id.taler_card_kind)
    val amount = view.findViewById<TextView>(R.id.taler_card_amount)
    val summary = view.findViewById<TextView>(R.id.taler_card_summary)
    val exchange = view.findViewById<TextView>(R.id.taler_card_exchange)
    val status = view.findViewById<TextView>(R.id.taler_card_status)

    kind.text = kindLabel(context, record?.uriKind)

    // Erweitere Betragszeile mit Sender/Empfaenger-Information
    if (record?.amount != null && record.currency != null) {
      // Versuche, Sender und Empfaenger zu ermitteln
      val otherPartyName = record.threadId?.let { threadId ->
        SignalDatabase.threads.getRecipientForThreadId(threadId)?.getDisplayName(context)
      }
      
      val senderName = if (record.isOwnPayment) {
        // Eigene Zahlung: Sender ist "You/Du", Empfaenger ist otherPartyName
        context.getString(R.string.TalerFork_you)
      } else {
        // Eingehende Zahlung: Sender ist otherPartyName, Empfaenger ist "You/Du"
        otherPartyName
      }
      
      val recipientName = if (record.isOwnPayment) {
        otherPartyName
      } else {
        context.getString(R.string.TalerFork_you)
      }
      
      // Verwende das erweiterte Format mit Sender → Empfaenger: Betrag Währung
      if (!senderName.isNullOrBlank() && !recipientName.isNullOrBlank()) {
        amount.text = context.getString(
          R.string.TalerFork_amount_with_parties,
          senderName,
          recipientName,
          record.amount,
          record.currency
        )
      } else {
        amount.text = "${record.amount} ${record.currency}"
      }
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
    status.text = getStatusText(context, record, talerStatus)

    val actionsRow = view.findViewById<android.view.View>(R.id.taler_card_actions)
    val acceptButton = view.findViewById<android.widget.Button>(R.id.taler_card_accept)
    val rejectButton = view.findViewById<android.widget.Button>(R.id.taler_card_reject)
    val cancelButton = view.findViewById<android.widget.Button>(R.id.taler_card_cancel)
    val refreshButton = view.findViewById<android.widget.Button>(R.id.taler_card_refresh)
    val refundButton = view.findViewById<android.widget.Button>(R.id.taler_card_refund)

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
    //
    // Unterschied zwischen eigenen und fremden Zahlungen:
    // - isOwnPayment=false, OFFEN (eingehend, noch offen): zeige Annehmen/Ablehnen
    // - isOwnPayment=true, OFFEN (ausgehend, noch offen): zeige Abbrechen/Refresh
    // - isOwnPayment=false, ANGENOMMEN (Geld ist bei mir angekommen - egal ob
    //   durch einen empfangenen PAY_PUSH oder durch einen von mir erstellten
    //   PAY_PULL, den jemand bezahlt hat): zeige Refund - man kann nur Geld
    //   zurueckerstatten, das man tatsaechlich erhalten hat. Bewusst NICHT
    //   mehr auf PAY_PUSH beschraenkt (Fix: "Konterpfad"-Generalisierung) -
    //   TalerRefundActions sendet in beiden Faellen gleich per neuem PUSH
    //   zurueck, unabhaengig von der Art des Original-Links.
    val showAcceptReject = record?.status == TalerPaymentStatus.OFFEN &&
      record.uriKind == net.taler.wallet.link.TalerUriKind.PAY_PUSH.name &&
      !record.isOwnPayment
    val showCancelRefresh = record?.status == TalerPaymentStatus.OFFEN &&
      record.uriKind == net.taler.wallet.link.TalerUriKind.PAY_PUSH.name &&
      record.isOwnPayment
    val showRefund = record?.status == TalerPaymentStatus.ANGENOMMEN &&
      !record.isOwnPayment

    actionsRow.visibility = if (showAcceptReject || showCancelRefresh || showRefund) android.view.View.VISIBLE else android.view.View.GONE
    acceptButton.visibility = if (showAcceptReject) android.view.View.VISIBLE else android.view.View.GONE
    rejectButton.visibility = if (showAcceptReject) android.view.View.VISIBLE else android.view.View.GONE
    cancelButton.visibility = if (showCancelRefresh) android.view.View.VISIBLE else android.view.View.GONE
    refreshButton.visibility = if (showCancelRefresh) android.view.View.VISIBLE else android.view.View.GONE
    refundButton.visibility = if (showRefund) android.view.View.VISIBLE else android.view.View.GONE

    if (showAcceptReject && record != null) {
      acceptButton.setOnClickListener { onAccept(record.uri, record.threadId) }
      rejectButton.setOnClickListener { onReject(record.uri, record.threadId) }
    }
    if (showCancelRefresh && record != null) {
      cancelButton.setOnClickListener { onCancel(record.uri, record.threadId) }
      refreshButton.setOnClickListener { onRefresh(record.uri, record.threadId) }
    }
    if (showRefund && record != null) {
      refundButton.setOnClickListener { onRefund(record.uri, record.threadId) }
    }
  }

  fun kindLabel(context: Context, kind: String?): String = context.getString(
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
   * Erzeugt den anzuzeigenden Status-Text unter Beruecksichtigung des URI-Typs
   * und ob es sich um eine eigene oder fremde Zahlung handelt. Fuer OFFENE
   * pay-push-Zahlungen wird ein spezifischerer Text angezeigt, der klar macht,
   * worauf gewartet wird. Fuer ANGENOMMENE pay-push-Zahlungen wird der Sender
   * oder Empfaenger angezeigt.
   */
  private fun getStatusText(context: Context, record: TalerPaymentRecord?, status: TalerPaymentStatus): String {
    // Spezielle Behandlung fuer OFFENE pay-push-Vorgaenge
    if (status == TalerPaymentStatus.OFFEN && 
        record?.uriKind == net.taler.wallet.link.TalerUriKind.PAY_PUSH.name) {
      
      // Versuche, den Empfaenger-Namen zu ermitteln
      val recipientName = record.threadId?.let { threadId ->
        SignalDatabase.threads.getRecipientForThreadId(threadId)?.getDisplayName(context)
      }
      
      return if (record.isOwnPayment) {
        // Eigene ausgehende Zahlung: warte auf Empfaenger
        if (!recipientName.isNullOrBlank()) {
          context.getString(R.string.TalerFork_status_waiting_for_recipient, recipientName)
        } else {
          context.getString(R.string.TalerFork_status_waiting_for_recipient, "Empfaenger")
        }
      } else {
        // Eingehende Zahlung: warte auf eigene Annahme
        context.getString(R.string.TalerFork_status_waiting_for_acceptance)
      }
    }
    
    // Spezielle Behandlung fuer ANGENOMMENE pay-push-Vorgaenge
    if (status == TalerPaymentStatus.ANGENOMMEN && 
        record?.uriKind == net.taler.wallet.link.TalerUriKind.PAY_PUSH.name) {
      
      // Versuche, den Sender- oder Empfaenger-Namen zu ermitteln
      val otherPartyName = record.threadId?.let { threadId ->
        SignalDatabase.threads.getRecipientForThreadId(threadId)?.getDisplayName(context)
      }
      
      return if (record.isOwnPayment) {
        // Eigene ausgehende Zahlung: wurde vom Empfaenger angenommen
        if (!otherPartyName.isNullOrBlank()) {
          context.getString(R.string.TalerFork_status_sent_confirmed, otherPartyName)
        } else {
          context.getString(R.string.TalerFork_status_sent_confirmed, "Empfaenger")
        }
      } else {
        // Eingehende Zahlung: wurde von uns angenommen, zeige Sender
        if (!otherPartyName.isNullOrBlank()) {
          context.getString(R.string.TalerFork_status_accepted_by_sender, otherPartyName)
        } else {
          context.getString(R.string.TalerFork_status_accepted_by_sender, "Sender")
        }
      }
    }
    
    // Standard-Fall: verwende den einfachen Status-Text
    return statusLabel(context, status)
  }

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
