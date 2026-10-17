package org.thoughtcrime.securesms.database

import android.content.Context
import org.signal.core.util.insertInto
import org.signal.core.util.readToSingleObject
import org.signal.core.util.requireBoolean
import org.signal.core.util.requireInt
import org.signal.core.util.requireIntOrNull
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullString
import org.signal.core.util.requireString
import org.signal.core.util.select

data class TalerPaymentMessageRecord(
  val messageId: Long,
  val uris: List<String>,
  val version: Int,
  val isGroupSplit: Boolean,
  val includeSelf: Boolean?,
  val totalAmount: String?,
)

/**
 * Strukturierte Zahlungsdaten aus DataMessage.talerPayment (Feld 9000,
 * SignalService.proto), persistiert pro Nachricht statt pro URI - anders als
 * [TalerPaymentTable]. Ersetzt das erneute JSON-Parsen von `body` beim
 * Rendern (TalerPaymentCardPresenter/GroupSplitCard) fuer neu empfangene
 * Nachrichten. Existiert keine Zeile fuer eine Nachricht, faellt der
 * Renderer auf die alte body-Text-Herleitung zurueck (aeltere/fremde
 * Nachrichten ohne dieses Proto-Feld).
 */
class TalerPaymentMessageTable(context: Context, databaseHelper: SignalDatabase) : DatabaseTable(context, databaseHelper) {

  companion object {
    const val TABLE_NAME = "taler_payment_message"
    const val ID = "_id"
    const val MESSAGE_ID = "message_id"
    const val URIS = "uris"
    const val VERSION = "version"
    const val IS_GROUP_SPLIT = "is_group_split"
    const val INCLUDE_SELF = "include_self"
    const val TOTAL_AMOUNT = "total_amount"

    private const val URI_SEPARATOR = "\n"

    const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY,
        $MESSAGE_ID INTEGER NOT NULL UNIQUE,
        $URIS TEXT NOT NULL,
        $VERSION INTEGER NOT NULL,
        $IS_GROUP_SPLIT INTEGER NOT NULL DEFAULT 0,
        $INCLUDE_SELF INTEGER DEFAULT NULL,
        $TOTAL_AMOUNT TEXT DEFAULT NULL
      )
    """

    const val CREATE_INDEX = "CREATE INDEX IF NOT EXISTS taler_payment_message_message_id_index ON $TABLE_NAME ($MESSAGE_ID)"
  }

  fun insert(
    messageId: Long,
    uris: List<String>,
    version: Int,
    isGroupSplit: Boolean,
    includeSelf: Boolean?,
    totalAmount: String?,
  ) {
    writableDatabase
      .insertInto(TABLE_NAME)
      .values(
        MESSAGE_ID to messageId,
        URIS to uris.joinToString(URI_SEPARATOR),
        VERSION to version,
        IS_GROUP_SPLIT to if (isGroupSplit) 1 else 0,
        INCLUDE_SELF to includeSelf?.let { if (it) 1 else 0 },
        TOTAL_AMOUNT to totalAmount,
      )
      .run(conflictStrategy = SQLiteDatabase.CONFLICT_IGNORE)
  }

  fun getByMessageId(messageId: Long): TalerPaymentMessageRecord? =
    readableDatabase
      .select()
      .from(TABLE_NAME)
      .where("$MESSAGE_ID = ?", messageId)
      .run()
      .readToSingleObject {
        TalerPaymentMessageRecord(
          messageId = it.requireLong(MESSAGE_ID),
          uris = it.requireNonNullString(URIS).split(URI_SEPARATOR),
          version = it.requireInt(VERSION),
          isGroupSplit = it.requireBoolean(IS_GROUP_SPLIT),
          includeSelf = it.requireIntOrNull(INCLUDE_SELF)?.let { v -> v != 0 },
          totalAmount = it.requireString(TOTAL_AMOUNT),
        )
      }
}
