/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.database.TalerPaymentTable

/**
 * GNU-Fork (Signal-Taler-Integration, docs/API.md): ein Vorgang pro
 * Taler-URI, siehe [TalerPaymentTable].
 */
@Suppress("ClassName")
object V322_AddTalerPaymentTable : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL(TalerPaymentTable.CREATE_TABLE)
    db.execSQL(TalerPaymentTable.CREATE_INDEX)
  }
}
