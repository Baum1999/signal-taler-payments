/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.database.TalerConfirmationMessageTable

/**
 * GNU-Fork (Signal-Taler-Integration): Tabelle, die eine eingehende
 * "Zahlung fuer [Kind] akzeptiert"-Nachricht (TalerConfirmationDetector)
 * dauerhaft der zugehoerigen eigenen Taler-URI zuordnet, damit das
 * Bestaetigungs-Icon (grau/blau) auch nach einem App-Neustart korrekt
 * dargestellt werden kann.
 */
@Suppress("ClassName")
object V325_AddTalerConfirmationMessageTable : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL(TalerConfirmationMessageTable.CREATE_TABLE)
    db.execSQL(TalerConfirmationMessageTable.CREATE_INDEX)
  }
}
