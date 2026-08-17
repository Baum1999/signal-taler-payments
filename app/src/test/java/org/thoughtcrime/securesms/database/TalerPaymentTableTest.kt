/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.update
import org.thoughtcrime.securesms.taler.TalerPaymentStatus
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.SignalDatabaseRule
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

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  @get:Rule
  val signalDatabaseRule = SignalDatabaseRule()

  private val table get() = SignalDatabase.talerPayments

  /**
   * [SignalDatabaseRule] baut das Schema nur aus den CREATE_TABLE-Konstanten
   * auf (kein Replay der echten Migrationshistorie) - CONSECUTIVE_FAILURES
   * ist bewusst nicht Teil von [TalerPaymentTable.CREATE_TABLE] (siehe
   * Kommentar dort und in V323_AddTalerPaymentPollingColumns), fehlt im
   * Testschema also ohne diesen manuellen Nachtrag. Auf dem echten Geraet
   * lief V323 bereits nachweislich korrekt (siehe REVIEW.md B3-Log-Dumps).
   */
  @Before
  fun applyV323Migration() {
    SignalDatabase.writableDatabase.execSQL(
      "ALTER TABLE ${TalerPaymentTable.TABLE_NAME} ADD COLUMN ${TalerPaymentTable.CONSECUTIVE_FAILURES} INTEGER NOT NULL DEFAULT 0"
    )
  }

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

  private fun setLastCheckedAt(uri: String, timestamp: Long) {
    SignalDatabase.writableDatabase
      .update(TalerPaymentTable.TABLE_NAME)
      .values(TalerPaymentTable.LAST_CHECKED_AT to timestamp)
      .where("${TalerPaymentTable.URI} = ?", uri)
      .run()
  }
}
