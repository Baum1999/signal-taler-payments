/*
 * PaymentHistoryManager.kt - Manager für Zahlungshistorie
 * 
 * Dies ist die Implementierung der Zahlungshistorie für Signal-Taler-Integration.
 */

package org.thoughtcrime.securesms.payments.history

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.database.PaymentHistoryTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.taler.TalerPaymentStatus
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manager für den Zugriff auf die Zahlungshistorie.
 * Bietet LiveData für die UI und Methoden zum Laden, Filtern und Sortieren.
 *
 * Greift bewusst ueber die statischen [SignalDatabase]-Companion-Accessoren
 * zu (SignalDatabase.paymentHistory/talerPayments/threads), nicht ueber eine
 * injizierte Instanz - [SignalDatabase.instance] ist privat, im gesamten
 * restlichen Code wird ausschliesslich so auf Tabellen zugegriffen.
 */
class PaymentHistoryManager(
    private val scope: CoroutineScope
) {

    private val paymentHistoryTable: PaymentHistoryTable
        get() = SignalDatabase.paymentHistory

    private val _items = MutableLiveData<PaymentHistoryResult>()
    val items: LiveData<PaymentHistoryResult> = _items
    
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading
    
    private val _isLoadingMore = MutableLiveData<Boolean>()
    val isLoadingMore: LiveData<Boolean> = _isLoadingMore
    
    private val _error = MutableLiveData<Throwable?>()
    val error: LiveData<Throwable?> = _error
    
    private val _totalCount = MutableLiveData<Int>()
    val totalCount: LiveData<Int> = _totalCount
    
    private var currentFilter: PaymentHistoryFilter = PaymentHistoryFilter()
    private var currentSort: PaymentHistorySort = PaymentHistorySort()
    
    private val isFetching = AtomicBoolean(false)
    private val isFetchingMore = AtomicBoolean(false)
    
    private var currentOffset = 0
    private val pageSize = 50

    companion object {
        private val TAG = Log.tag(PaymentHistoryManager::class.java)
    }

    /**
     * Lädt die Zahlungshistorie mit den aktuellen Filtern und Sortieroptionen.
     */
    fun fetchHistory(reset: Boolean = false) {
        if (isFetching.getAndSet(true)) {
            Log.d(TAG, "Already fetching, skipping...")
            return
        }

        if (reset) {
            currentOffset = 0
        }

        _isLoading.value = true
        _error.value = null

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    loadHistoryPage()
                }
                
                _items.value = result
                updateTotalCount()
                
                if (reset) {
                    currentOffset = pageSize
                } else {
                    currentOffset += pageSize
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching payment history", e)
                _error.value = e
                _items.value = PaymentHistoryResult.Error(e.message ?: "Unknown error", e)
            } finally {
                isFetching.set(false)
                _isLoading.value = false
            }
        }
    }

    /**
     * Lädt die nächste Seite der Zahlungshistorie.
     */
    fun loadMoreHistoryIfNeeded(lastVisibleIndex: Int) {
        if (isFetchingMore.getAndSet(true)) {
            return
        }

        val currentItems = (_items.value as? PaymentHistoryResult.Success)?.items?.size ?: 0
        
        // Lade mehr, wenn wir kurz vor dem Ende der aktuellen Liste sind
        if (lastVisibleIndex >= currentItems - 10) {
            _isLoadingMore.value = true
            
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        loadHistoryPage()
                    }
                    
                    val current = _items.value as? PaymentHistoryResult.Success ?:
                        PaymentHistoryResult.Success(emptyList(), 0)
                    val successResult = result as? PaymentHistoryResult.Success

                    _items.value = PaymentHistoryResult.Success(
                        current.items + (successResult?.items ?: emptyList()),
                        successResult?.totalCount ?: current.totalCount
                    )
                    
                    currentOffset += pageSize
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading more payment history", e)
                    _error.value = e
                } finally {
                    isFetchingMore.set(false)
                    _isLoadingMore.value = false
                }
            }
        }
    }

    /**
     * Lädt eine Seite der Zahlungshistorie.
     */
    private fun loadHistoryPage(): PaymentHistoryResult {
        try {
            val timeRange = currentFilter.timeRange
            val (fromTimestamp, toTimestamp) = when (timeRange) {
                TimeRange.ALL -> null to null
                TimeRange.LAST_7_DAYS -> System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000) to null
                TimeRange.LAST_30_DAYS -> System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000) to null
                TimeRange.LAST_90_DAYS -> System.currentTimeMillis() - (90 * 24 * 60 * 60 * 1000) to null
                TimeRange.CUSTOM -> null to null // Würde durch benutzerdefinierten Zeitraum ersetzt
            }

            val items = paymentHistoryTable.getFiltered(
                chatId = currentFilter.chatId,
                status = currentFilter.status,
                direction = currentFilter.direction,
                fromTimestamp = fromTimestamp,
                toTimestamp = toTimestamp,
                limit = pageSize,
                offset = currentOffset
            )

            val totalCount = paymentHistoryTable.getCount(
                chatId = currentFilter.chatId,
                status = currentFilter.status,
                direction = currentFilter.direction,
                fromTimestamp = fromTimestamp,
                toTimestamp = toTimestamp
            )

            return PaymentHistoryResult.Success(items, totalCount)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading history page", e)
            throw e
        }
    }

    /**
     * Aktualisiert die Gesamtanzahl der Einträge.
     */
    private fun updateTotalCount() {
        scope.launch {
            try {
                val timeRange = currentFilter.timeRange
                val (fromTimestamp, toTimestamp) = when (timeRange) {
                    TimeRange.ALL -> null to null
                    TimeRange.LAST_7_DAYS -> System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000) to null
                    TimeRange.LAST_30_DAYS -> System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000) to null
                    TimeRange.LAST_90_DAYS -> System.currentTimeMillis() - (90 * 24 * 60 * 60 * 1000) to null
                    TimeRange.CUSTOM -> null to null
                }

                val count = paymentHistoryTable.getCount(
                    chatId = currentFilter.chatId,
                    status = currentFilter.status,
                    direction = currentFilter.direction,
                    fromTimestamp = fromTimestamp,
                    toTimestamp = toTimestamp
                )
                
                _totalCount.value = count
                
            } catch (e: Exception) {
                Log.e(TAG, "Error updating total count", e)
            }
        }
    }

    /**
     * Setzt die aktuellen Filteroptionen.
     */
    fun setFilter(filter: PaymentHistoryFilter) {
        currentFilter = filter
        fetchHistory(reset = true)
    }

    /**
     * Setzt die aktuellen Sortieroptionen.
     */
    fun setSort(sort: PaymentHistorySort) {
        currentSort = sort
        fetchHistory(reset = true)
    }

    /**
     * Filtert nach Zeitrahmen.
     */
    fun setTimeRange(range: TimeRange) {
        currentFilter = currentFilter.copy(timeRange = range)
        fetchHistory(reset = true)
    }

    /**
     * Filtert nach Richtung.
     */
    fun setDirection(direction: PaymentDirection?) {
        currentFilter = currentFilter.copy(direction = direction)
        fetchHistory(reset = true)
    }

    /**
     * Filtert nach Status.
     */
    fun setStatus(status: PaymentStatus?) {
        currentFilter = currentFilter.copy(status = status)
        fetchHistory(reset = true)
    }

    /**
     * Filtert nach Chat.
     */
    fun setChatId(chatId: Long?) {
        currentFilter = currentFilter.copy(chatId = chatId)
        fetchHistory(reset = true)
    }

    /**
     * Fügt einen neuen History-Eintrag hinzu oder aktualisiert einen bestehenden.
     */
    fun upsertItem(item: PaymentHistoryItem): Boolean {
        return paymentHistoryTable.upsert(item)
    }

    /**
     * Aktualisiert den Status eines History-Eintrags.
     */
    fun updateStatus(paymentId: String, status: PaymentStatus) {
        paymentHistoryTable.updateStatus(paymentId, status)
        // Aktualisiere die aktuelle Liste, falls der Eintrag sichtbar ist
        scope.launch {
            fetchHistory(reset = true)
        }
    }

    /**
     * Löscht einen History-Eintrag.
     */
    fun deleteItem(paymentId: String) {
        paymentHistoryTable.delete(paymentId)
        scope.launch {
            fetchHistory(reset = true)
        }
    }

    /**
     * Gibt einen History-Eintrag zurück.
     */
    fun getItem(paymentId: String): PaymentHistoryItem? {
        return paymentHistoryTable.getByPaymentId(paymentId)
    }

    /**
     * Erstellt einen History-Eintrag aus einem TalerPaymentRecord.
     */
    fun createFromTalerPayment(
        paymentId: String,
        talerPaymentRecord: org.thoughtcrime.securesms.database.TalerPaymentRecord,
        recipient: Recipient
    ): PaymentHistoryItem {
        val isOwnPayment = talerPaymentRecord.isOwnPayment
        val context = AppDependencies.application
        val selfName = Recipient.self().getDisplayName(context)
        val recipientName = recipient.getDisplayName(context)

        val (sender, recipientParty) = if (isOwnPayment) {
            Pair(
                PaymentParty(Recipient.self().id, selfName),
                PaymentParty(recipient.id, recipientName)
            )
        } else {
            Pair(
                PaymentParty(recipient.id, recipientName),
                PaymentParty(Recipient.self().id, selfName)
            )
        }

        val direction = if (isOwnPayment) PaymentDirection.OUTFLOW else PaymentDirection.INFLOW
        val status = mapTalerPaymentStatus(talerPaymentRecord.status)

        return PaymentHistoryItem(
            id = paymentId,
            amount = talerPaymentRecord.amount ?: "0",
            currency = talerPaymentRecord.currency ?: "EUR",
            direction = direction,
            sender = sender,
            recipient = recipientParty,
            status = status,
            timestamp = Date(talerPaymentRecord.createdAt),
            chatId = talerPaymentRecord.threadId,
            talerPaymentId = paymentId,
            refundUri = null, // Würde aus Taler API kommen
            summary = talerPaymentRecord.summary,
            exchangeBaseUrl = talerPaymentRecord.exchangeBaseUrl,
            uriKind = talerPaymentRecord.uriKind
        )
    }

    /**
     * Mapped TalerPaymentStatus auf PaymentStatus.
     */
    private fun mapTalerPaymentStatus(status: TalerPaymentStatus): PaymentStatus {
        return when (status) {
            TalerPaymentStatus.UNBEKANNT_OFFLINE -> PaymentStatus.UNKNOWN_OFFLINE
            TalerPaymentStatus.TALER_NICHT_VERBUNDEN -> PaymentStatus.TALER_NOT_CONNECTED
            TalerPaymentStatus.NICHT_INSTALLIERT -> PaymentStatus.NOT_INSTALLED
            TalerPaymentStatus.NICHT_VERTRAUENSWUERDIG -> PaymentStatus.NOT_TRUSTED
            TalerPaymentStatus.OFFEN -> PaymentStatus.PENDING
            TalerPaymentStatus.ANGENOMMEN -> PaymentStatus.COMPLETED
            TalerPaymentStatus.ABGELAUFEN -> PaymentStatus.EXPIRED
            TalerPaymentStatus.UNGUELTIG -> PaymentStatus.FAILED
            TalerPaymentStatus.LOKAL_ABGELEHNT -> PaymentStatus.LOCAL_REJECTED
        }
    }

    /**
     * Aktualisiert die History aus der TalerPaymentTable.
     * Synchronisiert alle Zahlungen aus der TalerPaymentTable in die PaymentHistoryTable.
     */
    fun syncFromTalerPayments() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val talerPayments = SignalDatabase.talerPayments.getAll()

                    talerPayments.forEach { talerPayment ->
                        val existing = paymentHistoryTable.getByPaymentId(talerPayment.uri)

                        if (existing == null) {
                            // Versuche, den Empfänger aus dem Thread zu ermitteln
                            val recipient = SignalDatabase.threads.getRecipientForThreadId(talerPayment.threadId)

                            if (recipient != null) {
                                val historyItem = createFromTalerPayment(talerPayment.uri, talerPayment, recipient)
                                paymentHistoryTable.upsert(historyItem)
                            }
                        } else {
                            // Aktualisiere den Status
                            val newStatus = mapTalerPaymentStatus(talerPayment.status)
                            if (existing.status != newStatus) {
                                paymentHistoryTable.updateStatus(talerPayment.uri, newStatus)
                            }
                        }
                    }
                }
                
                // Lade die aktualisierte Liste
                fetchHistory(reset = true)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing from Taler payments", e)
                _error.value = e
            }
        }
    }

    /**
     * Löscht alle History-Einträge für einen bestimmten Chat.
     */
    fun deleteForChat(chatId: Long) {
        paymentHistoryTable.deleteForChat(chatId)
        
        // Wenn wir gerade diesen Chat anzeigen, aktualisiere die Liste
        if (currentFilter.chatId == chatId) {
            scope.launch {
                fetchHistory(reset = true)
            }
        } else {
            // Aktualisiere die Gesamtanzahl
            updateTotalCount()
        }
    }

    /**
     * Gibt die neuesten History-Einträge zurück.
     */
    fun getRecent(limit: Int): List<PaymentHistoryItem> {
        return paymentHistoryTable.getRecent(limit)
    }

    /**
     * Refresh der gesamten History.
     */
    fun refresh() {
        fetchHistory(reset = true)
    }

    /**
     * Räumt Fehler auf.
     */
    fun clearError() {
        _error.value = null
    }
}
