/*
 * PaymentHistoryPreference.kt - Preference für Zahlungshistorie in Chat-Einstellungen
 *
 * Dies ist die Implementierung der Zahlungshistorie für Signal-Taler-Integration.
 */

package org.thoughtcrime.securesms.components.settings.conversation.preferences

import android.content.Context
import android.view.View
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.PreferenceModel
import org.thoughtcrime.securesms.components.settings.PreferenceViewHolder
import org.thoughtcrime.securesms.payments.history.PaymentDirection
import org.thoughtcrime.securesms.payments.history.PaymentHistoryItem
import org.thoughtcrime.securesms.util.adapter.mapping.LayoutFactory
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Zeigt die Zahlungshistorie für einen Chat/Thread in den Einstellungen an.
 * Kann durch Klick geöffnet werden, um die vollständige History anzuzeigen.
 */
object PaymentHistoryPreference {

    fun register(adapter: MappingAdapter) {
        adapter.registerFactory(PaymentHistoryModel::class.java, LayoutFactory(::ViewHolder, R.layout.dsl_preference_item))
    }

    data class PaymentHistoryModel(
        val chatId: Long,
        val paymentCount: Int,
        val latestPayment: PaymentHistoryItem?,
        val totalInflow: String,
        val totalOutflow: String
    ) : PreferenceModel<PaymentHistoryModel>() {

        override fun areContentsTheSame(newItem: PaymentHistoryModel): Boolean {
            return this == newItem
        }

        override fun areItemsTheSame(newItem: PaymentHistoryModel): Boolean {
            return chatId == newItem.chatId
        }

        /**
         * Gibt den anzuzeigenden Text zurück.
         */
        fun getDisplayText(context: Context): String {
            return if (paymentCount == 0) {
                context.getString(R.string.payment_history_no_payments)
            } else if (paymentCount == 1) {
                latestPayment?.let {
                    val direction = if (it.direction == PaymentDirection.INFLOW) "erhalten" else "gesendet"
                    val amount = "${it.amount} ${it.currency}"
                    "1 Zahlung: $amount $direction"
                } ?: context.getString(R.string.payment_history_one_payment)
            } else {
                context.getString(R.string.payment_history_count, paymentCount)
            }
        }

        /**
         * Gibt den Untertitel zurück.
         */
        fun getSubtitle(context: Context): String? {
            return if (paymentCount > 0) {
                val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                latestPayment?.timestamp?.let { timestamp ->
                    "Letzte Zahlung: ${dateFormat.format(timestamp)}"
                }
            } else {
                null
            }
        }
    }

    private class ViewHolder(itemView: View) : PreferenceViewHolder<PaymentHistoryModel>(itemView) {
        override fun bind(model: PaymentHistoryModel) {
            super.bind(model)
            // super.bind() versteckt titleView, weil model.title (die
            // generische PreferenceModel-Eigenschaft) hier ungenutzt bleibt -
            // dieses Model berechnet Titel/Untertitel stattdessen dynamisch
            // aus den Zahlungsdaten (getDisplayText/getSubtitle).
            titleView.text = model.getDisplayText(context)
            titleView.visibility = View.VISIBLE

            val subtitle = model.getSubtitle(context)
            summaryView.text = subtitle
            summaryView.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }
}
