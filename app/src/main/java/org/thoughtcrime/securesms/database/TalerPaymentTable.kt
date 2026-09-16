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
import kotlin.time.Duration.Companion.seconds

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

    /**
     * Gibt an, ob die URI zu einer eigenen ausgehenden Zahlung des Nutzers
     * gehoert (true) oder eine eingehende Zahlungsanfrage von jemand anderem ist (false).
     * Wird von Taler beim URI-Check bestimmt.
     */
    const val IS_OWN_PAYMENT = "is_own_payment"

    /**
     * Rein lokale Endzustaende (nie von Taler selbst geliefert, siehe
     * [TalerPaymentStatus.fromTalerStatus]) - ein Refresh darf sie nie
     * ueberschreiben, siehe [updateStatus]/[updateFromPreview].
     */
    private val LOCAL_TERMINAL_STATUSES = setOf(TalerPaymentStatus.LOKAL_ABGELEHNT, TalerPaymentStatus.LOKAL_ABGEBROCHEN)

    /**
     * Zustaende, bei deren Erst-Erreichen [applyPreviewAndRecordTransition]
     * eine lokale Statuszeile einfuegt - siehe dort.
     */
    private val STATUS_LINE_TERMINAL_STATUSES = setOf(TalerPaymentStatus.ANGENOMMEN, TalerPaymentStatus.ABGELAUFEN)

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
        $LAST_CHECKED_AT INTEGER DEFAULT NULL,
        $CONSECUTIVE_FAILURES INTEGER DEFAULT 0,
        $IS_OWN_PAYMENT INTEGER DEFAULT 0
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
      isOwnPayment = cursor.requireInt(IS_OWN_PAYMENT) != 0,
    )
  }

  /**
   * Legt einen neuen Vorgang an, falls [uri] noch nicht bekannt ist. Gibt
   * true zurueck, wenn tatsaechlich neu angelegt wurde (Aufrufer soll dann
   * einen Refresh-Job enqueuen) - false, wenn der Vorgang schon existierte.
   *
   * [isOwnPayment] kommt vom Aufrufer, weil nur der die Richtung kennt: eine
   * URI im eigenen Sendepfad gehoert zu einer eigenen Zahlung, eine aus einer
   * empfangenen Nachricht nicht. Frueher lieferte Taler diese Unterscheidung
   * per Vorschau mit (OwnUriTracker); ohne die App-zu-App-Schnittstelle ist
   * die Nachrichtenrichtung die einzige verbleibende Quelle dafuer.
   */
  fun upsertDetected(uri: String, threadId: Long, isOwnPayment: Boolean): Boolean {
    val rowId = writableDatabase
      .insertInto(TABLE_NAME)
      .values(
        URI to uri,
        THREAD_ID to threadId,
        STATUS to TalerPaymentStatus.UNBEKANNT_OFFLINE.name,
        IS_OWN_PAYMENT to if (isOwnPayment) 1 else 0,
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
    isOwnPayment: Boolean = false,
  ) {
    // Schuetzt einen lokal per Reject/Cancel gesetzten Zustand (LOKAL_ABGELEHNT/
    // LOKAL_ABGEBROCHEN) vor dem Ueberschreiben durch einen verspaeteten
    // Refresh (REVIEW.md, Finding 3a) - z.B. wenn ein TalerUriRefreshJob noch
    // unterwegs war, als der Nutzer bereits abgelehnt/die Nachricht geloescht
    // hat. TalerPaymentStatus.fromTalerStatus() liefert keinen dieser Werte
    // (rein lokale Zustaende), ein Preview-Refresh will sie also nie legitim
    // setzen - ein bestehender lokaler Endzustand bleibt hier deshalb immer
    // unangetastet.
    if (getByUri(uri)?.status in LOCAL_TERMINAL_STATUSES) {
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
        IS_OWN_PAYMENT to if (isOwnPayment) 1 else 0,
        LAST_CHECKED_AT to System.currentTimeMillis(),
        // B2 (REVIEW.md): erfolgreicher Abruf setzt den Backoff zurueck.
        CONSECUTIVE_FAILURES to 0,
      )
      .where("$URI = ?", uri)
      .run()
  }

  /**
   * Atomarer Ersatz fuer den Aufrufer-seitigen Ablauf
   * "getByUri() lesen -> updateFromPreview() schreiben -> Vorher/Nachher
   * vergleichen -> ggf. insertLocalStatusLine()" in [TalerUriRefreshJob].
   *
   * Bugreport "abgelaufen-Bug": TalerUriRefreshJob las den bisherigen Status
   * VOR dem (mehrere Sekunden dauernden) previewForUri()-Netzwerkaufruf in
   * eine lokale Variable und verglich sie erst danach, unsynchronisiert,
   * gegen den neuen Status. Bug 3 (siehe HANDOFF_bug123_investigation.md)
   * hat bewusst getrennte Job-Queues pro TriggerType eingefuehrt, damit ein
   * RETURN-Trigger (Ruecksprung aus Taler nach Annehmen) nicht hinter einem
   * laufenden ROUTINE-Polling-Job fuer dieselbe URI wartet - genau das
   * erlaubt jetzt aber, dass ein ROUTINE- und ein RETURN-Job fuer dieselbe
   * URI echt parallel laufen. Lasen beide ihren Ausgangsstatus (z.B. OFFEN),
   * bevor der jeweils andere seinen neuen Status geschrieben hatte, erkannte
   * jeder Job unabhaengig "Uebergang in einen Endzustand" - sichtbar als
   * doppelte "Taler-Zahlungslink abgelaufen"-Zeile fuer einen Vorgang, der
   * kurz danach (vom parallel laufenden Job) tatsaechlich als angenommen
   * bestaetigt wurde.
   *
   * SQLiteDatabase serialisiert beginTransaction()/endTransaction() auf
   * derselben Datenbankverbindung (zweiter Aufrufer blockiert, bis der
   * erste committet) - Lesen des bisherigen Status, Schreiben der Preview
   * und die Entscheidung ueber die Statuszeile passieren hier deshalb als
   * eine Einheit. Der (langsame) previewForUri()-Aufruf selbst bleibt
   * ausserhalb dieser Methode und damit unserialisiert - RETURN wird also
   * weiterhin nicht durch einen laufenden ROUTINE-Netzwerkaufruf blockiert.
   *
   * Gibt den bisherigen Status zurueck (oder null, wenn kein bestehender
   * lokaler Endzustand ueberschrieben werden durfte, siehe
   * [LOCAL_TERMINAL_STATUSES]), damit der Aufrufer denselben Wert auch fuer
   * [org.thoughtcrime.securesms.jobs.TalerUriRefreshJob.maybeSendAcceptConfirmation]
   * verwenden kann, statt ihn ein zweites Mal - wieder unsynchronisiert - zu lesen.
   */
  fun applyPreviewAndRecordTransition(
    uri: String,
    uriKind: String?,
    status: TalerPaymentStatus,
    amount: String?,
    currency: String?,
    exchangeBaseUrl: String?,
    summary: String?,
  ): TalerPaymentStatus? {
    val db = writableDatabase
    db.beginTransaction()
    try {
      val previous = getByUri(uri)?.status ?: return null
      if (previous in LOCAL_TERMINAL_STATUSES) {
        db.setTransactionSuccessful()
        return null
      }
      db
        .update(TABLE_NAME)
        .values(
          URI_KIND to uriKind,
          STATUS to status.name,
          AMOUNT to amount,
          CURRENCY to currency,
          EXCHANGE_BASE_URL to exchangeBaseUrl,
          SUMMARY to summary,
          LAST_CHECKED_AT to System.currentTimeMillis(),
          CONSECUTIVE_FAILURES to 0,
        )
        .where("$URI = ?", uri)
        .run()
      if (status in STATUS_LINE_TERMINAL_STATUSES && previous != status) {
        getByUri(uri)?.let { insertLocalStatusLine(it.threadId, status) }
      }
      db.setTransactionSuccessful()
      return previous
    } finally {
      db.endTransaction()
    }
  }

  /**
   * Lokale Statuszeile bei Taler-Statuswechsel (docs/API.md Teil 1 Abschnitt
   * 2 / 2.10) - insertMessageInbox OHNE jobManager.add, es geht keine
   * Nachricht raus. Body traegt den TalerPaymentStatus-Namen, damit
   * MessageRecord.getUpdateDisplayBody() den richtigen Text waehlen kann
   * (siehe MessageTypes.SPECIAL_TYPE_TALER_PAYMENT_UPDATE).
   *
   * expiresIn kommt vom Recipient des Threads, nicht von einem festen Wert -
   * dieselbe Quelle wie bei allen anderen Taler-Systemnachrichten
   * (TalerUriRefreshJob, TalerReturnActivity). Ohne das haette diese
   * Statuszeile die konfigurierte Verschwinde-Frist des Chats ignoriert.
   */
  fun insertLocalStatusLine(threadId: Long, status: TalerPaymentStatus) {
    val recipient = SignalDatabase.threads.getRecipientForThreadId(threadId)
    val message = IncomingMessage(
      type = MessageType.TALER_PAYMENT_UPDATE,
      from = Recipient.self().id,
      sentTimeMillis = System.currentTimeMillis(),
      serverTimeMillis = System.currentTimeMillis(),
      receivedTimeMillis = System.currentTimeMillis(),
      body = status.name,
      expiresIn = (recipient?.expiresInSeconds ?: 0).seconds.inWholeMilliseconds,
    )
    SignalDatabase.messages.insertMessageInbox(message, threadId)
  }

  fun updateStatus(uri: String, status: TalerPaymentStatus) {
    // Gleicher Schutz wie in updateFromPreview (REVIEW.md, Finding 3a) - ein
    // bestehender lokaler Endzustand (LOKAL_ABGELEHNT/LOKAL_ABGEBROCHEN) wird
    // nicht ueberschrieben, AUSSER der Aufruf selbst setzt erneut genau
    // diesen Zustand - ein echter Reject-/Cancel-Aufruf muss weiterhin
    // funktionieren, nur ein Zurueckdrehen auf einen anderen Zustand (z.B.
    // durch einen verspaeteten TalerReturnActivity-Ruecksprung nach einem
    // bereits erfolgten lokalen Reject/Cancel) wird verhindert.
    val existingStatus = getByUri(uri)?.status
    if (status != existingStatus && existingStatus in LOCAL_TERMINAL_STATUSES) {
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
   * Alle bekannten Taler-Vorgaenge ueber alle Threads hinweg - Grundlage fuer
   * [org.thoughtcrime.securesms.payments.history.PaymentHistoryManager.syncFromTalerPayments].
   */
  fun getAll(): List<TalerPaymentRecord> =
    readableDatabase
      .select()
      .from(TABLE_NAME)
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
   * TTL fuer UNBEKANNT_OFFLINE: der Zustand eines noch nie erfolgreichen
   * Abrufs, der ohne Nutzerinteraktion ewig so bleiben kann. OFFEN ist
   * ein vom Exchange bestaetigter, echter Wartezustand (eine offene Purse) -
   * der bleibt ohne TTL im Polling, sonst wuerde eine
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
    val ttlSubject = listOf(TalerPaymentStatus.UNBEKANNT_OFFLINE)
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
