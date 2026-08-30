package org.thoughtcrime.securesms.taler

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
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
 * 
 * Neues Design (Meilenstein 7+) basierend auf taler-kachel-preview.html:
 * - Kompakter Status: Icon + Kurzform statt langem Satz
 * - Richtung statt Namen: "von dir" / "an dich" statt Sender -> Empfaenger
 * - Exchange als unauffaelliger Chip
 * - Waehrung als Symbol (z.B. "ク" fuer KUDOS)
 * - Taler-Branding als SVG-Logo statt Text
 */
object TalerPaymentCardPresenter {

  /**
   * Währungssymbole wie von der echten Taler-Wallet-App angezeigt.
   * KUDOS wird als "ク" (Katakana "ku") dargestellt.
   */
  private val REAL_APP_SYMBOL = mapOf("KUDOS" to "ク", "EUR" to "€", "USD" to "$")

  private fun getCurrencySymbol(currency: String?): String {
    return currency?.let { REAL_APP_SYMBOL[it] ?: it } ?: ""
  }

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

    // ========================================================================
    // View Referenzen (neues Layout)
    // ========================================================================
    
    // Brand Row
    val kindView = view.findViewById<TextView>(R.id.taler_card_kind)
    val previewBadge = view.findViewById<TextView>(R.id.taler_card_preview_badge)

    // Amount Row
    val directionView = view.findViewById<TextView>(R.id.taler_card_direction)
    val amountView = view.findViewById<TextView>(R.id.taler_card_amount)
    val currencyView = view.findViewById<TextView>(R.id.taler_card_currency)

    // Party
    val partyView = view.findViewById<TextView>(R.id.taler_card_party)

    // Split Note
    val splitNoteView = view.findViewById<TextView>(R.id.taler_card_split_note)

    // Summary
    val summaryView = view.findViewById<TextView>(R.id.taler_card_summary)

    // Exchange Chip
    val exchangeChip = view.findViewById<LinearLayout>(R.id.taler_card_exchange_chip)
    val exchangeTextView = view.findViewById<TextView>(R.id.taler_card_exchange)

    // Status
    val statusLayout = view.findViewById<LinearLayout>(R.id.taler_card_status)
    val statusIconView = view.findViewById<TextView>(R.id.taler_card_status_icon)
    val statusTextView = view.findViewById<TextView>(R.id.taler_card_status_text)
    
    // Split Progress
    val splitProgressContainer = view.findViewById<LinearLayout>(R.id.taler_card_split_progress_container)

    // Split Warning
    val splitWarningView = view.findViewById<TextView>(R.id.taler_card_split_warning)

    // ========================================================================
    // Daten extrahieren
    // ========================================================================
    
    val talerStatus = record?.status ?: TalerPaymentStatus.UNBEKANNT_OFFLINE
    val isOwnPayment = record?.isOwnPayment ?: false
    val uriKind = record?.uriKind

    // ========================================================================
    // Richtung berechnen (neues Design)
    // ========================================================================
    val isPull = uriKind == TalerUriKind.PAY_PULL.name
    val moneyLeavesMe = isPull != isOwnPayment
    val directionIn = !moneyLeavesMe
    
    val arrow = if (directionIn) context.getString(R.string.TalerFork_direction_in) else context.getString(R.string.TalerFork_direction_out)
    val partyText = if (directionIn) context.getString(R.string.TalerFork_party_to_you) else context.getString(R.string.TalerFork_party_from_you)

    // ========================================================================
    // Brand Row
    // ========================================================================
    kindView.text = kindLabel(context, uriKind)
    previewBadge.visibility = View.GONE

    // ========================================================================
    // Amount Row: Pfeil + Betrag + Währungssymbol
    // ========================================================================
    val formattedAmount = record?.amount?.replace(".", ",") ?: "0"
    directionView.text = arrow
    amountView.text = formattedAmount
    currencyView.text = getCurrencySymbol(record?.currency)

    // ========================================================================
    // Party
    // ========================================================================
    partyView.text = partyText

    // ========================================================================
    // Split Note (vorlaeufig nicht implementiert)
    // ========================================================================
    splitNoteView.visibility = View.GONE

    // ========================================================================
    // Summary
    // ========================================================================
    summaryView.text = record?.summary
    summaryView.visible = !record?.summary.isNullOrBlank()

    // ========================================================================
    // Exchange Chip
    // ========================================================================
    val host = displayHost(record?.exchangeBaseUrl)
    if (!host.isNullOrBlank()) {
      exchangeTextView.text = host
      exchangeChip.visibility = View.VISIBLE
    } else {
      exchangeChip.visibility = View.GONE
    }

