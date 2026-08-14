package org.thoughtcrime.securesms.database

import android.content.Context
import org.signal.core.util.insertInto
import org.signal.core.util.logging.Log
import org.signal.core.util.readToList
import org.signal.core.util.readToSingleObject
import org.signal.core.util.requireLong
import org.signal.core.util.requireLongOrNull
import org.signal.core.util.requireNonNullString
import org.signal.core.util.requireString
import org.signal.core.util.select
import org.signal.core.util.update
import org.thoughtcrime.securesms.taler.TalerPaymentStatus

data class TalerPaymentRecord(
  val uri: String,
  val threadId: Long,
  val uriKind: String?,
  val status: TalerPaymentStatus,
  val amount: String?,
  val currency: String?,
  val exchangeBaseUrl: String?,
  val summary: String?,
  val createdAt: Long,
  val lastCheckedAt: Long?,
)

/**
 * Ein Vorgang pro Taler-URI, nicht pro Nachricht - doppelt zugestellte,
 * zitierte und weitergeleitete Nachrichten mit derselben URI sind derselbe
 * Vorgang (siehe docs/API.md, Abschnitt "Zustaende"). [uri] ist deshalb der
 * eindeutige Schluessel, nicht die message-ID.
 */
class TalerPaymentTable(context: Context, databaseHelper: SignalDatabase) : DatabaseTable(context, databaseHelper) {

  companion object {
    private val TAG = Log.tag(TalerPaymentTable::class)

    const val TABLE_NAME = "taler_payment"
    const val ID = "_id"
    const val URI = "uri"
    const val THREAD_ID = "thread_id"
    const val URI_KIND = "uri_kind"
    const val STATUS = "status"
    const val AMOUNT = "amount"
    const val CURRENCY = "currency"
    const val EXCHANGE_BASE_URL = "exchange_base_url"
    const val SUMMARY = "summary"
    const val CREATED_AT = "created_at"
    const val LAST_CHECKED_AT = "last_checked_at"

    const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY,
        $URI TEXT NOT NULL UNIQUE,
        $THREAD_ID INTEGER NOT NULL,
        $URI_KIND TEXT DEFAULT NULL,
        $STATUS TEXT NOT NULL,
        $AMOUNT TEXT DEFAULT NULL,
        $CURRENCY TEXT DEFAULT NULL,
        $EXCHANGE_BASE_URL TEXT DEFAULT NULL,
        $SUMMARY TEXT DEFAULT NULL,
        $CREATED_AT INTEGER NOT NULL,
        $LAST_CHECKED_AT INTEGER DEFAULT NULL
      )
    """

    const val CREATE_INDEX = "CREATE INDEX IF NOT EXISTS taler_payment_thread_id_index ON $TABLE_NAME ($THREAD_ID)"

    private fun readRecord(cursor: android.database.Cursor): TalerPaymentRecord = TalerPaymentRecord(
      uri = cursor.requireNonNullString(URI),
      threadId = cursor.requireLong(THREAD_ID),
      uriKind = cursor.requireString(URI_KIND),
      status = TalerPaymentStatus.valueOf(cursor.requireNonNullString(STATUS)),
      amount = cursor.requireString(AMOUNT),
      currency = cursor.requireString(CURRENCY),
      exchangeBaseUrl = cursor.requireString(EXCHANGE_BASE_URL),
      summary = cursor.requireString(SUMMARY),
      createdAt = cursor.requireLong(CREATED_AT),
      lastCheckedAt = cursor.requireLongOrNull(LAST_CHECKED_AT),
    )
  }

  /**
   * Legt einen neuen Vorgang an, falls [uri] noch nicht bekannt ist. Gibt
   * true zurueck, wenn tatsaechlich neu angelegt wurde (Aufrufer soll dann
   * einen Refresh-Job enqueuen) - false, wenn der Vorgang schon existierte.
   */
  fun upsertDetected(uri: String, threadId: Long): Boolean {
    val rowId = writableDatabase
      .insertInto(TABLE_NAME)
      .values(
        URI to uri,
        THREAD_ID to threadId,
        STATUS to TalerPaymentStatus.UNBEKANNT_OFFLINE.name,
        CREATED_AT to System.currentTimeMillis(),
      )
      .run(conflictStrategy = SQLiteDatabase.CONFLICT_IGNORE)
    return rowId > 0
  }

  fun updateFromPreview(
    uri: String,
    uriKind: String?,
    status: TalerPaymentStatus,
    amount: String?,
    currency: String?,
    exchangeBaseUrl: String?,
    summary: String?,
  ) {
    writableDatabase
      .update(TABLE_NAME)
      .values(
        URI_KIND to uriKind,
        STATUS to status.name,
        AMOUNT to amount,
        CURRENCY to currency,
        EXCHANGE_BASE_URL to exchangeBaseUrl,
        SUMMARY to summary,
        LAST_CHECKED_AT to System.currentTimeMillis(),
      )
      .where("$URI = ?", uri)
      .run()
  }

  fun updateStatus(uri: String, status: TalerPaymentStatus) {
    writableDatabase
      .update(TABLE_NAME)
      .values(STATUS to status.name, LAST_CHECKED_AT to System.currentTimeMillis())
      .where("$URI = ?", uri)
      .run()
  }

  fun getByUri(uri: String): TalerPaymentRecord? =
    readableDatabase
      .select()
      .from(TABLE_NAME)
      .where("$URI = ?", uri)
      .run()
      .readToSingleObject { readRecord(it) }

  fun getForThread(threadId: Long): List<TalerPaymentRecord> =
    readableDatabase
      .select()
      .from(TABLE_NAME)
      .where("$THREAD_ID = ?", threadId)
      .orderBy("$CREATED_AT ASC")
      .run()
      .readToList { readRecord(it) }

  /**
   * URIs in einem nicht-terminalen Zustand - Kandidaten fuers Polling
   * (Schritt 4g/docs/API.md: "Source of Truth ist Taler"). ANGENOMMEN,
   * ABGELAUFEN, UNGUELTIG und LOKAL_ABGELEHNT sind Endzustaende, die sich
   * nicht mehr von selbst aendern.
   */
  fun getNonTerminalUris(): List<String> {
    val nonTerminal = listOf(
      TalerPaymentStatus.OFFEN,
      TalerPaymentStatus.UNBEKANNT_OFFLINE,
      TalerPaymentStatus.TALER_NICHT_VERBUNDEN,
    )
    val placeholders = nonTerminal.joinToString(",") { "?" }
    return readableDatabase
      .select(URI)
      .from(TABLE_NAME)
      .where("$STATUS IN ($placeholders)", *nonTerminal.map { it.name }.toTypedArray())
      .run()
      .readToList { it.requireNonNullString(URI) }
  }
}
