/*
 * PaymentHistoryFragment.kt - UI für Zahlungshistorie
 * 
 * Dies ist die Implementierung der Zahlungshistorie für Signal-Taler-Integration.
 */

package org.thoughtcrime.securesms.payments.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation.findNavController
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.taler.TalerRefundActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fragment für die Anzeige der Zahlungshistorie.
 */
class PaymentHistoryFragment : Fragment() {

    private val viewModel: PaymentHistoryViewModel by viewModels(
        factoryProducer = { PaymentHistoryViewModelFactory() }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PaymentHistoryScreen(
                viewModel = viewModel,
                onBackClick = { findNavController().popBackStack() },
                onItemClick = { item -> item.chatId?.let { navigateToChat(it) } },
                onRefundClick = { item ->
                    val uri = item.talerPaymentId ?: item.id
                    val chatId = item.chatId
                    if (chatId != null) {
                        TalerRefundActions.onRefundClicked(requireContext(), uri, chatId)
                    }
                },
                onChatLinkClick = { item -> item.chatId?.let { navigateToChat(it) } }
            )
        }
    }

    /**
     * Bug #6: Hyperlink von einem History-Eintrag zurueck zum Chat, in dem die
     * Zahlung urspruenglich aufgetaucht ist - RecipientId kommt aus der
     * Thread-Tabelle, PaymentHistoryItem selbst kennt nur die Thread-ID.
     */
    private fun navigateToChat(chatId: Long) {
        val recipientId = SignalDatabase.threads.getRecipientForThreadId(chatId) ?: return
        val intent = ConversationIntents.createBuilderSync(requireContext(), recipientId.id, chatId).build()
        startActivity(intent)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Fehlerbehandlung
        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                // Zeige Fehler in der UI an
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}

/**
 * ViewModel für die Zahlungshistorie.
 */
class PaymentHistoryViewModel : ViewModel() {

    private val historyManager = PaymentHistoryManager(viewModelScope)

    val items = historyManager.items
    val isLoading = historyManager.isLoading
    val isLoadingMore = historyManager.isLoadingMore
    val error = historyManager.error
    val totalCount = historyManager.totalCount
    
    // Filter- und Sortierzustand
    var currentFilter: PaymentHistoryFilter = PaymentHistoryFilter()
    var currentSort: PaymentHistorySort = PaymentHistorySort()
    
    // Dropdown-Zustände
    var showTimeRangeDropdown by mutableStateOf(false)
    var showDirectionDropdown by mutableStateOf(false)
    var showStatusDropdown by mutableStateOf(false)

    init {
        // Initiales Laden
        loadHistory()
    }

    fun loadHistory() {
        historyManager.fetchHistory(reset = true)
    }

    fun refresh() {
        historyManager.refresh()
    }

    fun loadMore(lastVisibleIndex: Int) {
        historyManager.loadMoreHistoryIfNeeded(lastVisibleIndex)
    }

    fun setTimeRange(range: TimeRange) {
        historyManager.setTimeRange(range)
        currentFilter = currentFilter.copy(timeRange = range)
    }

    fun setDirection(direction: PaymentDirection?) {
        historyManager.setDirection(direction)
        currentFilter = currentFilter.copy(direction = direction)
    }

    fun setStatus(status: PaymentStatus?) {
        historyManager.setStatus(status)
        currentFilter = currentFilter.copy(status = status)
    }

    fun clearError() {
        historyManager.clearError()
    }
}

/**
 * Factory für das ViewModel.
 */
class PaymentHistoryViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PaymentHistoryViewModel() as T
    }
}

