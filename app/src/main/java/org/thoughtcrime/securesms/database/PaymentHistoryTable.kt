/*
 * PaymentHistoryTable.kt - Datenbanktabelle für Zahlungshistorie
 * 
 * Dies ist die Implementierung der Zahlungshistorie für Signal-Taler-Integration.
 */

package org.thoughtcrime.securesms.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.signal.core.util.delete
import org.signal.core.util.insertInto
import org.signal.core.util.readToList
import org.signal.core.util.readToSingleObject
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.signal.core.util.requireLongOrNull
import org.signal.core.util.requireNonNullString
import org.signal.core.util.requireString
import org.signal.core.util.select
import org.signal.core.util.update
import org.thoughtcrime.securesms.payments.history.PaymentDirection
import org.thoughtcrime.securesms.payments.history.PaymentHistoryItem
import org.thoughtcrime.securesms.payments.history.PaymentParty
import org.thoughtcrime.securesms.payments.history.PaymentStatus
import org.thoughtcrime.securesms.recipients.RecipientId
import java.util.Date

/**
 * Datenbanktabelle für die Zahlungshistorie.
 * Speichert Zahlungsvorgänge mit Metadaten für die Anzeige in der History-UI.
 */
class PaymentHistoryTable(context: Context, databaseHelper: SignalDatabase) : DatabaseTable(context, databaseHelper) {

    companion object {
        const val TABLE_NAME = "payment_history"
        
        // Spaltennamen
        const val ID = "_id"
        const val PAYMENT_ID = "payment_id"  // Taler Payment URI (einzigartig)
        const val AMOUNT = "amount"
        const val CURRENCY = "currency"
        const val DIRECTION = "direction"
        const val SENDER_ID = "sender_id"
        const val SENDER_NAME = "sender_name"
        const val RECIPIENT_ID = "recipient_id"
        const val RECIPIENT_NAME = "recipient_name"
        const val STATUS = "status"
        const val TIMESTAMP = "timestamp"
        const val CHAT_ID = "chat_id"  // Thread-ID
        const val TALER_PAYMENT_ID = "taler_payment_id"
        const val REFUND_URI = "refund_uri"
        const val SUMMARY = "summary"
        const val EXCHANGE_BASE_URL = "exchange_base_url"
        const val URI_KIND = "uri_kind"
        const val CREATED_AT = "created_at"
        
        const val CREATE_TABLE = """
            CREATE TABLE $TABLE_NAME (
                $ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $PAYMENT_ID TEXT NOT NULL UNIQUE,
                $AMOUNT TEXT NOT NULL,
                $CURRENCY TEXT NOT NULL,
                $DIRECTION TEXT NOT NULL,
                $SENDER_ID TEXT NOT NULL,
                $SENDER_NAME TEXT NOT NULL,
                $RECIPIENT_ID TEXT NOT NULL,
                $RECIPIENT_NAME TEXT NOT NULL,
                $STATUS TEXT NOT NULL,
                $TIMESTAMP INTEGER NOT NULL,
                $CHAT_ID INTEGER,
                $TALER_PAYMENT_ID TEXT,
                $REFUND_URI TEXT,
                $SUMMARY TEXT,
                $EXCHANGE_BASE_URL TEXT,
                $URI_KIND TEXT,
                $CREATED_AT INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """
        
        const val CREATE_INDEX_PAYMENT_ID = "CREATE UNIQUE INDEX IF NOT EXISTS payment_history_payment_id_index ON $TABLE_NAME ($PAYMENT_ID)"
        const val CREATE_INDEX_CHAT_ID = "CREATE INDEX IF NOT EXISTS payment_history_chat_id_index ON $TABLE_NAME ($CHAT_ID)"
        const val CREATE_INDEX_TIMESTAMP = "CREATE INDEX IF NOT EXISTS payment_history_timestamp_index ON $TABLE_NAME ($TIMESTAMP)"
        const val CREATE_INDEX_STATUS = "CREATE INDEX IF NOT EXISTS payment_history_status_index ON $TABLE_NAME ($STATUS)"
        const val CREATE_INDEX_DIRECTION = "CREATE INDEX IF NOT EXISTS payment_history_direction_index ON $TABLE_NAME ($DIRECTION)"
    }

