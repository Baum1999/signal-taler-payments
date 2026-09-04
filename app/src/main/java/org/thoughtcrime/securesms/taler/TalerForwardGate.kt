package org.thoughtcrime.securesms.taler

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.core.view.drawToBitmap
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragmentArgs
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.io.ByteArrayOutputStream

/**
 * Meilenstein 6: Interstitial fuer "Weiterleiten" einer Auswahl, die aus
 * genau einer Nachricht mit mindestens einer Taler-URI besteht - ausgelagert
 * aus ConversationFragment (gleiches H4-Muster wie TalerPaymentCardPresenter),
 * damit der Eingriff in diese Upstream-Datei auf den Aufruf hier beschraenkt
 * bleibt.
 *
 * Nur eine Auswahl aus genau EINER Nachricht mit mindestens EINER Taler-URI
 * loest das Interstitial aus - jede andere Auswahl (mehrere Nachrichten,
 * keine URI, Nicht-Taler-Inhalt) wird unveraendert wie bisher weitergeleitet.
 * Bewusste, einfache Vorgabe fuer diesen Meilenstein, kein Versehen - siehe
 * Meilenstein-6-Bericht. Eine Nachricht kann dabei mehrere URIs tragen: bei
 * einem Gruppen-Split traegt der Body eine URI pro Empfaenger-Anteil (siehe
 * TalerPaymentData.kt) - die Wahl "als Text"/"als Bild" gilt dann fuer die
 * ganze Sammelnachricht, nicht pro Anteil.
 *
 * Der Dialog bietet immer zwei Wege an, unabhaengig vom Zahlungsstatus:
 * Weiterleiten "als Text" traegt die rohe(n) URI(s) unveraendert weiter (das
 * ist bereits das heutige Standardverhalten von buildMultiShareArgs) - mit
 * sichtbarer Warnung, wenn mindestens einer der Anteile noch OFFEN ist, weil
 * damit ein Inhaberpapier weitergegeben wird (wer den Link zuerst oeffnet,
 * kann DIESEN Anteil einloesen); sind alle Anteile bereits entschieden oder
 * abgelaufen, besteht das Risiko nicht mehr, daher keine Warnung, aber
 * weiterhin erlaubt. Weiterleiten "als Bild" rendert stattdessen einen
 * Schnappschuss der aktuellen Zahlungskarte (Einzel-URI) bzw. Sammelkarte
 * (Gruppen-Split, mehrere URIs) als PNG - die weitergeleitete Kopie enthaelt
 * keine URI mehr und rendert beim Empfaenger keine lebendige Karte.
 */
object TalerForwardGate {

  private const val SNAPSHOT_WIDTH_PX = 1080

  /**
   * Eine fuer das Interstitial in Frage kommende Auswahl: genau eine
   * Nachricht ([record]) mit mindestens einer Taler-URI ([uris]) im Body.
   */
  data class TalerForwardCandidate(val record: MessageRecord, val uris: List<String>)

  /**
   * Liefert den Interstitial-Kandidaten, wenn [messageParts] genau eine
   * Nachricht mit mindestens einer Taler-URI im Body referenziert - sonst
   * null (dann greift kein Interstitial, normales Weiterleiten).
   */
  fun detectTalerForward(messageParts: Set<MultiselectPart>): TalerForwardCandidate? {
    val record = messageParts.map { it.conversationMessage.messageRecord }.distinct().singleOrNull() ?: return null
    val uris = urisFromMessageBody(record.body)
    return if (uris.isNotEmpty()) TalerForwardCandidate(record, uris) else null
  }

  /**
   * Zeigt die Wahl zwischen "Als Text" (rohe URI(s), [onForwardAsText]) und
   * "Als Bild" (Schnappschuss, [onForwardAsImage]) - beide immer verfuegbar.
   * Die Claim-Warnung erscheint, sobald mindestens einer der [uris] noch
   * OFFEN ist (siehe [anyOpen]).
   */
  fun showChoiceDialog(
    context: Context,
    uris: List<String>,
    onForwardAsText: () -> Unit,
    onForwardAsImage: () -> Unit,
  ) {
    val statuses = uris.map { SignalDatabase.talerPayments.getByUri(it)?.status }
    val isOpen = anyOpen(statuses)

    val builder = AlertDialog.Builder(context)
      .setTitle(R.string.TalerFork_forward_choice_title)
      .setNegativeButton(R.string.TalerFork_send_dialog_cancel, null)
      .setPositiveButton(R.string.TalerFork_forward_as_text) { _, _ -> onForwardAsText() }
      .setNeutralButton(R.string.TalerFork_forward_as_image) { _, _ -> onForwardAsImage() }

    if (isOpen) {
      builder.setMessage(R.string.TalerFork_forward_payment_warning)
    }
    builder.show()
  }

  /**
   * Rendert die Zahlungskarte zu [candidate] offscreen zu einem PNG und
   * ersetzt in jedem [MultiShareArgs]-Eintrag, dessen draftText eine
   * Taler-URI enthaelt, den draftText durch das gerenderte Bild (ueber
   * buildUpon()/withDataUri()/withDataType(), bestehender Copy-Mechanismus
   * von MultiShareArgs - analog zum Weg, den
   * MultiselectForwardFragmentArgs.create() fuer einzelne Medien-URIs bereits
   * nutzt). Bei genau einer URI wird die Einzel-Zahlungskarte gerendert
   * ([TalerPaymentCardPresenter.renderStandalone]), bei einem Gruppen-Split
   * (mehrere URIs) die Sammelkarte
   * ([TalerPaymentCardPresenter.renderStandaloneGroup]) - dieselbe
   * Unterscheidung wie in present()/TalerPaymentCardPresenter. [onReady] wird
   * auf dem Main-Dispatcher mit den so veraenderten Args aufgerufen, sobald
   * das Bild fertig ist.
   */
  fun attachPaymentSnapshot(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    candidate: TalerForwardCandidate,
    args: MultiselectForwardFragmentArgs,
    onReady: (MultiselectForwardFragmentArgs) -> Unit,
  ) {
    lifecycleOwner.lifecycleScope.launch {
      val pngUri = withContext(Dispatchers.Default) {
        val view = if (candidate.uris.size > 1) {
          TalerPaymentCardPresenter.renderStandaloneGroup(
            context = context,
            uris = candidate.uris,
            threadId = candidate.record.threadId,
            sender = candidate.record.fromRecipient,
            messageBody = candidate.record.body,
          )
        } else {
          val record = SignalDatabase.talerPayments.getByUri(candidate.uris.single())
          TalerPaymentCardPresenter.renderStandalone(context, record)
        }
        view.measure(
          View.MeasureSpec.makeMeasureSpec(SNAPSHOT_WIDTH_PX, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val bitmap = view.drawToBitmap()
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 0, outputStream)

        AppDependencies.blobs
          .forData(outputStream.toByteArray())
          .withMimeType("image/png")
          .withFileName("Taler-Zahlung.png")
          .createForSingleSessionInMemory()
      }

      val withSnapshot = args.multiShareArgs.map { share ->
        val text = share.draftText
        if (!text.isNullOrEmpty() && urisFromMessageBody(text).isNotEmpty()) {
          share.buildUpon().withDraftText(null).withDataUri(pngUri).withDataType("image/png").build()
        } else {
          share
        }
      }
      onReady(args.copy(multiShareArgs = withSnapshot))
    }
  }
}
