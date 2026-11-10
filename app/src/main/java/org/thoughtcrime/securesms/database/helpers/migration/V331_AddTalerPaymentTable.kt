/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.thoughtcrime.securesms.database.SQLiteDatabase

/**
 * GNU-Fork (Signal-Taler-Integration, docs/API.md): ein Vorgang pro
 * Taler-URI, siehe [org.thoughtcrime.securesms.database.TalerPaymentTable].
 *
 * Das Schema ist bewusst eingefroren (Stand dieser Version), nicht
 * TalerPaymentTable.CREATE_TABLE: consecutive_failures, is_own_payment und
 * sent_from_other_device kommen erst mit V332, V333 und V338 dazu. Mit dem
 * aktuellen Schema scheiterte ein Upgrade von Version 330 an V332
 * ("duplicate column name").
 */
@Suppress("ClassName")
object V331_AddTalerPaymentTable : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL(
      """
      CREATE TABLE taler_payment (
        _id INTEGER PRIMARY KEY,
        uri TEXT NOT NULL UNIQUE,
        thread_id INTEGER NOT NULL,
        uri_kind TEXT DEFAULT NULL,
        status TEXT NOT NULL,
        amount TEXT DEFAULT NULL,
        currency TEXT DEFAULT NULL,
        exchange_base_url TEXT DEFAULT NULL,
        summary TEXT DEFAULT NULL,
        created_at INTEGER NOT NULL,
        last_checked_at INTEGER DEFAULT NULL
      )
      """
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS taler_payment_thread_id_index ON taler_payment (thread_id)")
  }
}
