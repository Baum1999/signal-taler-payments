package org.thoughtcrime.securesms.messagedetails

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.thoughtcrime.securesms.R

/**
 * Eine Zeile im Nachrichtendetails-Screen fuer eine Taler-URI: gekuerzte URI,
 * Status (ueber TalerPaymentCardPresenter.plainStatusLabel), Betrag+Waehrung
 * (falls schon bekannt), und wann DIESES Geraet die URI lokal erkannt hat
 * (nicht: wann Taler die Zahlung erstellt/abgeschlossen hat - dieses
 * Zeitfeld existiert lokal nicht, siehe TalerPaymentRecord).
 */
data class TalerPaymentDetailsEntry(
  val shortUri: String,
  val statusLabel: String,
  val amountLabel: String?,
  val detectedAtLabel: String?,
)

/**
 * Eine Nachricht kann mehrere URIs tragen (Gruppen-Split) - [totalLabel] ist
 * nur gesetzt, wenn GroupSplitCard.computeVerifiedTotal den von Taler
 * mitgelieferten Gesamtbetrag gegen die Pro-Anteil-Betraege verifizieren
 * konnte (dieselbe Fail-Safe-Regel wie auf der Sammelkarte selbst).
 */
data class TalerPaymentDetailsRow(
  val entries: List<TalerPaymentDetailsEntry>,
  val totalLabel: String?,
  val groupPrivacyNoteLabel: String? = null,
)

class TalerPaymentDetailsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

  private val totalView: TextView = itemView.findViewById(R.id.taler_details_total)
  private val entriesContainer: LinearLayout = itemView.findViewById(R.id.taler_details_entries)

  fun bind(row: TalerPaymentDetailsRow) {
    val context = itemView.context

    if (row.totalLabel != null) {
      totalView.text = context.getString(R.string.TalerFork_details_total_label) + ": " + row.totalLabel
      totalView.visibility = View.VISIBLE
    } else {
      totalView.visibility = View.GONE
    }

    entriesContainer.removeAllViews()
    val verticalPaddingPx = (8 * context.resources.displayMetrics.density).toInt()

    if (row.groupPrivacyNoteLabel != null) {
      val noteView = TextView(context)
      noteView.text = row.groupPrivacyNoteLabel
      noteView.textSize = 12f
      noteView.setPadding(0, 0, 0, verticalPaddingPx)
      entriesContainer.addView(noteView)
    }

    row.entries.forEach { entry ->
      val line = StringBuilder()
        .append(context.getString(R.string.TalerFork_details_uri_label)).append(": ").append(entry.shortUri).append('\n')
        .append(entry.statusLabel)

      if (entry.amountLabel != null) {
        line.append(" · ").append(entry.amountLabel)
      }
      if (entry.detectedAtLabel != null) {
        line.append('\n').append(context.getString(R.string.TalerFork_details_detected_at)).append(": ").append(entry.detectedAtLabel)
      }

      val textView = TextView(context)
      textView.text = line.toString()
      textView.textSize = 14f
      textView.setPadding(0, verticalPaddingPx, 0, verticalPaddingPx)
      entriesContainer.addView(textView)
    }
  }
}