    /**
     * Liest einen Datensatz aus dem Cursor
     */
    private fun readRecord(cursor: android.database.Cursor): PaymentHistoryItem = PaymentHistoryItem(
        id = cursor.requireLong(ID).toString(),
        amount = cursor.requireNonNullString(AMOUNT),
        currency = cursor.requireNonNullString(CURRENCY),
        direction = PaymentDirection.valueOf(cursor.requireNonNullString(DIRECTION)),
        sender = PaymentParty(
            id = RecipientId.from(cursor.requireNonNullString(SENDER_ID)),
            name = cursor.requireNonNullString(SENDER_NAME)
        ),
        recipient = PaymentParty(
            id = RecipientId.from(cursor.requireNonNullString(RECIPIENT_ID)),
            name = cursor.requireNonNullString(RECIPIENT_NAME)
        ),
        status = PaymentStatus.valueOf(cursor.requireNonNullString(STATUS)),
        timestamp = Date(cursor.requireLong(TIMESTAMP)),
        chatId = cursor.requireLongOrNull(CHAT_ID),
        talerPaymentId = cursor.requireString(TALER_PAYMENT_ID),
        refundUri = cursor.requireString(REFUND_URI),
        summary = cursor.requireString(SUMMARY),
        exchangeBaseUrl = cursor.requireString(EXCHANGE_BASE_URL),
        uriKind = cursor.requireString(URI_KIND)
    )

    /**
     * Fügt einen neuen History-Eintrag ein oder aktualisiert ihn, falls er bereits existiert.
     * 
     * @param item Der zu speichernde History-Eintrag
     * @return true, wenn ein neuer Eintrag erstellt wurde; false, wenn aktualisiert
     */
    fun upsert(item: PaymentHistoryItem): Boolean {
        val rowId = writableDatabase
            .insertInto(TABLE_NAME)
            .values(
                PAYMENT_ID to (item.talerPaymentId ?: item.id),
                AMOUNT to item.amount,
                CURRENCY to item.currency,
                DIRECTION to item.direction.name,
                SENDER_ID to item.sender.id.toString(),
                SENDER_NAME to item.sender.name,
                RECIPIENT_ID to item.recipient.id.toString(),
                RECIPIENT_NAME to item.recipient.name,
                STATUS to item.status.name,
                TIMESTAMP to item.timestamp.time,
                CHAT_ID to item.chatId,
                TALER_PAYMENT_ID to item.talerPaymentId,
                REFUND_URI to item.refundUri,
                SUMMARY to item.summary,
                EXCHANGE_BASE_URL to item.exchangeBaseUrl,
                URI_KIND to item.uriKind,
                CREATED_AT to System.currentTimeMillis()
            )
            .run(conflictStrategy = SQLiteDatabase.CONFLICT_REPLACE)
        
        return rowId > 0
    }

    /**
     * Aktualisiert den Status eines bestehenden History-Eintrags.
     * 
     * @param paymentId Die Taler Payment URI oder ID
     * @param status Der neue Status
     */
    fun updateStatus(paymentId: String, status: PaymentStatus) {
        writableDatabase
            .update(TABLE_NAME)
            .values(
                STATUS to status.name,
                CREATED_AT to System.currentTimeMillis()
            )
            .where("$PAYMENT_ID = ?", paymentId)
            .run()
    }

    /**
     * Löscht einen History-Eintrag.
     * 
     * @param paymentId Die Taler Payment URI oder ID
     */
    fun delete(paymentId: String) {
        writableDatabase
            .delete(TABLE_NAME)
            .where("$PAYMENT_ID = ?", paymentId)
            .run()
    }

    /**
     * Gibt einen History-Eintrag zurück.
     * 
     * @param paymentId Die Taler Payment URI oder ID
     * @return Der History-Eintrag oder null, falls nicht gefunden
     */
    fun getByPaymentId(paymentId: String): PaymentHistoryItem? =
        readableDatabase
            .select()
            .from(TABLE_NAME)
            .where("$PAYMENT_ID = ?", paymentId)
            .run()
            .readToSingleObject { readRecord(it) }

    /**
     * Gibt alle History-Einträge zurück.
     * 
     * @return Liste aller History-Einträge, sortiert nach Zeitstempel (absteigend)
     */
    fun getAll(): List<PaymentHistoryItem> =
        readableDatabase
            .select()
            .from(TABLE_NAME)
            .orderBy("$TIMESTAMP DESC")
            .run()
            .readToList { readRecord(it) }

    /**
     * Gibt History-Einträge für einen bestimmten Chat/Thread zurück.
     * 
     * @param chatId Die Thread-ID
     * @return Liste der History-Einträge für diesen Chat
     */
    fun getForChat(chatId: Long): List<PaymentHistoryItem> =
        readableDatabase
            .select()
            .from(TABLE_NAME)
            .where("$CHAT_ID = ?", chatId)
            .orderBy("$TIMESTAMP DESC")
            .run()
            .readToList { readRecord(it) }

