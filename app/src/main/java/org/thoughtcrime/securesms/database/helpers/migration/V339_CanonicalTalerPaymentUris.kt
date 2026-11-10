/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.signal.core.util.readToList
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullString
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.database.TalerPaymentTable

/**
 * GNU-Fork (Signal-Taler-Integration): taler_payment.uri ist seitdem immer
 * die taler://-Form (TalerPaymentTable.canonicalUri). Der eigene Sendepfad hat
 * seit den DD-103-Links im Body (2026-10-27) Zeilen unter
 * https://<host>/link#... angelegt; sie werden auf ihre taler://-URI
 * umgeschrieben. Gibt es die taler://-Zeile schon, bleibt sie stehen und die
 * https-Dublette faellt weg.
 *
 * payment_history schluesselt ueber dieselbe URI (payment_id, meist auch
 * taler_payment_id) und wird genauso umgeschrieben, sonst legte
 * PaymentHistoryManager.syncFromTalerPayments fuer die umgeschriebene Zeile
 * einen zweiten Eintrag an.
 */
@Suppress("ClassName")
object V339_CanonicalTalerPaymentUris : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    for ((id, uri) in httpsRows(db, table = "taler_payment", column = "uri")) {
      val canonical = TalerPaymentTable.canonicalUri(uri)
      if (canonical == uri) continue
      db.execSQL("UPDATE OR IGNORE taler_payment SET uri = ? WHERE _id = ?", arrayOf(canonical, id))
      // Nur wenn das UPDATE an der UNIQUE-Bedingung gescheitert ist, steht hier noch die alte URI.
      db.execSQL("DELETE FROM taler_payment WHERE _id = ? AND uri = ?", arrayOf(id, uri))
    }

    for ((id, paymentId) in httpsRows(db, table = "payment_history", column = "payment_id")) {
      val canonical = TalerPaymentTable.canonicalUri(paymentId)
      if (canonical == paymentId) continue
      db.execSQL(
        """
        UPDATE OR IGNORE payment_history
        SET payment_id = ?, taler_payment_id = CASE WHEN taler_payment_id = payment_id THEN ? ELSE taler_payment_id END
        WHERE _id = ?
        """,
        arrayOf(canonical, canonical, id)
      )
      db.execSQL("DELETE FROM payment_history WHERE _id = ? AND payment_id = ?", arrayOf(id, paymentId))
    }
  }

  private fun httpsRows(db: SQLiteDatabase, table: String, column: String): List<Pair<Long, String>> =
    db.rawQuery("SELECT _id, $column FROM $table WHERE $column LIKE 'https://%'")
      .readToList { it.requireLong("_id") to it.requireNonNullString(column) }
}
