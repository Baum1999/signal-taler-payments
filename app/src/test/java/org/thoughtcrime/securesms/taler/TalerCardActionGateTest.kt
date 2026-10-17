/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import net.taler.wallet.link.TalerUriKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thoughtcrime.securesms.database.TalerPaymentRecord

/**
 * TalerCardActionGate ist die einzige Wahrheitstabelle fuer Annehmen/
 * Ablehnen/Abbrechen/Aktualisieren/Rueckerstatten - sowohl auf der
 * Einzel-URI-Karte als auch im Gruppen-Split (GroupSplitCard.resolveCancelableUris/
 * resolveRefundableUris) und im Long-Press-Menue (TalerMenuState). Ein Fehler
 * hier wirkt sich auf alle drei Stellen gleichzeitig aus - deshalb hier direkt
 * an der Quelle statt nur indirekt ueber diese Konsumenten getestet.
 */
class TalerCardActionGateTest {

  private fun record(
    status: TalerPaymentStatus = TalerPaymentStatus.OFFEN,
    uriKind: String? = TalerUriKind.PAY_PUSH.name,
    isOwnPayment: Boolean = false,
  ) = TalerPaymentRecord(
    uri = "taler://pay-push/exchange.demo.taler.net/gateTest",
    threadId = 1,
    uriKind = uriKind,
    status = status,
    amount = "1",
    currency = "KUDOS",
    exchangeBaseUrl = "https://exchange.demo.taler.net/",
    summary = null,
    createdAt = 0,
    lastCheckedAt = null,
    consecutiveFailures = 0,
    isOwnPayment = isOwnPayment,
  )

  // --- showAccept/showReject: eingehende, offene PAY_PUSH-Anfrage -----------

  @Test
  fun acceptAndReject_visibleForIncomingOpenPayPush() {
    val r = record(status = TalerPaymentStatus.OFFEN, uriKind = TalerUriKind.PAY_PUSH.name, isOwnPayment = false)
    assertTrue(TalerCardActionGate.showAccept(r))
    assertTrue(TalerCardActionGate.showReject(r))
  }

  @Test
  fun acceptAndReject_hiddenForOwnPayment() {
    val r = record(status = TalerPaymentStatus.OFFEN, uriKind = TalerUriKind.PAY_PUSH.name, isOwnPayment = true)
    assertFalse(TalerCardActionGate.showAccept(r))
    assertFalse(TalerCardActionGate.showReject(r))
  }

  @Test
  fun acceptAndReject_hiddenWhenNotOpen() {
    val r = record(status = TalerPaymentStatus.ANGENOMMEN, uriKind = TalerUriKind.PAY_PUSH.name, isOwnPayment = false)
    assertFalse(TalerCardActionGate.showAccept(r))
    assertFalse(TalerCardActionGate.showReject(r))
  }

  @Test
  fun acceptAndReject_hiddenForNonPayPushKind() {
    val r = record(status = TalerPaymentStatus.OFFEN, uriKind = TalerUriKind.PAY_PULL.name, isOwnPayment = false)
    assertFalse(TalerCardActionGate.showAccept(r))
    assertFalse(TalerCardActionGate.showReject(r))
  }

  @Test
  fun acceptAndReject_hiddenForNullRecord() {
    assertFalse(TalerCardActionGate.showAccept(null))
    assertFalse(TalerCardActionGate.showReject(null))
  }

  // --- showCancel/showRefresh: eigene, offene ausgehende PAY_PUSH-Zahlung ---

  @Test
  fun cancelAndRefresh_visibleForOwnOpenOutgoingPayPush() {
    val r = record(status = TalerPaymentStatus.OFFEN, uriKind = TalerUriKind.PAY_PUSH.name, isOwnPayment = true)
    assertTrue(TalerCardActionGate.showCancel(r))
    assertTrue(TalerCardActionGate.showRefresh(r))
  }

  @Test
  fun cancelAndRefresh_hiddenForIncomingPayment() {
    val r = record(status = TalerPaymentStatus.OFFEN, uriKind = TalerUriKind.PAY_PUSH.name, isOwnPayment = false)
    assertFalse(TalerCardActionGate.showCancel(r))
    assertFalse(TalerCardActionGate.showRefresh(r))
  }

  @Test
  fun cancelAndRefresh_hiddenForLocallyCancelledPayment() {
    val r = record(status = TalerPaymentStatus.LOKAL_ABGEBROCHEN, uriKind = TalerUriKind.PAY_PUSH.name, isOwnPayment = true)
    assertFalse(TalerCardActionGate.showCancel(r))
    assertFalse(TalerCardActionGate.showRefresh(r))
  }

  @Test
  fun cancelAndRefresh_hiddenForNullRecord() {
    assertFalse(TalerCardActionGate.showCancel(null))
    assertFalse(TalerCardActionGate.showRefresh(null))
  }

  // --- showRefund: von der Gegenseite bereits angenommene Zahlung -----------

  @Test
  fun refund_visibleForAcceptedIncomingPayment() {
    val r = record(status = TalerPaymentStatus.ANGENOMMEN, isOwnPayment = false)
    assertTrue(TalerCardActionGate.showRefund(r))
  }

  @Test
  fun refund_hiddenForOwnPayment() {
    val r = record(status = TalerPaymentStatus.ANGENOMMEN, isOwnPayment = true)
    assertFalse(TalerCardActionGate.showRefund(r))
  }

  @Test
  fun refund_hiddenWhenNotAccepted() {
    val r = record(status = TalerPaymentStatus.OFFEN, isOwnPayment = false)
    assertFalse(TalerCardActionGate.showRefund(r))
  }

  @Test
  fun refund_hiddenForNullRecord() {
    assertFalse(TalerCardActionGate.showRefund(null))
  }
}
