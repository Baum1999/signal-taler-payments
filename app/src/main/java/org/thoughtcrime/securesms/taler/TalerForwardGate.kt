package org.thoughtcrime.securesms.taler

import android.app.AlertDialog
import android.content.Context
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragmentArgs
import org.thoughtcrime.securesms.database.SignalDatabase

/**
 * Meilenstein 6: Interstitial fuer "Weiterleiten" einer Auswahl, die genau
 * eine Taler-URI enthaelt - ausgelagert aus ConversationFragment (gleiches
 * H4-Muster wie TalerPaymentCardPresenter), damit der Eingriff in diese
 * Upstream-Datei auf den Aufruf hier beschraenkt bleibt.
 *
 * Nur eine Auswahl aus genau EINER Nachricht mit genau EINER Taler-URI loest
 * das Interstitial aus - jede andere Auswahl (mehrere Nachrichten, keine oder
 * mehrere URIs, Nicht-Taler-Inhalt) wird unveraendert wie bisher
 * weitergeleitet. Bewusste, einfache Vorgabe fuer diesen Meilenstein, kein
 * Versehen - siehe Meilenstein-6-Bericht.
 *
 * Weiterleiten "als Zahlung" traegt die rohe URI unveraendert weiter (das ist
 * bereits das heutige Standardverhalten von buildMultiShareArgs, siehe
 * Kommentar an onForwardAsPayment unten) - nur bei OFFEN erlaubt und mit
 * sichtbarer Warnung, weil damit ein Inhaberpapier weitergegeben wird
 * (PROMPT.md Meilenstein 6). Weiterleiten "als Text" ersetzt die URI durch
 * einen Platzhalter, damit die weitergeleitete Kopie keine lebendige Karte
 * mehr rendert.
 */
object TalerForwardGate {

  /**
   * Liefert die Taler-URI, wenn [messageParts] genau eine Nachricht mit genau
   * einer Taler-URI im Body referenziert - sonst null (dann greift kein
   * Interstitial, normales Weiterleiten).
   */
  fun detectSingleTalerUri(messageParts: Set<MultiselectPart>): String? {
    val records = messageParts.map { it.conversationMessage.messageRecord }.distinct()
    if (records.size != 1) return null
    val uris = TalerUriDetector.findUris(records.first().body)
    return uris.singleOrNull()
  }

  /**
   * [onForwardAsPayment] laesst den bestehenden Weiterleiten-Flow
   * unveraendert (die rohe URI bleibt im Text - "als Zahlung" ist bereits das
   * Standardverhalten, siehe Klassendoc), nur erreichbar bei OFFEN. Ist die
   * Zahlung nicht mehr offen, bietet der Dialog nur noch "Als Text" an - eine
   * bereits entschiedene/abgelaufene URI als Zahlung weiterzugeben waere
   * irrefuehrend.
   */
  fun showChoiceDialog(
    context: Context,
    uri: String,
    onForwardAsPayment: () -> Unit,
    onForwardAsText: () -> Unit,
  ) {
    val isOpen = SignalDatabase.talerPayments.getByUri(uri)?.status == TalerPaymentStatus.OFFEN

    val builder = AlertDialog.Builder(context)
      .setTitle(R.string.TalerFork_forward_choice_title)
      .setNegativeButton(R.string.TalerFork_send_dialog_cancel, null)

    if (isOpen) {
      builder
        .setMessage(R.string.TalerFork_forward_payment_warning)
        .setPositiveButton(R.string.TalerFork_forward_as_payment) { _, _ -> onForwardAsPayment() }
        .setNeutralButton(R.string.TalerFork_forward_as_text) { _, _ -> onForwardAsText() }
    } else {
      builder
        .setMessage(R.string.TalerFork_forward_as_payment_disabled_reason)
        .setPositiveButton(R.string.TalerFork_forward_as_text) { _, _ -> onForwardAsText() }
    }
    builder.show()
  }

  /**
   * Ersetzt in jedem [MultiShareArgs]-Eintrag, dessen draftText eine
   * Taler-URI enthaelt, den kompletten draftText durch einen Platzhalter -
   * ueber buildUpon()/withDraftText() (bestehender Copy-Mechanismus von
   * MultiShareArgs), kein neuer Parsing-/Konstruktionspfad. Andere Eintraege
   * (z. B. bei einer Mehrfachauswahl, die hier zwar nicht das Interstitial
   * ausloest, aber theoretisch trotzdem durchlaufen koennte) bleiben
   * unveraendert.
   */
  fun redactPaymentUri(context: Context, args: MultiselectForwardFragmentArgs): MultiselectForwardFragmentArgs {
    val placeholder = context.getString(R.string.TalerFork_forward_redacted_placeholder)
    val redacted = args.multiShareArgs.map { share ->
      val text = share.draftText
      if (!text.isNullOrEmpty() && TalerUriDetector.findUris(text).isNotEmpty()) {
        share.buildUpon().withDraftText(placeholder).build()
      } else {
        share
      }
    }
    return args.copy(multiShareArgs = redacted)
  }
}
