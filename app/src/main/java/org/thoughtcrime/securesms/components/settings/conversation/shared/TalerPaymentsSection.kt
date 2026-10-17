package org.thoughtcrime.securesms.components.settings.conversation.shared

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Texts
import org.signal.core.util.concurrent.SignalDispatchers
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.TalerPaymentRecord
import org.thoughtcrime.securesms.payments.history.PaymentDirection
import org.thoughtcrime.securesms.payments.history.PaymentHistoryItem
import org.thoughtcrime.securesms.taler.TalerPaymentStatus
import org.thoughtcrime.securesms.util.DateUtils
import java.math.BigDecimal
import java.util.Locale

/**
 * Taler payments of one conversation, built only from Signal's own tables (no calls into the Taler app).
 *
 * @param groups Completed payment totals per currency and exchange.
 * @param history Entries of the payment history for this chat, newest first.
 */
data class TalerPaymentsSummary(
  val groups: List<TalerPaymentGroup> = emptyList(),
  val history: List<PaymentHistoryItem> = emptyList()
) {
  val isEmpty: Boolean get() = groups.isEmpty() && history.isEmpty()

  companion object {
    suspend fun load(threadId: Long): TalerPaymentsSummary {
      if (threadId <= 0) {
        return TalerPaymentsSummary()
      }

      return withContext(SignalDispatchers.IO) {
        val groups = SignalDatabase.talerPayments.getForThread(threadId)
          .filter { it.amount != null && it.currency != null && it.exchangeBaseUrl != null }
          .groupBy { it.currency!! to it.exchangeBaseUrl!! }
          .map { (key, records) -> TalerPaymentGroup(currency = key.first, exchangeBaseUrl = key.second, records = records) }
          .sortedBy { it.currency }

        TalerPaymentsSummary(
          groups = groups,
          history = SignalDatabase.paymentHistory.getForChat(threadId)
        )
      }
    }
  }
}

/** All Taler payments of a conversation in one currency at one exchange. */
data class TalerPaymentGroup(
  val currency: String,
  val exchangeBaseUrl: String,
  val records: List<TalerPaymentRecord>
) {
  /**
   * Only completed (accepted) payments move money, so only those count, split into incoming and outgoing instead of
   * blindly adding everything up.
   */
  val displayAmount: String
    get() {
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

  /** The common status of all payments in this group, or null if they differ. */
  val commonStatus: TalerPaymentStatus?
    get() = records.map { it.status }.toSet().singleOrNull()

  private fun List<TalerPaymentRecord>.sumAmounts(): BigDecimal =
    sumOf { record -> record.amount?.toBigDecimalOrNull() ?: BigDecimal.ZERO }
}

/**
 * The Taler payments of this chat and its payment history. Only shown when the chat has any.
 */
fun LazyListScope.talerPaymentsSection(
  summary: TalerPaymentsSummary,
  onViewPaymentHistoryClick: () -> Unit
) {
  if (summary.isEmpty) {
    return
  }

  if (summary.groups.isNotEmpty()) {
    item { Dividers.Default() }
    item { Texts.SectionHeader(text = stringResource(R.string.TalerFork_payments)) }

    items(
      items = summary.groups,
      key = { "taler-${it.currency}-${it.exchangeBaseUrl}" }
    ) { group ->
      val status = group.commonStatus?.let { stringResource(it.labelRes()) }
      Rows.TextRow(
        text = "${group.currency} · ${group.displayAmount}",
        label = listOfNotNull(group.exchangeBaseUrl, status).joinToString("\n")
      )
    }
  }

  if (summary.history.isNotEmpty()) {
    item { Dividers.Default() }
    item { Texts.SectionHeader(text = stringResource(R.string.payment_history)) }

    item {
      val context = LocalContext.current
      val latest = summary.history.first()
      Rows.TextRow(
        text = if (summary.history.size == 1) {
          val amount = "${latest.amount} ${latest.currency}"
          if (latest.direction == PaymentDirection.INFLOW) {
            stringResource(R.string.payment_history_one_payment_received, amount)
          } else {
            stringResource(R.string.payment_history_one_payment_sent, amount)
          }
        } else {
          pluralStringResource(R.plurals.payment_history_count, summary.history.size, summary.history.size)
        },
        label = stringResource(
          R.string.payment_history_last_payment,
          DateUtils.formatDate(Locale.getDefault(), latest.timestamp.time)
        )
      )
    }

    item {
      Rows.TextRow(
        text = stringResource(R.string.payment_history_view_all),
        onClick = onViewPaymentHistoryClick
      )
    }
  }
}

private fun TalerPaymentStatus.labelRes(): Int = when (this) {
  TalerPaymentStatus.OFFEN -> R.string.TalerFork_status_compact_open
  TalerPaymentStatus.ANGENOMMEN -> R.string.TalerFork_status_compact_accepted
  TalerPaymentStatus.LOKAL_ABGELEHNT -> R.string.TalerFork_status_compact_declined
  TalerPaymentStatus.LOKAL_ABGEBROCHEN -> R.string.TalerFork_status_compact_cancelled
  TalerPaymentStatus.ABGELAUFEN -> R.string.TalerFork_status_compact_expired
  TalerPaymentStatus.UNBEKANNT_OFFLINE -> R.string.TalerFork_status_compact_checking
  TalerPaymentStatus.UNGUELTIG -> R.string.TalerFork_status_compact_invalid
}
