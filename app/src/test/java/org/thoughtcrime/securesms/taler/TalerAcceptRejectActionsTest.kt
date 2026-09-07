/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.MmsHelper
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.RecipientTestRule

/**
 * Regressionstest fuer "Loeschen einer Taler-Nachricht bricht die
 * Transaktion nicht ab": cancelCancelablePaymentsForDeletedMessages muss den
 * Vorgang SOFORT und rein lokal auf LOKAL_ABGEBROCHEN setzen (kein
 * startActivity() zu Talers eigener Abbrechen-UI mehr, siehe Kommentar an der
 * Funktion) - fuer jede noch offene eigene ausgehende URI der geloeschten
 * Nachricht(en), unabhaengig davon ob Einzelzahlung oder Gruppen-Split.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TalerAcceptRejectActionsTest {

  @get:Rule
  val recipientTestRule = RecipientTestRule()

  private val table get() = SignalDatabase.talerPayments

  @Test
  fun cancelCancelablePaymentsForDeletedMessages_cancelsOwnOpenOutgoingPayment() {
    val uri = "taler://pay-push/exchange.demo.taler.net/deleteCancelA"
    val record = insertOwnMessageWithUri(uri)
    markOwnOpenPayPush(uri, record.threadId)

    TalerAcceptRejectActions.cancelCancelablePaymentsForDeletedMessages(setOf(record))

    assertEquals(TalerPaymentStatus.LOKAL_ABGEBROCHEN, table.getByUri(uri)?.status)
  }

  @Test
  fun cancelCancelablePaymentsForDeletedMessages_cancelsAllOpenUrisOfAGroupSplitMessage() {
    val uriA = "taler://pay-push/exchange.demo.taler.net/deleteCancelB1"
    val uriB = "taler://pay-push/exchange.demo.taler.net/deleteCancelB2"
    val record = insertOwnMessageWithUri("$uriA\n\n$uriB")
    markOwnOpenPayPush(uriA, record.threadId)
    markOwnOpenPayPush(uriB, record.threadId)

    TalerAcceptRejectActions.cancelCancelablePaymentsForDeletedMessages(setOf(record))

    assertEquals(TalerPaymentStatus.LOKAL_ABGEBROCHEN, table.getByUri(uriA)?.status)
    assertEquals(TalerPaymentStatus.LOKAL_ABGEBROCHEN, table.getByUri(uriB)?.status)
  }

  @Test
  fun cancelCancelablePaymentsForDeletedMessages_leavesIncomingPaymentUntouched() {
    val uri = "taler://pay-push/exchange.demo.taler.net/deleteCancelC"
    val record = insertOwnMessageWithUri(uri)
    table.upsertDetected(uri, threadId = record.threadId)
    table.updateFromPreview(
      uri = uri,
      uriKind = "PAY_PUSH",
      status = TalerPaymentStatus.OFFEN,
      amount = "1",
      currency = "KUDOS",
      exchangeBaseUrl = "https://exchange.demo.taler.net/",
      summary = "incoming",
      isOwnPayment = false,
    )

    TalerAcceptRejectActions.cancelCancelablePaymentsForDeletedMessages(setOf(record))

    assertEquals(TalerPaymentStatus.OFFEN, table.getByUri(uri)?.status)
  }

  @Test
  fun cancelCancelablePaymentsForDeletedMessages_leavesAlreadyAcceptedPaymentUntouched() {
    val uri = "taler://pay-push/exchange.demo.taler.net/deleteCancelD"
    val record = insertOwnMessageWithUri(uri)
    table.upsertDetected(uri, threadId = record.threadId)
    table.updateFromPreview(
      uri = uri,
      uriKind = "PAY_PUSH",
      status = TalerPaymentStatus.ANGENOMMEN,
      amount = "1",
      currency = "KUDOS",
      exchangeBaseUrl = "https://exchange.demo.taler.net/",
      summary = "already accepted",
      isOwnPayment = true,
    )

    TalerAcceptRejectActions.cancelCancelablePaymentsForDeletedMessages(setOf(record))

    assertEquals(TalerPaymentStatus.ANGENOMMEN, table.getByUri(uri)?.status)
  }

  private fun insertOwnMessageWithUri(body: String): org.thoughtcrime.securesms.database.model.MessageRecord {
    val recipientId: RecipientId = recipientTestRule.createRecipient("Test Recipient")
    val messageId = MmsHelper.insert(recipient = Recipient.resolved(recipientId), body = body)
    return SignalDatabase.messages.getMessageRecord(messageId)
  }

  private fun markOwnOpenPayPush(uri: String, threadId: Long) {
    table.upsertDetected(uri, threadId = threadId)
    table.updateFromPreview(
      uri = uri,
      uriKind = "PAY_PUSH",
      status = TalerPaymentStatus.OFFEN,
      amount = "1",
      currency = "KUDOS",
      exchangeBaseUrl = "https://exchange.demo.taler.net/",
      summary = "outgoing",
      isOwnPayment = true,
    )
  }
}
