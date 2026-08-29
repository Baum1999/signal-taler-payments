/*
 * V400_AddPaymentHistoryTable.kt - Migration für die Zahlungshistorie-Tabelle
 * 
 * Dies ist die Implementierung der Zahlungshistorie für Signal-Taler-Integration.
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.thoughtcrime.securesms.database.PaymentHistoryTable
import org.thoughtcrime.securesms.database.SQLiteDatabase as SignalSQLiteDatabase

/**
 * Migration für die Erstellung der PaymentHistoryTable.
 * Diese Migration wird automatisch ausgeführt, wenn die App aktualisiert wird.
 */
@Suppress("ClassName")
object V400_AddPaymentHistoryTable : SignalDatabaseMigration {

    override fun migrate(context: Application, db: SignalSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(PaymentHistoryTable.CREATE_TABLE)
        db.execSQL(PaymentHistoryTable.CREATE_INDEX_PAYMENT_ID)
        db.execSQL(PaymentHistoryTable.CREATE_INDEX_CHAT_ID)
        db.execSQL(PaymentHistoryTable.CREATE_INDEX_TIMESTAMP)
        db.execSQL(PaymentHistoryTable.CREATE_INDEX_STATUS)
        db.execSQL(PaymentHistoryTable.CREATE_INDEX_DIRECTION)
    }
}
