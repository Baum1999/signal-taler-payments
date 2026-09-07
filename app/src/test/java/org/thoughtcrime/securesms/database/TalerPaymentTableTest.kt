/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.readToList
import org.signal.core.util.requireLong
import org.signal.core.util.requireString
import org.signal.core.util.select
import org.signal.core.util.update
import org.thoughtcrime.securesms.taler.TalerPaymentStatus
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import java.util.concurrent.TimeUnit

/**
 * Regressionstest fuer REVIEW.md B3: `COALESCE($LAST_CHECKED_AT, $CREATED_AT) >= ?`
 * verglich eine INTEGER-Spalte gegen einen als TEXT gebundenen Parameter
 * (Android SupportSQLiteQueryBuilder kennt nur String[]-Selection-Args).
 * COALESCE(...) traegt - anders als eine nackte Spaltenreferenz - keine
 * Spalten-Affinitaet (SQLite-Doku "Column Affinity", Abschnitt 3.1), also
 * erzwang die COALESCE-Huelle keine numerische Typkonvertierung des
 * gebundenen Strings. Ohne Affinitaet vergleicht SQLite nach Storage-Class,
 * und NUMERIC sortiert dort immer unter TEXT - der Vergleich war dadurch fuer
 * jede Zeile unabhaengig vom tatsaechlichen Timestamp false. Vor dem Fix
 * (CAST(? AS INTEGER) in TalerPaymentTable.getPollCandidates) schlaegt
 * [getPollCandidates_includesRecentTtlSubjectRow] fehl (0 statt 1 Kandidat).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TalerPaymentTableTest {

  // insertLocalStatusLine_insertsAnUpdateMessageInTheGivenThread (u.a. via
  // insertMessageInbox -> ThreadTable.updateForMessageInsert -> getConversationSnippet)
  // braucht ein echtes, aufloesbares Recipient.self() - mit den vorherigen
  // Einzel-Rules MockAppDependenciesRule/SignalDatabaseRule ist
  // AppDependencies.recipientCache ein voll-relaxter mockk-Mock, dessen
  // getSelf().id.serialize() unbeschaltet "" statt einer echten Recipient-ID
  // liefert - FROM_RECIPIENT_ID landet dann als "" in der DB und der
  // Rueckgelesen-Cursor stolpert beim requireLong() darueber
  // (NumberFormatException). RecipientTestRule verkabelt statt des Mocks
  // eine echte LiveRecipientCache-Instanz plus gemocktem SignalStore.account
  // (aci/e164) und legt "self" vorab in der Recipients-Tabelle an - genau das
  // Muster, das der Rest der Codebase fuer Tests mit echten
  // Recipient-Inserts verwendet (siehe RecipientTestRule-Doc-Kommentar). Sie
  // kapselt SignalDatabaseRule/MockAppDependenciesRule bereits intern.
  @get:Rule
  val recipientTestRule = RecipientTestRule()

  private val table get() = SignalDatabase.talerPayments

  @Test
  fun getPollCandidates_includesRecentTtlSubjectRow() {
    val uri = "taler://pay-push/exchange.demo.taler.net/regressionA"
    table.upsertDetected(uri, threadId = 1)
    table.updateStatus(uri, TalerPaymentStatus.UNBEKANNT_OFFLINE)

    val candidates = table.getPollCandidates(limit = 200, ttlCutoffMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24))

    assertEquals(1, candidates.size)
    assertEquals(uri, candidates[0].uri)
  }

  @Test
  fun getPollCandidates_excludesStaleTtlSubjectRow() {
    val uri = "taler://pay-push/exchange.demo.taler.net/regressionB"
    table.upsertDetected(uri, threadId = 1)
    table.updateStatus(uri, TalerPaymentStatus.TALER_NICHT_VERBUNDEN)
    setLastCheckedAt(uri, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))

    val candidates = table.getPollCandidates(limit = 200, ttlCutoffMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24))

    assertTrue(candidates.isEmpty())
  }

  @Test
  fun getPollCandidates_includesOffenRegardlessOfAge() {
    val uri = "taler://pay-push/exchange.demo.taler.net/regressionC"
    table.upsertDetected(uri, threadId = 1)
    table.updateStatus(uri, TalerPaymentStatus.OFFEN)
    setLastCheckedAt(uri, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))

    val candidates = table.getPollCandidates(limit = 200, ttlCutoffMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24))

    assertEquals(1, candidates.size)
  }

  @Test
  fun getPollCandidates_excludesTerminalStatus() {
    val uri = "taler://pay-push/exchange.demo.taler.net/regressionD"
    table.upsertDetected(uri, threadId = 1)
    table.updateStatus(uri, TalerPaymentStatus.ANGENOMMEN)

    val candidates = table.getPollCandidates(limit = 200, ttlCutoffMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24))

    assertTrue(candidates.isEmpty())
  }

  @Test
  fun insertLocalStatusLine_insertsAnUpdateMessageInTheGivenThread() {
    val uri = "taler://pay-push/exchange.demo.taler.net/regressionE"
    table.upsertDetected(uri, threadId = 1)

    table.insertLocalStatusLine(threadId = 1, status = TalerPaymentStatus.ANGENOMMEN)

    val rows = SignalDatabase.writableDatabase
      .select(MessageTable.TYPE, MessageTable.BODY)
      .from(MessageTable.TABLE_NAME)
      .where("${MessageTable.THREAD_ID} = ?", 1)
      .run()
      .readToList { it.requireLong(MessageTable.TYPE) to it.requireString(MessageTable.BODY) }

    val statusLine = rows.singleOrNull { (type, _) -> (type and MessageTypes.SPECIAL_TYPES_MASK) == MessageTypes.SPECIAL_TYPE_TALER_PAYMENT_UPDATE }
    assertEquals("ANGENOMMEN", statusLine?.second)
  }

  /**
   * REVIEW.md Finding 3a: updateStatus() darf ein bestehendes
   * LOKAL_ABGELEHNT nicht ueberschreiben - Regressionsschutz fuer genau die
   * im Review beschriebene Sequenz (Annehmen angestossen, dann in Signal
   * Ablehnen, dann ein verspaeteter Ruecksprung/Refresh, der versucht, den
   * Status auf OFFEN zurueckzudrehen).
   */
  @Test
  fun updateStatus_doesNotOverwriteExistingLokalAbgelehnt() {
    val uri = "taler://pay-push/exchange.demo.taler.net/regressionF"
    table.upsertDetected(uri, threadId = 1)
    table.updateStatus(uri, TalerPaymentStatus.LOKAL_ABGELEHNT)

    table.updateStatus(uri, TalerPaymentStatus.OFFEN)

    assertEquals(TalerPaymentStatus.LOKAL_ABGELEHNT, table.getByUri(uri)?.status)
  }

  /**
   * Ein echter, erneuter Reject-Aufruf (status selbst == LOKAL_ABGELEHNT)
   * muss weiterhin funktionieren - der Guard darf keine Idempotenz brechen.
   */
  @Test
  fun updateStatus_allowsReapplyingLokalAbgelehnt() {
    val uri = "taler://pay-push/exchange.demo.taler.net/regressionG"
    table.upsertDetected(uri, threadId = 1)
    table.updateStatus(uri, TalerPaymentStatus.LOKAL_ABGELEHNT)

    table.updateStatus(uri, TalerPaymentStatus.LOKAL_ABGELEHNT)

    assertEquals(TalerPaymentStatus.LOKAL_ABGELEHNT, table.getByUri(uri)?.status)
  }

  /**
   * REVIEW.md Finding 3a: updateFromPreview() (der TalerUriRefreshJob-Pfad)
   * darf ein bestehendes LOKAL_ABGELEHNT ebenfalls nicht ueberschreiben -
   * TalerPaymentStatus.fromTalerStatus() liefert diesen Wert nie, ein
   * Preview-Refresh will ihn also nie legitim setzen.
   */
  @Test
  fun updateFromPreview_doesNotOverwriteExistingLokalAbgelehnt() {
    val uri = "taler://pay-push/exchange.demo.taler.net/regressionH"
    table.upsertDetected(uri, threadId = 1)
    table.updateStatus(uri, TalerPaymentStatus.LOKAL_ABGELEHNT)

    table.updateFromPreview(
      uri = uri,
      uriKind = "PAY_PUSH",
      status = TalerPaymentStatus.OFFEN,
      amount = "1",
      currency = "KUDOS",
      exchangeBaseUrl = "https://exchange.demo.taler.net/",
      summary = "resurrected",
    )

    assertEquals(TalerPaymentStatus.LOKAL_ABGELEHNT, table.getByUri(uri)?.status)
  }

  /**
   * REVIEW.md Finding 2: die lokale Statuszeile beschreibt eine bereits vom
   * Nutzer selbst getroffene Aktion - sie soll deshalb nicht ungelesen
   * landen (Badge-Zaehler) und nicht benachrichtigen (MessageTable.READ = 0
   * ist die gemeinsame Voraussetzung fuer beides, siehe getUnreadCount()
   * und die NOTIFIED-Abfragen in MessageTable). Vor dem Fix (insertMessageInbox
   * las TALER_PAYMENT_UPDATE nicht in der `read`-Bedingung) landete diese
   * Zeile mit READ = 0.
   */
  @Test
  fun insertLocalStatusLine_insertsAnAlreadyReadMessage() {
    val uri = "taler://pay-push/exchange.demo.taler.net/regressionI"
    table.upsertDetected(uri, threadId = 1)

    table.insertLocalStatusLine(threadId = 1, status = TalerPaymentStatus.LOKAL_ABGELEHNT)

    val readValues = SignalDatabase.writableDatabase
      .select(MessageTable.READ)
      .from(MessageTable.TABLE_NAME)
      .where("${MessageTable.THREAD_ID} = ?", 1)
      .run()
      .readToList { it.requireLong(MessageTable.READ) }

    assertEquals(1, readValues.size)
    assertEquals(1L, readValues.single())
  }

  /**
   * Gleicher Schutz wie updateStatus_doesNotOverwriteExistingLokalAbgelehnt,
   * jetzt fuer den zweiten lokalen Endzustand LOKAL_ABGEBROCHEN (Loeschen
   * einer Taler-Nachricht, TalerAcceptRejectActions.cancelCancelablePaymentsForDeletedMessages) -
   * ein verspaeteter Refresh darf einen bereits lokal abgebrochenen Vorgang
   * nicht wieder auf OFFEN zuruecksetzen.
   */
  @Test
  fun updateStatus_doesNotOverwriteExistingLokalAbgebrochen() {
    val uri = "taler://pay-push/exchange.demo.taler.net/regressionJ"
    table.upsertDetected(uri, threadId = 1)
    table.updateStatus(uri, TalerPaymentStatus.LOKAL_ABGEBROCHEN)

    table.updateStatus(uri, TalerPaymentStatus.OFFEN)

    assertEquals(TalerPaymentStatus.LOKAL_ABGEBROCHEN, table.getByUri(uri)?.status)
  }

  @Test
  fun updateFromPreview_doesNotOverwriteExistingLokalAbgebrochen() {
    val uri = "taler://pay-push/exchange.demo.taler.net/regressionK"
    table.upsertDetected(uri, threadId = 1)
    table.updateStatus(uri, TalerPaymentStatus.LOKAL_ABGEBROCHEN)

    table.updateFromPreview(
      uri = uri,
      uriKind = "PAY_PUSH",
      status = TalerPaymentStatus.OFFEN,
      amount = "1",
      currency = "KUDOS",
      exchangeBaseUrl = "https://exchange.demo.taler.net/",
      summary = "resurrected",
    )

    assertEquals(TalerPaymentStatus.LOKAL_ABGEBROCHEN, table.getByUri(uri)?.status)
  }

  private fun setLastCheckedAt(uri: String, timestamp: Long) {
    SignalDatabase.writableDatabase
      .update(TalerPaymentTable.TABLE_NAME)
      .values(TalerPaymentTable.LAST_CHECKED_AT to timestamp)
      .where("${TalerPaymentTable.URI} = ?", uri)
      .run()
  }
}
