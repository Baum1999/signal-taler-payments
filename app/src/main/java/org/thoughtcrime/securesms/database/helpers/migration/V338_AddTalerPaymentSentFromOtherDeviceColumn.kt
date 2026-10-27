/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.database.TalerPaymentTable

/**
 * GNU-Fork (Signal-Taler-Integration): sent_from_other_device markiert eigene
 * Zahlungen, die ein anderes Geraet desselben Kontos gesendet hat (Sync-
 * Transkript). Die Taler-Wallet dieses Geraets hat sie nie erzeugt und kann
 * sie deshalb nicht abbrechen. Bestehende Zeilen bleiben 0: welches Geraet
 * sie gesendet hat, laesst sich nachtraeglich nicht mehr feststellen.
 */
@Suppress("ClassName")
object V338_AddTalerPaymentSentFromOtherDeviceColumn : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("ALTER TABLE ${TalerPaymentTable.TABLE_NAME} ADD COLUMN ${TalerPaymentTable.SENT_FROM_OTHER_DEVICE} INTEGER DEFAULT 0")
  }
}