    // ========================================================================
    // Status
    // ========================================================================
    val statusInfo = getCompactStatus(context, record, talerStatus)
    val statusIcon = statusInfo[0] as String
    val statusText = statusInfo[1] as String
    val statusIconColorRes = statusInfo[2] as Int
    val statusTextColorRes = statusInfo[3] as Int
    
    statusIconView.text = statusIcon
    statusTextView.text = statusText
    
    // Farben - vorlaeufig nur Light Mode
    try {
      statusIconView.setTextColor(context.getColor(statusIconColorRes))
      statusTextView.setTextColor(context.getColor(statusTextColorRes))
    } catch (e: Exception) {
      // Fallback
    }

    // ========================================================================
    // Split Progress (vorlaeufig nicht implementiert)
    // ========================================================================
    statusLayout.visibility = View.VISIBLE
    splitProgressContainer.visibility = View.GONE

    // ========================================================================
    // Split Warning (vorlaeufig nicht implementiert)
    // ========================================================================
    splitWarningView.visibility = View.GONE

    // ========================================================================
    // Aktionen (unveraendert)
    // ========================================================================
    val actionsRow = view.findViewById<View>(R.id.taler_card_actions)
    val acceptButton = view.findViewById<Button>(R.id.taler_card_accept)
    val rejectButton = view.findViewById<Button>(R.id.taler_card_reject)
    val cancelButton = view.findViewById<Button>(R.id.taler_card_cancel)
    val refreshButton = view.findViewById<Button>(R.id.taler_card_refresh)
    val refundButton = view.findViewById<Button>(R.id.taler_card_refund)

    // Annehmen/Ablehnen nur bei einer Karte mit konkretem DB-Eintrag im
    // Zustand OFFEN - bei einer noch unbekannten (record == null) oder
    // bereits entschiedenen Karte gibt es nichts mehr zu entscheiden
    // (siehe docs/API.md 3.6, "Bewusst noch nicht enthalten").
    //
    // Zusaetzlich auf PAY_PUSH beschraenkt: der Taler-seitige Ruecksprung-
    // Mechanismus (TalerReturnActivity/TalerCorrelationStore) ist bisher nur
    // fuer pay-push-Vorgaenge Ende-zu-Ende verdrahtet.
    
    val showAcceptReject = record?.status == TalerPaymentStatus.OFFEN &&
      uriKind == TalerUriKind.PAY_PUSH.name &&
      !isOwnPayment
    val showCancelRefresh = record?.status == TalerPaymentStatus.OFFEN &&
      uriKind == TalerUriKind.PAY_PUSH.name &&
      isOwnPayment
    val showRefund = record?.status == TalerPaymentStatus.ANGENOMMEN &&
      !isOwnPayment

    actionsRow.visibility = if (showAcceptReject || showCancelRefresh || showRefund) View.VISIBLE else View.GONE
    acceptButton.visibility = if (showAcceptReject) View.VISIBLE else View.GONE
    rejectButton.visibility = if (showAcceptReject) View.VISIBLE else View.GONE
    cancelButton.visibility = if (showCancelRefresh) View.VISIBLE else View.GONE
    refreshButton.visibility = if (showCancelRefresh) View.VISIBLE else View.GONE
    refundButton.visibility = if (showRefund) View.VISIBLE else View.GONE

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

