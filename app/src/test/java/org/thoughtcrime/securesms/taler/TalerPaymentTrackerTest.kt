/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.whispersystems.signalservice.internal.push.DataMessage

/**
 * TalerPaymentTracker ist der gemeinsame Erkennungs-/Nachverfolgungspfad fuer
 * Sende- (MessageSender.java) und Empfangsseite (DataMessageProcessor.kt) -
 * deckt ab, dass beide Wege dieselbe talerPayments-Zeile pro URI erzeugen
 * (nicht pro Aufruf dupliziert), und dass trackStructuredPayment die
 * Gruppen-Split-Metadaten (Feld 9000) unveraendert in TalerPaymentMessageTable
 * persistiert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TalerPaymentTrackerTest {

  @get:Rule
  val recipientTestRule = RecipientTestRule()

  @Test
  fun trackUrisInBody_ignoresBlankBody() {
    TalerPaymentTracker.trackUrisInBody(null, threadId = 1)
    TalerPaymentTracker.trackUrisInBody("", threadId = 1)
    TalerPaymentTracker.trackUrisInBody("   ", threadId = 1)
    // Kein Crash ist der eigentliche Test hier - es gibt keine URI, deren
    // Abwesenheit sich sonst pruefen liesse.
  }

  @Test
  fun trackUrisInBody_createsAPaymentRowForANewUri() {
    val uri = "taler://pay-push/exchange.demo.taler.net/trackerA"

    TalerPaymentTracker.trackUrisInBody(uri, threadId = 1)

    val record = SignalDatabase.talerPayments.getByUri(uri)
    assertNotNull(record)
    assertEquals(TalerPaymentStatus.UNBEKANNT_OFFLINE, record?.status)
    assertEquals(1L, record?.threadId)
  }

  @Test
  fun trackUrisInBody_doesNotDuplicateAnAlreadyKnownUri() {
    val uri = "taler://pay-push/exchange.demo.taler.net/trackerB"

    TalerPaymentTracker.trackUrisInBody(uri, threadId = 1)
    SignalDatabase.talerPayments.updateStatus(uri, TalerPaymentStatus.ANGENOMMEN)
    TalerPaymentTracker.trackUrisInBody(uri, threadId = 1)

    // Ein zweiter Aufruf mit derselben URI darf den bereits fortgeschrittenen
    // Status nicht zuruecksetzen (upsertDetected nutzt CONFLICT_IGNORE).
    assertEquals(TalerPaymentStatus.ANGENOMMEN, SignalDatabase.talerPayments.getByUri(uri)?.status)
  }

  @Test
  fun trackStructuredPayment_persistsGroupSplitMetadataAndTracksEveryUri() {
    val uris = listOf(
      "taler://pay-push/exchange.demo.taler.net/trackerC1",
      "taler://pay-push/exchange.demo.taler.net/trackerC2",
    )
    val talerPayment = DataMessage.TalerPayment(
      uris = uris,
      version = 1,
      isGroupSplit = true,
      includeSelf = true,
      totalAmount = "9.00",
    )

    TalerPaymentTracker.trackStructuredPayment(talerPayment, messageId = 42, threadId = 1)

    val stored = SignalDatabase.talerPaymentMessages.getByMessageId(42)
    assertNotNull(stored)
    assertEquals(uris, stored?.uris)
    assertTrue(stored?.isGroupSplit == true)
    assertEquals(true, stored?.includeSelf)
    assertEquals("9.00", stored?.totalAmount)

    uris.forEach { uri -> assertNotNull(SignalDatabase.talerPayments.getByUri(uri)) }
  }

  @Test
  fun trackStructuredPayment_doesNothingForEmptyUriList() {
    val talerPayment = DataMessage.TalerPayment(uris = emptyList(), version = 1)

    TalerPaymentTracker.trackStructuredPayment(talerPayment, messageId = 43, threadId = 1)

    assertNull(SignalDatabase.talerPaymentMessages.getByMessageId(43))
  }
}
