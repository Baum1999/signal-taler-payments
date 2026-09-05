/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.database.TalerPaymentMessageTable

/**
 * GNU-Fork (Signal-Taler-Integration): Tabelle fuer strukturierte
 * Taler-Zahlungsdaten aus DataMessage.talerPayment (Feld 9000), persistiert
 * pro Nachricht, damit das Rendern (TalerPaymentCardPresenter/
 * GroupSplitCard) diese Daten nicht mehr aus dem `body`-Text herleiten muss.
 */
@Suppress("ClassName")
object V401_AddTalerPaymentMessageTable : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL(TalerPaymentMessageTable.CREATE_TABLE)
    db.execSQL(TalerPaymentMessageTable.CREATE_INDEX)
  }
}
