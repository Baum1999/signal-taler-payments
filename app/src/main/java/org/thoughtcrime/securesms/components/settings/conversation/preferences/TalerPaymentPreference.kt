package org.thoughtcrime.securesms.components.settings.conversation.preferences

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.PreferenceModel
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.TalerPaymentRecord
import org.thoughtcrime.securesms.taler.TalerPaymentStatus
import org.thoughtcrime.securesms.util.adapter.mapping.LayoutFactory
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter
import org.thoughtcrime.securesms.util.adapter.mapping.MappingViewHolder
import java.math.BigDecimal

/**
 * Zeigt Taler-Zahlungsinformationen in den Chat-Einstellungen an -
 * ausschliesslich aus Signals eigenen Daten (TalerPaymentTable), ohne
 * Aufrufe an die Taler-App (kein getTransactionsV2, kein getBalances).
 * Nur sichtbar, wenn die Taler-App verbunden ist.
 */
object TalerPaymentPreference {

  data class TalerPaymentGroup(
    val currency: String,
    val exchangeBaseUrl: String,
    val records: List<TalerPaymentRecord>,
  ) {
    val displayAmount: String
      get() {
        // Fix: vorher wurden ALLE Betraege dieser Gruppe blind addiert, egal
        // ob eingehend oder ausgehend und egal ob die Zahlung ueberhaupt
        // abgeschlossen war - ein Senden UND ein Empfangen von je 5 KUDOS
        // zeigte "10 KUDOS" statt getrennter Ein-/Ausgangsbetraege, und auch
        // noch offene/abgelehnte Vorgaenge zaehlten mit, obwohl dabei nie
        // Geld bewegt wurde. Nur ANGENOMMEN (tatsaechlich abgeschlossen)
        // zaehlt, getrennt nach isOwnPayment (ausgehend) vs. eingehend.
        val completed = records.filter { it.status == TalerPaymentStatus.ANGENOMMEN }
        val inflow = completed.filter { !it.isOwnPayment }.sumAmounts()
        val outflow = completed.filter { it.isOwnPayment }.sumAmounts()
        return when {
          inflow > BigDecimal.ZERO && outflow > BigDecimal.ZERO -> "+$inflow / -$outflow $currency"
          outflow > BigDecimal.ZERO -> "-$outflow $currency"
          inflow > BigDecimal.ZERO -> "+$inflow $currency"
          else -> "0 $currency"
        }
      }

    private fun List<TalerPaymentRecord>.sumAmounts(): BigDecimal =
      sumOf { record -> record.amount?.toBigDecimalOrNull() ?: BigDecimal.ZERO }

    val displayStatus: String?
      get() {
        val uniqueStatuses = records.map { it.status }.toSet()
        return if (uniqueStatuses.size == 1) {
          when (uniqueStatuses.first()) {
            TalerPaymentStatus.OFFEN -> "Open"
            TalerPaymentStatus.ANGENOMMEN -> "Accepted"
            TalerPaymentStatus.LOKAL_ABGELEHNT -> "Declined"
            TalerPaymentStatus.ABGELAUFEN -> "Expired"
            TalerPaymentStatus.UNBEKANNT_OFFLINE -> "Checking..."
            TalerPaymentStatus.UNGUELTIG -> "Invalid"
            TalerPaymentStatus.TALER_NICHT_VERBUNDEN -> "Not connected"
            TalerPaymentStatus.NICHT_INSTALLIERT -> "Not installed"
            TalerPaymentStatus.NICHT_VERTRAUENSWUERDIG -> "Untrusted"
          }
        } else {
          null
        }
      }
  }

  fun register(adapter: MappingAdapter) {
    adapter.registerFactory(Model::class.java, LayoutFactory(::ViewHolder, R.layout.taler_payment_preference_item))
  }

  class Model(
    val threadId: Long,
  ) : PreferenceModel<Model>() {

    val paymentGroups: List<TalerPaymentGroup>
      get() {
        val records = SignalDatabase.talerPayments.getForThread(threadId)
        // Gruppe nach Waehrung und Exchange
        return records
          .filter { it.amount != null && it.currency != null && it.exchangeBaseUrl != null }
          .groupBy { Pair(it.currency!!, it.exchangeBaseUrl!!) }
          .map { (key, groupRecords) ->
            TalerPaymentGroup(
              currency = key.first,
              exchangeBaseUrl = key.second,
              records = groupRecords,
            )
          }
          .sortedBy { it.currency }
      }

    val isVisible: Boolean
      get() = paymentGroups.isNotEmpty()

    override fun areContentsTheSame(newItem: Model): Boolean {
      return super.areContentsTheSame(newItem) &&
        threadId == newItem.threadId &&
        paymentGroups == newItem.paymentGroups
    }

    override fun areItemsTheSame(newItem: Model): Boolean {
      return newItem.threadId == threadId
    }
  }

  class ViewHolder(itemView: View) : MappingViewHolder<Model>(itemView) {
    private val container: LinearLayout = itemView.findViewById(R.id.taler_payment_container)
    private val inflater: LayoutInflater by lazy { LayoutInflater.from(itemView.context) }

    override fun bind(model: Model) {
      // Remove all existing views
      container.removeAllViews()
      
      // Inflate a new row for each payment group
      for (group in model.paymentGroups) {
        val rowView = inflater.inflate(R.layout.taler_payment_row, container, false)
        val currencyText: TextView = rowView.findViewById(R.id.taler_payment_currency)
        val exchangeText: TextView = rowView.findViewById(R.id.taler_payment_exchange)
        val amountText: TextView = rowView.findViewById(R.id.taler_payment_amount)
        val statusText: TextView = rowView.findViewById(R.id.taler_payment_status)
        
        currencyText.text = group.currency
        exchangeText.text = group.exchangeBaseUrl
        amountText.text = group.displayAmount
        
        val status = group.displayStatus
        statusText.isVisible = status != null
        statusText.text = status
        
        container.addView(rowView)
      }
    }
  }
}
