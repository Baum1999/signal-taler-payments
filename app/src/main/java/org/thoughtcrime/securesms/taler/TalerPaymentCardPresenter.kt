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
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.TalerPaymentRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.TalerPaymentExtra
import org.thoughtcrime.securesms.keyvalue.SignalStore
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

  private val TAG = Log.tag(TalerPaymentCardPresenter::class.java)

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
    messageId: Long,
    threadId: Long,
    sender: Recipient,
    onAccept: (uri: String, threadId: Long) -> Unit = { _, _ -> },
    messageExtrasTalerPayment: TalerPaymentExtra? = null,
  ) {
    if (stub == null) return
    // Nur Arten, zu denen Signal ueberhaupt einen Vorgang fuehren kann - ein
    // pay/withdraw/refund-Link bleibt blosser Linktext statt einer Karte, die
    // dauerhaft "unbekannt" zeigen wuerde (siehe TalerPaymentTracker).
    val uris = urisFromMessageBody(messageBody).filter { TalerPaymentTracker.isTrackable(it) }

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

    // Gruppen-Split (Meilenstein 4, PROMPT_parallel_group_split.md): EINE
    // Sammelkarte fuer die ganze Nachricht statt einer Karte pro URI - das
    // "N Karten"-Wording im urspruenglichen Plan ist ueberholt, siehe
    // GroupSplitCard.kt. Der Einzel-URI-Pfad (der ueberwiegende Normalfall)
    // bleibt unten unveraendert.
    if (uris.size > 1) {
      val cardView = inflater.inflate(R.layout.taler_payment_card, container, false)
      bindGroupCard(cardView, uris, threadId, sender, messageBody, messageId, onAccept, messageExtrasTalerPayment)
      container.addView(cardView)
      return
    }

    for (uri in uris) {
      val record = SignalDatabase.talerPayments.getByUri(uri)
      val cardView = inflater.inflate(R.layout.taler_payment_card, container, false)
      bind(cardView, record, onAccept)
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
    bind(view, record, { _, _ -> })
    view.findViewById<View>(R.id.taler_card_actions).visibility = View.GONE
    return view
  }

  /**
   * Wie [renderStandalone], aber fuer eine Gruppen-Split-Nachricht (mehrere
   * URIs) - fuer den "Als Bild"-Weiterleiten-Snapshot einer Sammelnachricht
   * (siehe TalerForwardGate). Nutzt dieselbe [bindGroupCard]-Logik wie
   * present(), blendet die Aktionsreihe aber immer aus, aus demselben Grund
   * wie [renderStandalone]: ein statisches PNG kann Annehmen/Ablehnen nicht
   * ausfuehren.
   */
  fun renderStandaloneGroup(
    context: Context,
    uris: List<String>,
    threadId: Long,
    sender: Recipient,
    messageBody: String,
    messageId: Long,
    messageExtrasTalerPayment: TalerPaymentExtra? = null,
  ): View {
    val view = LayoutInflater.from(context).inflate(R.layout.taler_payment_card, null, false)
    bindGroupCard(view, uris, threadId, sender, messageBody, messageId, { _, _ -> }, messageExtrasTalerPayment)
    view.findViewById<View>(R.id.taler_card_actions).visibility = View.GONE
    return view
  }

  private fun bind(
    view: View,
    record: TalerPaymentRecord?,
    onAccept: (uri: String, threadId: Long) -> Unit,
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
    // Aktionen: nur noch Annehmen bleibt direkt auf der Karte - Ablehnen/
    // Abbrechen/Aktualisieren/Rueckerstatten leben jetzt im Long-Press-Menue
    // (TalerMenuState.kt/TalerMenuActions.kt), dieselbe Gating-Bedingung wie
    // hier (TalerCardActionGate) wird dort wiederverwendet, damit beide Stellen
    // nicht auseinanderlaufen koennen.
    // ========================================================================
    val actionsRow = view.findViewById<View>(R.id.taler_card_actions)
    val acceptButton = view.findViewById<Button>(R.id.taler_card_accept)

    val showAccept = TalerCardActionGate.showAccept(record)

    actionsRow.visibility = if (showAccept) View.VISIBLE else View.GONE
    acceptButton.visibility = if (showAccept) View.VISIBLE else View.GONE

    if (showAccept && record != null) {
      acceptButton.setOnClickListener { onAccept(record.uri, record.threadId) }
    }
  }

  /**
   * Sammelkarte fuer eine Gruppen-Split-Nachricht (mehrere URIs, siehe
   * GroupSplitCard.kt fuer die reine Entscheidungslogik dahinter). Teilt sich
   * bewusst Betrag/Waehrung/Zusammenfassung/Exchange-Chip/Art-Label mit
   * bind() (dieselben Felder, ein einzelner repraesentativer Datensatz reicht
   * dafuer, da alle Anteile derselben Nachricht dieselben Metadaten tragen) -
   * dupliziert aber NICHT dessen Status-/Aktionen-Logik, die fuer eine
   * einzelne URI gedacht ist und hier keinen Sinn ergibt.
   */
  private fun bindGroupCard(
    view: View,
    uris: List<String>,
    threadId: Long,
    sender: Recipient,
    messageBody: String,
    messageId: Long,
    onAccept: (uri: String, threadId: Long) -> Unit,
    messageExtrasTalerPayment: TalerPaymentExtra? = null,
  ) {
    val context = view.context

    // Alle Anteile derselben Sammelnachricht teilen Betrag/Waehrung/Zweck/
    // Exchange - der erste lokal bereits bekannte Datensatz reicht als
    // Quelle dafuer; ist noch keiner bekannt (frisch eingetroffene Nachricht,
    // Polling noch nicht durchgelaufen), zeigt die Karte denselben
    // "wird geprueft"-Zustand wie die Einzel-Karte bei unbekanntem record.
    val record = uris.firstNotNullOfOrNull { SignalDatabase.talerPayments.getByUri(it) }
    val statuses = uris.map { SignalDatabase.talerPayments.getByUri(it)?.status }

    // Bevorzuge die strukturierten Daten aus DataMessage.talerPayment (Feld
    // 9000, ueber TalerPaymentMessageTable persistiert) - `body` ist bei neu
    // gesendeten Nachrichten kein JSON mehr. parsePaymentDataOrNull bleibt
    // als Fallback fuer aeltere, vor der Umstellung gesendete Nachrichten.
    // Fuer die eigene ausgehende Nachricht des Absenders traegt weder die
    // TalerPaymentMessageTable noch der Body diese Daten: trackStructuredPayment
    // wird nur beim Verarbeiten einer EINGEHENDEN Nachricht bzw. eines
    // Sync-Transcripts aufgerufen (DataMessageProcessor/SyncMessageProcessor),
    // nicht beim lokalen Einfuegen der eigenen Bubble. Dort liegen
    // totalAmount/includeSelf aber bereits im MessageExtras.talerPayment-Feld
    // der Nachricht selbst (MessageTable.insertMessageOutbox), das der Aufrufer
    // hier durchreicht - dritte und letzte Fallback-Quelle.
    val structuredPayment = SignalDatabase.talerPaymentMessages.getByMessageId(messageId)
    val legacyPaymentData = parsePaymentDataOrNull(messageBody)
    val totalAmount = structuredPayment?.totalAmount ?: legacyPaymentData?.totalAmount ?: messageExtrasTalerPayment?.totalAmount
    val includeSelf = structuredPayment?.includeSelf ?: legacyPaymentData?.includeSelf ?: messageExtrasTalerPayment?.includeSelf

    val myAci = SignalStore.account.requireAci().toString()
    val role = if (sender.aci.isPresent) {
      // recipientAcis kommt weder aus dem Legacy-JSON noch aus
      // TalerPaymentPayload (siehe TalerPaymentData.kt) - stattdessen lokal
      // aus der bereits bekannten Gruppenmitgliedschaft hergeleitet
      // (GroupSplitRecipients.kt), damit diese Rollenaufloesung von keinem
      // Nachrichtenformat abhaengt.
      val senderAci = sender.aci.get().toString()
      val groupRecipient = SignalDatabase.threads.getRecipientForThreadId(threadId)
      val recipientAcis = groupRecipient?.let { groupSplitRecipientAcis(it, senderAci) }
      resolveGroupCardRole(recipientAcis, senderAci, myAci)
    } else {
      // Ohne bekannte Absender-ACI laesst sich weder Ersteller- noch
      // Empfaenger-Rolle feststellen - read-only, wie ein spaeter
      // beigetretenes Gruppenmitglied ohne Anspruch auf einen Anteil.
      GroupCardRole.NotAParticipant
    }

    // ========================================================================
    // View Referenzen
    // ========================================================================
    val kindView = view.findViewById<TextView>(R.id.taler_card_kind)
    val previewBadge = view.findViewById<TextView>(R.id.taler_card_preview_badge)
    val directionView = view.findViewById<TextView>(R.id.taler_card_direction)
    val amountView = view.findViewById<TextView>(R.id.taler_card_amount)
    val currencyView = view.findViewById<TextView>(R.id.taler_card_currency)
    val partyView = view.findViewById<TextView>(R.id.taler_card_party)
    val splitNoteView = view.findViewById<TextView>(R.id.taler_card_split_note)
    val summaryView = view.findViewById<TextView>(R.id.taler_card_summary)
    val exchangeChip = view.findViewById<LinearLayout>(R.id.taler_card_exchange_chip)
    val exchangeTextView = view.findViewById<TextView>(R.id.taler_card_exchange)
    val statusLayout = view.findViewById<LinearLayout>(R.id.taler_card_status)
    val splitProgressContainer = view.findViewById<LinearLayout>(R.id.taler_card_split_progress_container)
    val splitProgressBar = view.findViewById<ProgressBar>(R.id.taler_card_split_progress_bar)
    val splitProgressText = view.findViewById<TextView>(R.id.taler_card_split_progress_text)
    val splitWarningView = view.findViewById<TextView>(R.id.taler_card_split_warning)

    // ========================================================================
    // Richtung: aus der Rolle abgeleitet statt aus einem einzelnen record
    // (der bei einer frischen Nachricht noch fehlen kann) - Ersteller sieht
    // die Karte als ausgehend, jeder andere als eingehend.
    // ========================================================================
    val isOwnPayment = role is GroupCardRole.Creator
    val directionIn = !isOwnPayment
    val arrow = if (directionIn) context.getString(R.string.TalerFork_direction_in) else context.getString(R.string.TalerFork_direction_out)
    val partyText = if (directionIn) context.getString(R.string.TalerFork_party_to_you) else context.getString(R.string.TalerFork_party_from_you)

    kindView.text = kindLabel(context, record?.uriKind)
    previewBadge.visibility = View.GONE

    // Hauptbetrag der Sammelkarte: der verifizierte GESAMTBETRAG der
    // Gruppenzahlung (Bugfix 2026-09-09: zeigte hier bisher den
    // Pro-Anteil-Betrag des zuerst gefundenen records - eine Karte pro
    // Nachricht soll aber die ganze Nachricht repraesentieren, nicht nur
    // einen einzelnen Anteil). Nur anzeigen, nachdem computeVerifiedTotal
    // (GroupSplitCard.kt) bestaetigt hat, dass Divisor * Pro-Anteil-Betrag
    // zum gelieferten totalAmount passt - sonst (aeltere Nachricht ohne
    // totalAmount/includeSelf, oder manipulierte/inkonsistente Angaben)
    // bleibt der Pro-Anteil-Betrag als einzig bekannte, gepruefte Zahl der
    // Fallback (fail-safe statt Absturz oder falscher Zahl, Regel 4,
    // PROMPT.md).
    val verifiedTotal = computeVerifiedTotal(
      totalAmount = totalAmount,
      includeSelf = includeSelf,
      uriCount = uris.size,
      perShareAmount = record?.amount
    )
    val formattedAmount = (verifiedTotal?.toPlainString() ?: record?.amount)?.replace(".", ",") ?: "0"
    directionView.text = arrow
    amountView.text = formattedAmount
    currencyView.text = getCurrencySymbol(record?.currency)

    partyView.text = partyText

    // Split Note: ergaenzt den Hauptbetrag oben um die Aufteilung (Anzahl
    // Personen, verschickte Links) - dieselbe verifiedTotal-Pruefung wie
    // oben, keine zweite Berechnung noetig.
    if (verifiedTotal != null) {
      val totalFormatted = verifiedTotal.toPlainString().replace(".", ",")
      val currencySymbol = getCurrencySymbol(record?.currency)
      splitNoteView.text = if (includeSelf == true) {
        context.getString(
          R.string.TalerFork_split_note_with_self,
          totalFormatted,
          currencySymbol,
          uris.size + 1,
          uris.size
        )
      } else {
        context.getString(
          R.string.TalerFork_split_note_without_self,
          totalFormatted,
          currencySymbol,
          uris.size
        )
      }
      splitNoteView.visibility = View.VISIBLE
    } else {
      if (totalAmount != null) {
        Log.w(TAG, "Gruppen-Split: totalAmount passt nicht zu Anteilsbetrag/URI-Anzahl - zeige keine Summe")
      }
      splitNoteView.visibility = View.GONE
    }

    summaryView.text = record?.summary
    summaryView.visible = !record?.summary.isNullOrBlank()

    val host = displayHost(record?.exchangeBaseUrl)
    if (!host.isNullOrBlank()) {
      exchangeTextView.text = host
      exchangeChip.visibility = View.VISIBLE
    } else {
      exchangeChip.visibility = View.GONE
    }

    // ========================================================================
    // Fortschrittsbalken statt Einzel-Status (Alternative laut XML-Kommentar)
    // ========================================================================
    statusLayout.visibility = View.GONE
    splitProgressContainer.visibility = View.VISIBLE
    val accepted = countAccepted(statuses)
    splitProgressBar.progress = if (uris.isEmpty()) 0 else 100 * accepted / uris.size
    splitProgressText.text = context.getString(
      R.string.TalerFork_split_progress,
      accepted,
      uris.size,
      context.getString(R.string.TalerFork_split_warning_accepted)
    )

    // ========================================================================
    // Split Warning: bewusst nicht befuellt - der einzige vorhandene Text
    // dafuer ("...trotzdem nochmal moeglich") widerspricht dem Blockieren
    // durch GroupClaimTracker unten, ein neuer String waere hier Ratewerk
    // ohne Vorgabe (siehe Bericht).
    // ========================================================================
    splitWarningView.visibility = View.GONE

    // ========================================================================
    // Aktionen: nur noch Annehmen bleibt direkt auf der Karte, gebunden an den
    // naechsten noch offenen Anteil ab dem eigenen Index - nicht an eine feste
    // eigene URI (siehe GroupSplitCard.kt-Doc: "mein Anteil" ist nur ein
    // Startpunkt). GroupClaimTracker verhindert, dass dieser Betrachter ueber
    // DIESE Karte einen zweiten Anteil annimmt, nachdem er bereits einen
    // beansprucht hat. Ablehnen/Abbrechen/Aktualisieren/Rueckerstatten leben
    // jetzt im Long-Press-Menue (TalerMenuState.kt/TalerMenuActions.kt,
    // GroupSplitCard.resolveCancelableUris/resolveRefundableUris) - das macht
    // die zuvor hier hart auf GONE gesetzten Aktionen fuer die Ersteller-Rolle
    // erstmals ueber das Menue erreichbar (Nebeneffekt der Neustrukturierung,
    // siehe Plan).
    // ========================================================================
    val actionsRow = view.findViewById<View>(R.id.taler_card_actions)
    val acceptButton = view.findViewById<Button>(R.id.taler_card_accept)

    val claimTracker = GroupClaimTracker(context)
    val alreadyClaimed = claimTracker.hasClaimedAny(uris)
    val targetUri = if (role is GroupCardRole.Recipient && !alreadyClaimed) {
      resolveTargetUri(uris, statuses, role.myIndex)
    } else {
      null
    }

    if (targetUri != null) {
      actionsRow.visibility = View.VISIBLE
      acceptButton.visibility = View.VISIBLE
      acceptButton.setOnClickListener {
        // Vor dem Callback tracken, nicht danach - onAccept startet einen
        // expliziten Deep-Link zu Talers UI und kehrt zu dieser Activity
        // nicht synchron zurueck (siehe TalerAcceptRejectActions), ein
        // Tracking "danach" wuerde nie ausgefuehrt.
        claimTracker.track(targetUri)
        onAccept(targetUri, threadId)
      }
    } else {
      actionsRow.visibility = View.GONE
      acceptButton.visibility = View.GONE
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
   * Reines Status-Label ohne Icon/Farbe/Empfaenger-Namen - fuer Stellen, die
   * nur einen kurzen, sicheren Text brauchen (Nachrichtendetails-Screen,
   * Zitat-Kurzversion), nicht die volle Karte. Nutzt dieselben
   * TalerFork_status_compact_*-Strings wie [getCompactStatus]s Standardfall,
   * damit der Wortlaut an allen Stellen konsistent bleibt.
   */
  fun plainStatusLabel(context: Context, status: TalerPaymentStatus): String {
    val textRes = when (status) {
      TalerPaymentStatus.OFFEN -> R.string.TalerFork_status_compact_open
      TalerPaymentStatus.ANGENOMMEN -> R.string.TalerFork_status_compact_accepted
      TalerPaymentStatus.LOKAL_ABGELEHNT -> R.string.TalerFork_status_compact_declined
      TalerPaymentStatus.LOKAL_ABGEBROCHEN -> R.string.TalerFork_status_compact_cancelled
      TalerPaymentStatus.ABGELAUFEN -> R.string.TalerFork_status_compact_expired
      TalerPaymentStatus.UNBEKANNT_OFFLINE -> R.string.TalerFork_status_compact_checking
      TalerPaymentStatus.UNGUELTIG -> R.string.TalerFork_status_compact_invalid
    }
    return context.getString(textRes)
  }

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
      TalerPaymentStatus.LOKAL_ABGEBROCHEN -> R.string.TalerFork_status_icon_cancelled
      TalerPaymentStatus.ABGELAUFEN -> R.string.TalerFork_status_icon_expired
      TalerPaymentStatus.UNBEKANNT_OFFLINE -> R.string.TalerFork_status_icon_checking
      TalerPaymentStatus.UNGUELTIG -> R.string.TalerFork_status_icon_invalid
    }
    
    val textRes = when (status) {
      TalerPaymentStatus.OFFEN -> R.string.TalerFork_status_compact_open
      TalerPaymentStatus.ANGENOMMEN -> R.string.TalerFork_status_compact_accepted
      TalerPaymentStatus.LOKAL_ABGELEHNT -> R.string.TalerFork_status_compact_declined
      TalerPaymentStatus.LOKAL_ABGEBROCHEN -> R.string.TalerFork_status_compact_cancelled
      TalerPaymentStatus.ABGELAUFEN -> R.string.TalerFork_status_compact_expired
      TalerPaymentStatus.UNBEKANNT_OFFLINE -> R.string.TalerFork_status_compact_checking
      TalerPaymentStatus.UNGUELTIG -> R.string.TalerFork_status_compact_invalid
    }

    // Farben basierend auf Status-Kategorie
    val (iconColorRes, textColorRes) = when (status) {
      TalerPaymentStatus.ANGENOMMEN -> Pair(R.color.taler_status_ok_light, R.color.taler_status_ok_light)
      TalerPaymentStatus.LOKAL_ABGELEHNT, TalerPaymentStatus.LOKAL_ABGEBROCHEN, TalerPaymentStatus.ABGELAUFEN, TalerPaymentStatus.UNGUELTIG ->
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
