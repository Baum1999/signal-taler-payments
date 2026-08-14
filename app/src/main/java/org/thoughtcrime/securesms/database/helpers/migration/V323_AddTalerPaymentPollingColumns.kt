/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.database.TalerPaymentTable

/**
 * GNU-Fork (Signal-Taler-Integration, REVIEW.md B2): consecutive_failures
 * fuer den exponentiellen Backoff in [org.thoughtcrime.securesms.taler.TalerPollingCoordinator].
 * Eigene Migration statt Nachtragen in V322s CREATE_TABLE - eine
 * Frischinstallation durchlaeuft die gesamte Migrationshistorie inklusive
 * V322, ein nachtraeglich geaendertes CREATE_TABLE wuerde dort mit dem
 * ALTER TABLE hier kollidieren (Spalte existiert schon).
 */
@Suppress("ClassName")
object V323_AddTalerPaymentPollingColumns : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("ALTER TABLE ${TalerPaymentTable.TABLE_NAME} ADD COLUMN ${TalerPaymentTable.CONSECUTIVE_FAILURES} INTEGER NOT NULL DEFAULT 0")
  }
}