/**
 * Haupt-UI für die Zahlungshistorie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentHistoryScreen(
    viewModel: PaymentHistoryViewModel,
    onBackClick: () -> Unit,
    onItemClick: (PaymentHistoryItem) -> Unit,
    onRefundClick: (PaymentHistoryItem) -> Unit,
    onChatLinkClick: (PaymentHistoryItem) -> Unit
) {
    val items by viewModel.items.observeAsState(PaymentHistoryResult.Loading)
    val isLoading by viewModel.isLoading.observeAsState(false)
    val isLoadingMore by viewModel.isLoadingMore.observeAsState(false)
    val totalCount by viewModel.totalCount.observeAsState(0)
    
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    
    val listState = rememberLazyListState()

    // Automatisches Laden beim Scrollen
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { index ->
                if (index != null) {
                    viewModel.loadMore(index)
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zahlungshistorie") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren")
                    }
                    IconButton(onClick = { /* Filter öffnen */ }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filtern")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Filter-Leiste
            FilterBar(
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Anzahl der Einträge anzeigen
            val itemCount = (items as? PaymentHistoryResult.Success)?.items?.size ?: 0
            Text(
                text = "$itemCount von $totalCount Zahlungen",
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Ladeindikator
            if (isLoading && items !is PaymentHistoryResult.Success) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            
            // Liste der Zahlungen
            when (val currentItems = items) {
                is PaymentHistoryResult.Success -> {
                    if (currentItems.items.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Keine Zahlungen gefunden")
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(currentItems.items, key = { it.id }) { item ->
                                PaymentHistoryItemCard(
                                    item = item,
                                    onClick = { onItemClick(item) },
                                    onRefundClick = { onRefundClick(item) },
                                    onChatLinkClick = { onChatLinkClick(item) }
                                )
                            }
                            
                            if (isLoadingMore) {
                                item(key = "loading-more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                        }
                    }
                }
                is PaymentHistoryResult.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Fehler beim Laden der Zahlungshistorie")
                    }
                }
                PaymentHistoryResult.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

/**
 * Filter-Leiste für die Zahlungshistorie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBar(
    viewModel: PaymentHistoryViewModel,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Zeitrahmen-Filter
            TimeRangeFilter(viewModel)
            
            // Richtungs-Filter
            DirectionFilter(viewModel)
            
            // Status-Filter
            StatusFilter(viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRangeFilter(viewModel: PaymentHistoryViewModel) {
    var expanded by mutableStateOf(false)
    val currentRange = viewModel.currentFilter.timeRange
    
    Box {
        ElevatedCard(
            onClick = { expanded = true },
            modifier = Modifier.height(48.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (currentRange) {
                        TimeRange.ALL -> "Alle Zeiten"
                        TimeRange.LAST_7_DAYS -> "Letzte 7 Tage"
                        TimeRange.LAST_30_DAYS -> "Letzte 30 Tage"
                        TimeRange.LAST_90_DAYS -> "Letzte 90 Tage"
                        TimeRange.CUSTOM -> "Benutzerdefiniert"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TimeRange.entries.forEach { range ->
                DropdownMenuItem(
                    text = { 
                        Text(when (range) {
                            TimeRange.ALL -> "Alle Zeiten"
                            TimeRange.LAST_7_DAYS -> "Letzte 7 Tage"
                            TimeRange.LAST_30_DAYS -> "Letzte 30 Tage"
                            TimeRange.LAST_90_DAYS -> "Letzte 90 Tage"
                            TimeRange.CUSTOM -> "Benutzerdefiniert"
                        })
                    },
                    onClick = {
                        viewModel.setTimeRange(range)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectionFilter(viewModel: PaymentHistoryViewModel) {
    var expanded by mutableStateOf(false)
    val currentDirection = viewModel.currentFilter.direction
    
    Box {
        ElevatedCard(
            onClick = { expanded = true },
            modifier = Modifier.height(48.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (currentDirection) {
                        null -> "Alle Richtungen"
                        PaymentDirection.INFLOW -> "Eingehend"
                        PaymentDirection.OUTFLOW -> "Ausgehend"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Alle Richtungen") },
                onClick = {
                    viewModel.setDirection(null)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Eingehend") },
                onClick = {
                    viewModel.setDirection(PaymentDirection.INFLOW)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Ausgehend") },
                onClick = {
                    viewModel.setDirection(PaymentDirection.OUTFLOW)
                    expanded = false
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusFilter(viewModel: PaymentHistoryViewModel) {
    var expanded by mutableStateOf(false)
    val currentStatus = viewModel.currentFilter.status
    
    Box {
        ElevatedCard(
            onClick = { expanded = true },
            modifier = Modifier.height(48.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (currentStatus) {
                        null -> "Alle Stati"
                        PaymentStatus.PENDING -> "Ausstehend"
                        PaymentStatus.COMPLETED -> "Abgeschlossen"
                        PaymentStatus.REFUNDED -> "Erstattet"
                        PaymentStatus.FAILED -> "Fehlgeschlagen"
                        PaymentStatus.LOCAL_REJECTED -> "Lokal abgelehnt"
                        PaymentStatus.LOCAL_CANCELLED -> "Lokal abgebrochen"
                        PaymentStatus.UNKNOWN_OFFLINE -> "Unbekannt (offline)"
                        PaymentStatus.TALER_NOT_CONNECTED -> "Taler nicht verbunden"
                        PaymentStatus.NOT_INSTALLED -> "Nicht installiert"
                        PaymentStatus.NOT_TRUSTED -> "Nicht vertrauenswürdig"
                        PaymentStatus.EXPIRED -> "Abgelaufen"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Alle Stati") },
                onClick = {
                    viewModel.setStatus(null)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Ausstehend") },
                onClick = {
                    viewModel.setStatus(PaymentStatus.PENDING)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Abgeschlossen") },
                onClick = {
                    viewModel.setStatus(PaymentStatus.COMPLETED)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Erstattet") },
                onClick = {
                    viewModel.setStatus(PaymentStatus.REFUNDED)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Fehlgeschlagen") },
                onClick = {
                    viewModel.setStatus(PaymentStatus.FAILED)
                    expanded = false
                }
            )
        }
    }
}

/**
 * Karte für einen Zahlungshistorie-Eintrag.
 */
@Composable
fun PaymentHistoryItemCard(
    item: PaymentHistoryItem,
    onClick: () -> Unit,
    onRefundClick: () -> Unit,
    onChatLinkClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Oberste Zeile: Betrag und Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${item.amount} ${item.currency}",
                    style = MaterialTheme.typography.titleMedium
                )
                
                StatusBadge(item.status)
            }
            
            // Richtung anzeigen
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = when (item.direction) {
                        PaymentDirection.INFLOW -> "Von: ${item.sender.name}"
                        PaymentDirection.OUTFLOW -> "An: ${item.recipient.name}"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = dateFormat.format(item.timestamp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            // Zusammenfassung
            item.summary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Aktionen
            if (item.isRefundable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    ElevatedCard(
                        onClick = onRefundClick,
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = "Refund",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            
            // Chat-Link
            item.chatId?.let { chatId ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "Zum Chat",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.shapes.small
                            )
                            .clickable(onClick = onChatLinkClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Status-Badge für die Anzeige des Zahlungsstatus.
 */
@Composable
fun StatusBadge(status: PaymentStatus) {
    val (color, label) = when (status) {
        PaymentStatus.PENDING -> MaterialTheme.colorScheme.secondaryContainer to "Ausstehend"
        PaymentStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer to "Abgeschlossen"
        PaymentStatus.REFUNDED -> MaterialTheme.colorScheme.tertiaryContainer to "Erstattet"
        PaymentStatus.FAILED -> MaterialTheme.colorScheme.errorContainer to "Fehlgeschlagen"
        PaymentStatus.LOCAL_REJECTED -> MaterialTheme.colorScheme.errorContainer to "Abgelehnt"
        PaymentStatus.LOCAL_CANCELLED -> MaterialTheme.colorScheme.errorContainer to "Abgebrochen"
        PaymentStatus.UNKNOWN_OFFLINE -> MaterialTheme.colorScheme.surfaceVariant to "Unbekannt"
        PaymentStatus.TALER_NOT_CONNECTED -> MaterialTheme.colorScheme.surfaceVariant to "Nicht verbunden"
        PaymentStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.surfaceVariant to "Nicht installiert"
        PaymentStatus.NOT_TRUSTED -> MaterialTheme.colorScheme.surfaceVariant to "Nicht vertrauenswürdig"
        PaymentStatus.EXPIRED -> MaterialTheme.colorScheme.surfaceVariant to "Abgelaufen"
    }
    
    Surface(
        color = color,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Vorschau für die Zahlungshistorie.
 */
@Preview(showBackground = true)
@Composable
fun PaymentHistoryPreview() {
    val sampleItem = PaymentHistoryItem(
        id = "1",
        amount = "10.00",
        currency = "EUR",
        direction = PaymentDirection.OUTFLOW,
        sender = PaymentParty(
            id = org.thoughtcrime.securesms.recipients.RecipientId.from("test1"),
            name = "Max Mustermann"
        ),
        recipient = PaymentParty(
            id = org.thoughtcrime.securesms.recipients.RecipientId.from("test2"),
            name = "Erika Mustermann"
        ),
        status = PaymentStatus.COMPLETED,
        timestamp = Date(),
        chatId = 123L,
        talerPaymentId = "taler:payment:123",
        refundUri = "taler:refund:123",
        summary = "Miete Januar"
    )
    
    PaymentHistoryItemCard(
        item = sampleItem,
        onClick = {},
        onRefundClick = {},
        onChatLinkClick = {}
    )
}
