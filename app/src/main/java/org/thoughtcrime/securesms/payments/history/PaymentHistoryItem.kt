/*
 * PaymentHistoryItem.kt - Datenmodell für Zahlungshistorie
 * 
 * Dies ist die Implementierung der Zahlungshistorie für Signal-Taler-Integration.
 */

package org.thoughtcrime.securesms.payments.history

import org.thoughtcrime.securesms.recipients.RecipientId
import java.util.Date

/**
 * Datenmodell für einen Eintrag in der Zahlungshistorie.
 * Kombiniert Informationen aus Signal (Chat-Metadaten) und GNU Taler (Zahlungsstatus).
 */
data class PaymentHistoryItem(
    /** Eindeutige ID dieses History-Eintrags */
    val id: String,
    
    /** Betrag der Zahlung */
    val amount: String,
    
    /** Währung (z.B. "EUR", "USD", "KUDOS") */
    val currency: String,
    
    /** Richtung der Zahlung: inflow = eingehend, outflow = ausgehend */
    val direction: PaymentDirection,
    
    /** Absender der Zahlung */
    val sender: PaymentParty,
    
    /** Empfänger der Zahlung */
    val recipient: PaymentParty,
    
    /** Status der Zahlung */
    val status: PaymentStatus,
    
    /** Zeitstempel der Zahlung */
    val timestamp: Date,
    
    /** Optional: ID des Chats/Threads für Link zum Chat */
    val chatId: Long? = null,
    
    /** Optional: Taler-spezifische Payment-ID für Details in GNU Taler */
    val talerPaymentId: String? = null,
    
    /** Optional: URI für Refund-Operationen */
    val refundUri: String? = null,
    
    /** Optional: Zusammenfassung/Verwendungszweck */
    val summary: String? = null,
    
    /** Optional: Exchange Base URL */
    val exchangeBaseUrl: String? = null,
    
    /** Optional: URI-Kind (z.B. "pay-push", "pay-pull") */
    val uriKind: String? = null
) {
    /**
     * Gibt an, ob diese Zahlung von uns selbst initiiert wurde.
     */
    val isOwnPayment: Boolean
        get() = direction == PaymentDirection.OUTFLOW

    /**
     * Gibt an, ob ein Refund möglich ist.
     */
    val isRefundable: Boolean
        get() = status == PaymentStatus.COMPLETED && 
                direction == PaymentDirection.OUTFLOW && 
                refundUri != null
}

/**
 * Richtung der Zahlung
 */
enum class PaymentDirection {
    /** Eingehende Zahlung (wir haben Geld erhalten) */
    INFLOW,
    /** Ausgehende Zahlung (wir haben Geld gesendet) */
    OUTFLOW
}

/**
 * Status der Zahlung
 */
enum class PaymentStatus {
    /** Zahlung ist noch offen/ausstehend */
    PENDING,
    /** Zahlung wurde erfolgreich abgeschlossen */
    COMPLETED,
    /** Zahlung wurde erstattet */
    REFUNDED,
    /** Zahlung ist fehlgeschlagen */
    FAILED,
    /** Zahlung wurde lokal abgelehnt */
    LOCAL_REJECTED,
    /** Zahlung wurde durch Löschen der Nachricht lokal abgebrochen */
    LOCAL_CANCELLED,
    /** Zustand unbekannt (offline) */
    UNKNOWN_OFFLINE,
    /** Zahlung abgelaufen */
    EXPIRED
}

/**
 * Partei (Absender oder Empfänger) einer Zahlung
 */
data class PaymentParty(
    /** Eindeutige ID der Partei */
    val id: RecipientId,
    
    /** Anzeigename der Partei */
    val name: String
)

/**
 * Filter-Optionen für die Zahlungshistorie
 */
data class PaymentHistoryFilter(
    /** Zeitrahmen-Filter */
    val timeRange: TimeRange = TimeRange.ALL,
    
    /** Filter nach Richtung */
    val direction: PaymentDirection? = null,
    
    /** Filter nach Status */
    val status: PaymentStatus? = null,
    
    /** Filter nach Chat/Thread */
    val chatId: Long? = null
)

/**
 * Zeitrahmen für die Filterung
 */
enum class TimeRange {
    /** Alle Zeiträume */
    ALL,
    /** Letzte 7 Tage */
    LAST_7_DAYS,
    /** Letzte 30 Tage */
    LAST_30_DAYS,
    /** Letzte 90 Tage */
    LAST_90_DAYS,
    /** Benutzerdefinierter Zeitraum */
    CUSTOM
}

/**
 * Ergebnis einer History-Abfrage
 */
sealed class PaymentHistoryResult {
    /** Erfolgreiches Ergebnis mit Daten */
    data class Success(
        val items: List<PaymentHistoryItem>,
        val totalCount: Int
    ) : PaymentHistoryResult()
    
    /** Fehler bei der Abfrage */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : PaymentHistoryResult()
    
    /** Ladezustand */
    object Loading : PaymentHistoryResult()
}

/**
 * Sortieroptionen für die Zahlungshistorie
 */
data class PaymentHistorySort(
    /** Feld, nach dem sortiert wird */
    val field: SortField = SortField.DATE,
    
    /** Sortierrichtung */
    val direction: SortDirection = SortDirection.DESCENDING
)

enum class SortField {
    DATE,
    AMOUNT,
    SENDER,
    RECIPIENT,
    STATUS
}

enum class SortDirection {
    ASCENDING,
    DESCENDING
}
