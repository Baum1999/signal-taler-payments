package org.thoughtcrime.securesms.database

import android.content.Context
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
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.recipients.Recipient
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
  val consecutiveFailures: Int,
)

/**
 * Kandidat fuers Polling (REVIEW.md B2) - schlanker als [TalerPaymentRecord],
 * enthaelt nur, was [org.thoughtcrime.securesms.taler.TalerPollingCoordinator]
 * fuer die Backoff-Entscheidung braucht.
 */
data class TalerPaymentPollCandidate(
  val uri: String,
  val lastCheckedAt: Long?,
  val consecutiveFailures: Int,
)

/**
 * Ein Vorgang pro Taler-URI, nicht pro Nachricht - doppelt zugestellte,
 * zitierte und weitergeleitete Nachrichten mit derselben URI sind derselbe
 * Vorgang (siehe docs/API.md, Abschnitt "Zustaende"). [uri] ist deshalb der
 * eindeutige Schluessel, nicht die message-ID.
 */
class TalerPaymentTable(context: Context, databaseHelper: SignalDatabase) : DatabaseTable(context, databaseHelper) {

  companion object {
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

    /**
     * Erst mit V323_AddTalerPaymentPollingColumns hinzugekommen - bewusst
     * nicht Teil von [CREATE_TABLE] (siehe Kommentar dort in der Migration).
     */
    const val CONSECUTIVE_FAILURES = "consecutive_failures"

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
      consecutiveFailures = cursor.requireInt(CONSECUTIVE_FAILURES),
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
    // Schuetzt einen lokal per Reject gesetzten LOKAL_ABGELEHNT-Zustand vor
    // dem Ueberschreiben durch einen verspaeteten Refresh (REVIEW.md,
    // Finding 3a) - z.B. wenn ein TalerUriRefreshJob noch unterwegs war, als
    // der Nutzer bereits abgelehnt hat. TalerPaymentStatus.fromTalerStatus()
    // liefert nie LOKAL_ABGELEHNT (das ist ein rein lokaler Zustand), ein
    // Preview-Refresh will diesen Wert also nie legitim setzen - ein
    // bestehendes LOKAL_ABGELEHNT bleibt hier deshalb immer unangetastet.
    if (getByUri(uri)?.status == TalerPaymentStatus.LOKAL_ABGELEHNT) {
      return
    }
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
        // B2 (REVIEW.md): erfolgreicher Abruf setzt den Backoff zurueck.
        CONSECUTIVE_FAILURES to 0,
      )
      .where("$URI = ?", uri)
      .run()
  }

  /**
   * Lokale Statuszeile bei Taler-Statuswechsel (docs/API.md Teil 1 Abschnitt
   * 2 / 2.10) - insertMessageInbox OHNE jobManager.add, es geht keine
   * Nachricht raus. Body traegt den TalerPaymentStatus-Namen, damit
   * MessageRecord.getUpdateDisplayBody() den richtigen Text waehlen kann
   * (siehe MessageTypes.SPECIAL_TYPE_TALER_PAYMENT_UPDATE).
   */
  fun insertLocalStatusLine(threadId: Long, status: TalerPaymentStatus) {
    val message = IncomingMessage(
      type = MessageType.TALER_PAYMENT_UPDATE,
      from = Recipient.self().id,
      sentTimeMillis = System.currentTimeMillis(),
      serverTimeMillis = System.currentTimeMillis(),
      receivedTimeMillis = System.currentTimeMillis(),
      body = status.name,
    )
    SignalDatabase.messages.insertMessageInbox(message, threadId)
  }

  fun updateStatus(uri: String, status: TalerPaymentStatus) {
    // Gleicher Schutz wie in updateFromPreview (REVIEW.md, Finding 3a) - ein
    // bestehendes LOKAL_ABGELEHNT wird nicht ueberschrieben, AUSSER der
    // Aufruf selbst setzt (erneut) LOKAL_ABGELEHNT - ein echter Reject-Klick
    // muss weiterhin funktionieren, nur ein Zurueckdrehen auf einen anderen
    // Zustand (z.B. durch einen verspaeteten TalerReturnActivity-Ruecksprung
    // nach einem bereits erfolgten lokalen Reject) wird verhindert.
    if (status != TalerPaymentStatus.LOKAL_ABGELEHNT && getByUri(uri)?.status == TalerPaymentStatus.LOKAL_ABGELEHNT) {
      return
    }
    writableDatabase
      .update(TABLE_NAME)
      .values(
        STATUS to status.name,
        LAST_CHECKED_AT to System.currentTimeMillis(),
        CONSECUTIVE_FAILURES to 0,
      )
      .where("$URI = ?", uri)
      .run()
  }

  /**
   * Binder-/Transport-Fehler beim Abfragen von [uri] (REVIEW.md B2) -
   * zaehlt hoch fuer den exponentiellen Backoff in
   * [org.thoughtcrime.securesms.taler.TalerPollingCoordinator]. Anders als
   * [updateStatus]/[updateFromPreview] bewusst kein Status-Wechsel - ein
   * transienter Binder-Fehler ist kein neuer bekannter Zustand des Vorgangs.
   */
  fun recordFailure(uri: String) {
    writableDatabase
      .execSQL(
        "UPDATE $TABLE_NAME SET $LAST_CHECKED_AT = ?, $CONSECUTIVE_FAILURES = $CONSECUTIVE_FAILURES + 1 WHERE $URI = ?",
        arrayOf(System.currentTimeMillis(), uri)
      )
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
   * Kandidaten fuers Polling (Schritt 4g/docs/API.md: "Source of Truth ist
   * Taler"; Backoff/Cap: REVIEW.md B2). ANGENOMMEN, ABGELAUFEN, UNGUELTIG und
   * LOKAL_ABGELEHNT sind Endzustaende, die sich nicht mehr von selbst
   * aendern - fuer die ist auch kein TTL noetig, sie werden hier gar nicht
   * erst betrachtet.
   *
   * TTL fuer UNBEKANNT_OFFLINE/TALER_NICHT_VERBUNDEN/NICHT_INSTALLIERT/
   * NICHT_VERTRAUENSWUERDIG: das sind die unsicheren Zustaende (App fehlt,
   * App nicht vertrauenswuerdig, kein Consent, bzw. ein fehlgeschlagener
   * Abruf), die ohne Nutzerinteraktion ewig so bleiben koennen. OFFEN ist
   * ein von Taler bestaetigter, echter Wartezustand (ein offener Dialog/eine
   * offene Purse) - der bleibt ohne TTL im Polling, sonst wuerde eine
   * tagelang liegen gelassene, aber weiterhin gueltige Zahlungsanfrage
   * irgendwann nicht mehr aktualisiert.
   *
   * [limit] plus Sortierung nach am laengsten nicht geprueft zuerst sorgt
   * dafuer, dass bei mehr offenen Vorgaengen als das Limit alle Vorgaenge
   * reihum drankommen, statt dass die ersten N fuer immer bevorzugt werden.
   * Der Backoff selbst (aus [TalerPaymentPollCandidate.consecutiveFailures])
   * wird von [org.thoughtcrime.securesms.taler.TalerPollingCoordinator]
   * angewandt, nicht hier in SQL.
   */
  fun getPollCandidates(limit: Int, ttlCutoffMillis: Long): List<TalerPaymentPollCandidate> {
    val ttlExempt = listOf(TalerPaymentStatus.OFFEN)
    val ttlSubject = listOf(
      TalerPaymentStatus.UNBEKANNT_OFFLINE,
      TalerPaymentStatus.TALER_NICHT_VERBUNDEN,
      TalerPaymentStatus.NICHT_INSTALLIERT,
      TalerPaymentStatus.NICHT_VERTRAUENSWUERDIG,
    )
    val exemptPlaceholders = ttlExempt.joinToString(",") { "?" }
    val subjectPlaceholders = ttlSubject.joinToString(",") { "?" }
    // B3 (REVIEW.md, root-cause 2026-08-15): CAST(? AS INTEGER) ist notwendig,
    // nicht kosmetisch. Der Query-Builder bindet Selection-Args immer als
    // TEXT (Android SupportSQLiteQueryBuilder-API kennt nur String[]).
    // COALESCE(...) traegt - anders als eine nackte Spaltenreferenz - keine
    // Spalten-Affinitaet (SQLite-Doku "Column Affinity", Abschnitt 3.1), also
    // erzwingt die COALESCE-Huelle hier keine numerische Typkonvertierung des
    // gebundenen Strings. Ohne Affinitaet vergleicht SQLite nach Storage-
    // Class, und NUMERIC sortiert dort IMMER unter TEXT - der Vergleich war
    // dadurch fuer jede Zeile unabhaengig vom tatsaechlichen Timestamp false.
    // CAST(? AS INTEGER) gibt dem gebundenen Parameter explizite Affinitaet
    // zurueck. Lokal mit sqlite3 (Python) gegen genau dieses Muster verifiziert.
    val result = readableDatabase
      .select(URI, LAST_CHECKED_AT, CONSECUTIVE_FAILURES)
      .from(TABLE_NAME)
      .where(
        """
        ($STATUS IN ($exemptPlaceholders))
        OR ($STATUS IN ($subjectPlaceholders) AND COALESCE($LAST_CHECKED_AT, $CREATED_AT) >= CAST(? AS INTEGER))
        """,
        *ttlExempt.map { it.name }.toTypedArray(),
        *ttlSubject.map { it.name }.toTypedArray(),
        ttlCutoffMillis,
      )
      .orderBy("COALESCE($LAST_CHECKED_AT, 0) ASC")
      .limit(limit)
      .run()
      .readToList {
        TalerPaymentPollCandidate(
          uri = it.requireNonNullString(URI),
          lastCheckedAt = it.requireLongOrNull(LAST_CHECKED_AT),
          consecutiveFailures = it.requireInt(CONSECUTIVE_FAILURES),
        )
      }
    return result
  }
}
