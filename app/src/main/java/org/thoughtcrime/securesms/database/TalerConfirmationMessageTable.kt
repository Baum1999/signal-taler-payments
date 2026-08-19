package org.thoughtcrime.securesms.database

import android.content.Context
import org.signal.core.util.insertInto
import org.signal.core.util.readToSingleObject
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullString
import org.signal.core.util.select

data class TalerConfirmationMessageRecord(
  val messageId: Long,
  val uri: String,
  val matchedAt: Long,
)

/**
 * Ordnet einer eingehenden Nachricht, die TalerConfirmationDetector als
 * "Zahlung fuer [Kind] akzeptiert" erkannt hat, die passende eigene
 * Taler-URI zu (docs/API.md: der Klartext traegt selbst keine URI). Diese
 * Zuordnung wird einmalig beim Erkennen persistiert, damit die Icon-Farbe
 * (grau/blau, siehe V2ConversationItemTextOnlyViewHolder) auch nach einem
 * App-Neustart aus der DB statt aus einer erneuten (potenziell anderen)
 * Zuordnung gelesen wird.
 */
class TalerConfirmationMessageTable(context: Context, databaseHelper: SignalDatabase) : DatabaseTable(context, databaseHelper) {

  companion object {
    const val TABLE_NAME = "taler_confirmation_message"
    const val ID = "_id"
    const val MESSAGE_ID = "message_id"
    const val URI = "uri"
    const val MATCHED_AT = "matched_at"

    const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY,
        $MESSAGE_ID INTEGER NOT NULL UNIQUE,
        $URI TEXT NOT NULL,
        $MATCHED_AT INTEGER NOT NULL
      )
    """

    const val CREATE_INDEX = "CREATE INDEX IF NOT EXISTS taler_confirmation_message_uri_index ON $TABLE_NAME ($URI)"
  }

  fun insert(messageId: Long, uri: String) {
    writableDatabase
      .insertInto(TABLE_NAME)
      .values(
        MESSAGE_ID to messageId,
        URI to uri,
        MATCHED_AT to System.currentTimeMillis(),
      )
      .run(conflictStrategy = SQLiteDatabase.CONFLICT_IGNORE)
  }

  fun getByMessageId(messageId: Long): TalerConfirmationMessageRecord? =
    readableDatabase
      .select()
      .from(TABLE_NAME)
      .where("$MESSAGE_ID = ?", messageId)
      .run()
      .readToSingleObject {
        TalerConfirmationMessageRecord(
          messageId = it.requireLong(MESSAGE_ID),
          uri = it.requireNonNullString(URI),
          matchedAt = it.requireLong(MATCHED_AT),
        )
      }
}