    /**
     * Gibt History-Einträge mit Filteroptionen zurück.
     * 
     * @param chatId Optional: Filter nach Chat-ID
     * @param status Optional: Filter nach Status
     * @param direction Optional: Filter nach Richtung
     * @param fromTimestamp Optional: Filter nach Startzeitpunkt
     * @param toTimestamp Optional: Filter nach Endzeitpunkt
     * @param limit Optional: Maximale Anzahl von Ergebnissen
     * @param offset Optional: Offset für Pagination
     * @return Gefilterte Liste der History-Einträge
     */
    fun getFiltered(
        chatId: Long? = null,
        status: PaymentStatus? = null,
        direction: PaymentDirection? = null,
        fromTimestamp: Long? = null,
        toTimestamp: Long? = null,
        limit: Int? = null,
        offset: Int? = null
    ): List<PaymentHistoryItem> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()

        chatId?.let {
            conditions.add("$CHAT_ID = ?")
            args.add(it)
        }

        status?.let {
            conditions.add("$STATUS = ?")
            args.add(it.name)
        }

        direction?.let {
            conditions.add("$DIRECTION = ?")
            args.add(it.name)
        }

        fromTimestamp?.let {
            conditions.add("$TIMESTAMP >= ?")
            args.add(it)
        }

        toTimestamp?.let {
            conditions.add("$TIMESTAMP <= ?")
            args.add(it)
        }

        // Der Query-Builder ist ein Stufen-Typ (SelectBuilderPart2/3/4a/...) -
        // jede Methode gibt einen NEUEN, spezifischeren Typ zurueck, deshalb
        // kann hier nicht in einen "var" zwischengespeichert werden (siehe
        // TalerPaymentTable.getPollCandidates fuer dasselbe Muster: WHERE
        // immer als ein einziger, vorab zusammengesetzter String+Args-Aufruf).
        // where("1", ...) statt where() ueberspringen, weil .where() sonst
        // uebersprungen werden muesste UND danach ein anderer statischer Typ
        // (SelectBuilderPart4a ohne where) entstuende.
        val whereClause = if (conditions.isNotEmpty()) conditions.joinToString(" AND ") else "1"
        val query = readableDatabase
            .select()
            .from(TABLE_NAME)
            .where(whereClause, *args.toTypedArray())
            .orderBy("$TIMESTAMP DESC")

        return if (limit != null) {
            query.limit(limit, offset ?: 0).run().readToList { readRecord(it) }
        } else {
            query.run().readToList { readRecord(it) }
        }
    }

    /**
     * Gibt die Anzahl der History-Einträge mit den gegebenen Filtern zurück.
     */
    fun getCount(
        chatId: Long? = null,
        status: PaymentStatus? = null,
        direction: PaymentDirection? = null,
        fromTimestamp: Long? = null,
        toTimestamp: Long? = null
    ): Int {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()

        chatId?.let {
            conditions.add("$CHAT_ID = ?")
            args.add(it)
        }

        status?.let {
            conditions.add("$STATUS = ?")
            args.add(it.name)
        }

        direction?.let {
            conditions.add("$DIRECTION = ?")
            args.add(it.name)
        }

        fromTimestamp?.let {
            conditions.add("$TIMESTAMP >= ?")
            args.add(it)
        }

        toTimestamp?.let {
            conditions.add("$TIMESTAMP <= ?")
            args.add(it)
        }

        val whereClause = if (conditions.isNotEmpty()) conditions.joinToString(" AND ") else "1"

        return readableDatabase
            .select("COUNT(*) as count")
            .from(TABLE_NAME)
            .where(whereClause, *args.toTypedArray())
            .run()
            .readToSingleObject {
                it.requireInt("count")
            } ?: 0
    }

    /**
     * Löscht alle History-Einträge für einen bestimmten Chat.
     * 
     * @param chatId Die Thread-ID
     */
    fun deleteForChat(chatId: Long) {
        writableDatabase
            .delete(TABLE_NAME)
            .where("$CHAT_ID = ?", chatId)
            .run()
    }

    /**
     * Löscht alle History-Einträge mit einem bestimmten Status.
     * 
     * @param status Der zu löschende Status
     */
    fun deleteWithStatus(status: PaymentStatus) {
        writableDatabase
            .delete(TABLE_NAME)
            .where("$STATUS = ?", status.name)
            .run()
    }

    /**
     * Löscht alle History-Einträge, die älter als der angegebene Zeitstempel sind.
     * 
     * @param olderThanTimestamp Zeitstempel (ms seit epoch)
     */
    fun deleteOlderThan(olderThanTimestamp: Long) {
        writableDatabase
            .delete(TABLE_NAME)
            .where("$TIMESTAMP < ?", olderThanTimestamp)
            .run()
    }

    /**
     * Gibt die neuesten History-Einträge zurück.
     * 
     * @param limit Maximale Anzahl von Ergebnissen
     * @return Liste der neuesten History-Einträge
     */
    fun getRecent(limit: Int): List<PaymentHistoryItem> =
        readableDatabase
            .select()
            .from(TABLE_NAME)
            .orderBy("$TIMESTAMP DESC")
            .limit(limit)
            .run()
            .readToList { readRecord(it) }
}
