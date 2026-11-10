/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import net.taler.wallet.link.TalerHttpsLink
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.insertInto
import org.signal.core.util.readToList
import org.signal.core.util.readToSingleObject
import org.signal.core.util.requireInt
import org.signal.core.util.requireNonNullString
import org.signal.core.util.select
import org.thoughtcrime.securesms.testutil.SignalDatabaseMigrationRule

@Suppress("ClassName")
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class V339_CanonicalTalerPaymentUrisTest {

  @get:Rule val signalDatabaseRule = SignalDatabaseMigrationRule(338)

  private val db get() = signalDatabaseRule.database

  @Test
  fun migrate_rewritesHttpsLinksToTalerUris() {
    val uri = "taler://pay-push/exchange.demo.taler.net/migrationA"
    insertPayment(TalerHttpsLink.wrap(uri), status = "OFFEN", isOwnPayment = 1)

    migrate()

    assertThat(allUris()).containsExactlyInAnyOrder(uri)
    assertThat(statusOf(uri)).isEqualTo("OFFEN")
    assertThat(isOwnPaymentOf(uri)).isEqualTo(1)
  }

  @Test
  fun migrate_leavesTalerUrisUnchanged() {
    val uri = "taler://pay-pull/exchange.demo.taler.net/migrationB"
    insertPayment(uri, status = "ANGENOMMEN", isOwnPayment = 0)

    migrate()

    assertThat(allUris()).containsExactlyInAnyOrder(uri)
    assertThat(statusOf(uri)).isEqualTo("ANGENOMMEN")
  }

  @Test
  fun migrate_dropsTheHttpsDuplicateOfAnExistingTalerUri() {
    val uri = "taler://pay-push/exchange.demo.taler.net/migrationC"
    insertPayment(uri, status = "ANGENOMMEN", isOwnPayment = 0)
    insertPayment(TalerHttpsLink.wrap(uri), status = "UNBEKANNT_OFFLINE", isOwnPayment = 1)

    migrate()

    assertThat(allUris()).containsExactlyInAnyOrder(uri)
    assertThat(statusOf(uri)).isEqualTo("ANGENOMMEN")
  }

  @Test
  fun migrate_leavesOtherHttpsUrisUnchanged() {
    val other = "https://example.org/link#pay-push/exchange.demo.taler.net/migrationD"
    insertPayment(other, status = "UNBEKANNT_OFFLINE", isOwnPayment = 0)

    migrate()

    assertThat(allUris()).containsExactlyInAnyOrder(other)
  }

  /**
   * payment_history schluesselt ueber dieselbe URI (PaymentHistoryManager.syncFromTalerPayments
   * sucht per talerPayment.uri). Bliebe die https-Form stehen, legte der naechste
   * Sync fuer die umgeschriebene taler_payment-Zeile einen zweiten Eintrag an.
   */
  @Test
  fun migrate_rewritesPaymentHistoryIdsToTalerUris() {
    val uri = "taler://pay-push/exchange.demo.taler.net/migrationE"
    insertHistory(TalerHttpsLink.wrap(uri))

    migrate()

    val ids = db.select("payment_id", "taler_payment_id").from("payment_history").run()
      .readToList { it.requireNonNullString("payment_id") to it.requireNonNullString("taler_payment_id") }
    assertThat(ids).containsExactlyInAnyOrder(uri to uri)
  }

  @Test
  fun migrate_dropsTheHttpsDuplicateInPaymentHistory() {
    val uri = "taler://pay-push/exchange.demo.taler.net/migrationF"
    insertHistory(uri)
    insertHistory(TalerHttpsLink.wrap(uri))

    migrate()

    val ids = db.select("payment_id").from("payment_history").run().readToList { it.requireNonNullString("payment_id") }
    assertThat(ids).containsExactlyInAnyOrder(uri)
  }

  private fun insertHistory(paymentId: String) {
    db.insertInto("payment_history")
      .values(
        "payment_id" to paymentId,
        "amount" to "0.6",
        "currency" to "KUDOS",
        "direction" to "OUTGOING",
        "sender_id" to "1",
        "sender_name" to "Ich",
        "recipient_id" to "2",
        "recipient_name" to "Gruppe",
        "status" to "PENDING",
        "timestamp" to 1000L,
        "taler_payment_id" to paymentId,
        "created_at" to 1000L,
      )
      .run()
  }

  private fun migrate() {
    V339_CanonicalTalerPaymentUris.migrate(ApplicationProvider.getApplicationContext(), db, 338, 339)
  }

  private fun insertPayment(uri: String, status: String, isOwnPayment: Int) {
    db.insertInto("taler_payment")
      .values(
        "uri" to uri,
        "thread_id" to 1L,
        "status" to status,
        "is_own_payment" to isOwnPayment,
        "created_at" to 1000L,
      )
      .run()
  }

  private fun allUris(): List<String> =
    db.select("uri").from("taler_payment").run().readToList { it.requireNonNullString("uri") }

  private fun statusOf(uri: String): String? =
    db.select("status").from("taler_payment").where("uri = ?", uri).run().readToSingleObject { it.requireNonNullString("status") }

  private fun isOwnPaymentOf(uri: String): Int? =
    db.select("is_own_payment").from("taler_payment").where("uri = ?", uri).run().readToSingleObject { it.requireInt("is_own_payment") }
}
