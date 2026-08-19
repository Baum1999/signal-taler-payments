/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.database.TalerPaymentTable

/**
 * GNU-Fork (Signal-Taler-Integration): is_own_payment Spalte fuer die Unterscheidung
 * zwischen eigenen ausgehenden Zahlungen und eingehenden Zahlungsanfragen.
 * Der Wert wird von Taler beim URI-Check bestimmt und an Signal zurueckgegeben.
 * 
 * - true: eigene ausgehende Zahlung (zeige Abbrechen/Refresh Buttons)
 * - false: eingehende Zahlungsanfrage (zeige Akzeptieren/Ablehnen Buttons)
 */
@Suppress("ClassName")
object V324_AddTalerPaymentIsOwnPaymentColumn : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("ALTER TABLE ${TalerPaymentTable.TABLE_NAME} ADD COLUMN ${TalerPaymentTable.IS_OWN_PAYMENT} INTEGER NOT NULL DEFAULT 0")
  }
}
