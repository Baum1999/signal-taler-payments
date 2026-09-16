/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.thoughtcrime.securesms.database.SQLiteDatabase

/**
 * GNU-Fork (Signal-Taler-Integration): Mit dem Wegfall der App-zu-App-
 * Schnittstelle entstehen die Zustaende NICHT_INSTALLIERT,
 * NICHT_VERTRAUENSWUERDIG und TALER_NICHT_VERBUNDEN nicht mehr - sie sagten
 * etwas ueber die Taler-App aus, nicht ueber die Zahlung. Bestehende Zeilen
 * darauf stehen zu lassen wuerde beim Lesen auf einen unbekannten Enum-Wert
 * laufen; sie werden deshalb auf UNBEKANNT_OFFLINE normalisiert und beim
 * naechsten Poll ohnehin echt aufgeloest.
 */
@Suppress("ClassName")
object V402_NormalizeTalerConnectivityStatuses : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL(
      """
      UPDATE taler_payment
      SET status = 'UNBEKANNT_OFFLINE'
      WHERE status IN ('NICHT_INSTALLIERT', 'NICHT_VERTRAUENSWUERDIG', 'TALER_NICHT_VERBUNDEN')
      """
    )
    // Dieselben drei Faelle gespiegelt in der Zahlungshistorie, die ihren
    // Status als Enum-Namen ablegt und beim Lesen per valueOf() aufloest.
    db.execSQL(
      """
      UPDATE payment_history
      SET status = 'UNKNOWN_OFFLINE'
      WHERE status IN ('NOT_INSTALLED', 'NOT_TRUSTED', 'TALER_NOT_CONNECTED')
      """
    )
  }
}
