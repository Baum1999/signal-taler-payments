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
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.io.ByteArrayOutputStream

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
 * Der Dialog bietet immer zwei Wege an, unabhaengig vom Zahlungsstatus:
 * Weiterleiten "als Text" traegt die rohe URI unveraendert weiter (das ist
 * bereits das heutige Standardverhalten von buildMultiShareArgs) - bei OFFEN
 * mit sichtbarer Warnung, weil damit ein Inhaberpapier weitergegeben wird
 * (wer den Link zuerst oeffnet, kann die Zahlung einloesen); bei jedem
 * anderen Status ist das Risiko nicht mehr gegeben, daher keine Warnung, aber
 * weiterhin erlaubt - eine bereits entschiedene/abgelaufene URI unveraendert
 * weiterzugeben ist unproblematisch, nur das *Einloesen* einer offenen
 * Zahlung ist der Risikofall. Weiterleiten "als Bild" rendert stattdessen
 * einen Schnappschuss der aktuellen Zahlungskarte (Betrag/Status/
 * Zusammenfassung zum Zeitpunkt des Weiterleitens) als PNG - die
 * weitergeleitete Kopie enthaelt keine URI mehr und rendert beim Empfaenger
 * keine lebendige Karte.
 */
object TalerForwardGate {

  private const val SNAPSHOT_WIDTH_PX = 1080

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
   * Zeigt die Wahl zwischen "Als Text" (rohe URI, [onForwardAsText]) und
   * "Als Bild" (Schnappschuss, [onForwardAsImage]) - beide immer verfuegbar.
   * Die Claim-Warnung erscheint nur, wenn die Zahlung noch OFFEN ist.
   */
  fun showChoiceDialog(
    context: Context,
    uri: String,
    onForwardAsText: () -> Unit,
    onForwardAsImage: () -> Unit,
  ) {
    val isOpen = SignalDatabase.talerPayments.getByUri(uri)?.status == TalerPaymentStatus.OFFEN

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
   * Rendert die Zahlungskarte zu [uri] offscreen zu einem PNG und ersetzt in
   * jedem [MultiShareArgs]-Eintrag, dessen draftText diese URI enthaelt, den
   * draftText durch das gerenderte Bild (ueber buildUpon()/withDataUri()/
   * withDataType(), bestehender Copy-Mechanismus von MultiShareArgs - analog
   * zum Weg, den MultiselectForwardFragmentArgs.create() fuer einzelne
   * Medien-URIs bereits nutzt). [onReady] wird auf dem Main-Dispatcher mit
   * den so veraenderten Args aufgerufen, sobald das Bild fertig ist.
   */
  fun attachPaymentSnapshot(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    uri: String,
    args: MultiselectForwardFragmentArgs,
    onReady: (MultiselectForwardFragmentArgs) -> Unit,
  ) {
    lifecycleOwner.lifecycleScope.launch {
      val record = SignalDatabase.talerPayments.getByUri(uri)
      val pngUri = withContext(Dispatchers.Default) {
        val view = TalerPaymentCardPresenter.renderStandalone(context, record)
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
        if (!text.isNullOrEmpty() && TalerUriDetector.findUris(text).isNotEmpty()) {
          share.buildUpon().withDraftText(null).withDataUri(pngUri).withDataType("image/png").build()
        } else {
          share
        }
      }
      onReady(args.copy(multiShareArgs = withSnapshot))
    }
  }
}