  /**
   * Liefert kompakte Status-Information: Icon, Text, Icon-Farbe, Text-Farbe
   * basierend auf dem neuen Design aus taler-kachel-preview.html
   */
  private fun getCompactStatus(
    context: Context,
    record: TalerPaymentRecord?, 
    status: TalerPaymentStatus
  ): Array<Any> {
    
    // Spezielle Behandlung fuer OFFENE pay-push-Vorgaenge
    if (status == TalerPaymentStatus.OFFEN && record?.uriKind == TalerUriKind.PAY_PUSH.name) {
      return if (record.isOwnPayment) {
        // Eigene ausgehende Zahlung: warte auf Empfaenger
        val recipientName = record.threadId?.let { threadId ->
          SignalDatabase.threads.getRecipientForThreadId(threadId)?.getDisplayName(context)
        }
        val text = if (!recipientName.isNullOrBlank()) {
          context.getString(R.string.TalerFork_status_waiting_for_recipient, recipientName)
        } else {
          context.getString(R.string.TalerFork_status_waiting_for_recipient, "Empfaenger")
        }
        arrayOf(
          context.getString(R.string.TalerFork_status_icon_waiting),
          text,
          R.color.taler_status_waiting_light,
          R.color.taler_status_waiting_light
        )
      } else {
        // Eingehende Zahlung: warte auf eigene Annahme
        arrayOf(
          context.getString(R.string.TalerFork_status_icon_waiting),
          context.getString(R.string.TalerFork_status_compact_waiting_acceptance),
          R.color.taler_status_waiting_light,
          R.color.taler_status_waiting_light
        )
      }
    }
    
    // Spezielle Behandlung fuer ANGENOMMENE pay-push-Vorgaenge
    if (status == TalerPaymentStatus.ANGENOMMEN && record?.uriKind == TalerUriKind.PAY_PUSH.name) {
      return if (record.isOwnPayment) {
        // Eigene ausgehende Zahlung: wurde vom Empfaenger angenommen
        val otherPartyName = record.threadId?.let { threadId ->
          SignalDatabase.threads.getRecipientForThreadId(threadId)?.getDisplayName(context)
        }
        val text = if (!otherPartyName.isNullOrBlank()) {
          context.getString(R.string.TalerFork_status_sent_confirmed, otherPartyName)
        } else {
          context.getString(R.string.TalerFork_status_sent_confirmed, "Empfaenger")
        }
        arrayOf(
          context.getString(R.string.TalerFork_status_icon_accepted),
          text,
          R.color.taler_status_ok_light,
          R.color.taler_status_ok_light
        )
      } else {
        // Eingehende Zahlung: wurde von uns angenommen
        val otherPartyName = record.threadId?.let { threadId ->
          SignalDatabase.threads.getRecipientForThreadId(threadId)?.getDisplayName(context)
        }
        val text = if (!otherPartyName.isNullOrBlank()) {
          context.getString(R.string.TalerFork_status_accepted_by_sender, otherPartyName)
        } else {
          context.getString(R.string.TalerFork_status_accepted_by_sender, "Sender")
        }
        arrayOf(
          context.getString(R.string.TalerFork_status_icon_accepted),
          text,
          R.color.taler_status_ok_light,
          R.color.taler_status_ok_light
        )
      }
    }
    
    // Standard-Fall: kompakter Status
    val iconRes = when (status) {
      TalerPaymentStatus.OFFEN -> R.string.TalerFork_status_icon_open
      TalerPaymentStatus.ANGENOMMEN -> R.string.TalerFork_status_icon_accepted
      TalerPaymentStatus.LOKAL_ABGELEHNT -> R.string.TalerFork_status_icon_declined
      TalerPaymentStatus.ABGELAUFEN -> R.string.TalerFork_status_icon_expired
      TalerPaymentStatus.UNBEKANNT_OFFLINE -> R.string.TalerFork_status_icon_checking
      TalerPaymentStatus.UNGUELTIG -> R.string.TalerFork_status_icon_invalid
      TalerPaymentStatus.TALER_NICHT_VERBUNDEN -> R.string.TalerFork_status_icon_not_connected
      TalerPaymentStatus.NICHT_INSTALLIERT -> R.string.TalerFork_status_icon_not_installed
      TalerPaymentStatus.NICHT_VERTRAUENSWUERDIG -> R.string.TalerFork_status_icon_untrusted
    }
    
    val textRes = when (status) {
      TalerPaymentStatus.OFFEN -> R.string.TalerFork_status_compact_open
      TalerPaymentStatus.ANGENOMMEN -> R.string.TalerFork_status_compact_accepted
      TalerPaymentStatus.LOKAL_ABGELEHNT -> R.string.TalerFork_status_compact_declined
      TalerPaymentStatus.ABGELAUFEN -> R.string.TalerFork_status_compact_expired
      TalerPaymentStatus.UNBEKANNT_OFFLINE -> R.string.TalerFork_status_compact_checking
      TalerPaymentStatus.UNGUELTIG -> R.string.TalerFork_status_compact_invalid
      TalerPaymentStatus.TALER_NICHT_VERBUNDEN -> R.string.TalerFork_status_compact_not_connected
      TalerPaymentStatus.NICHT_INSTALLIERT -> R.string.TalerFork_status_compact_not_installed
      TalerPaymentStatus.NICHT_VERTRAUENSWUERDIG -> R.string.TalerFork_status_compact_untrusted
    }
    
    // Farben basierend auf Status-Kategorie
    val (iconColorRes, textColorRes) = when (status) {
      TalerPaymentStatus.ANGENOMMEN -> Pair(R.color.taler_status_ok_light, R.color.taler_status_ok_light)
      TalerPaymentStatus.LOKAL_ABGELEHNT, TalerPaymentStatus.ABGELAUFEN, TalerPaymentStatus.UNGUELTIG, TalerPaymentStatus.NICHT_VERTRAUENSWUERDIG -> 
        Pair(R.color.taler_status_bad_light, R.color.taler_status_bad_light)
      else -> Pair(R.color.taler_status_neutral_light, R.color.taler_status_neutral_light)
    }
    
    return arrayOf(
      context.getString(iconRes),
      context.getString(textRes),
      iconColorRes,
      textColorRes
    )
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
