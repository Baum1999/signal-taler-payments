/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.testutil.RecipientTestRule

/**
 * TalerQuoteSummary.buildOrNull() ersetzt die rohe(n) URI(s) (inkl.
 * Claim-/Pay-Token) im Zitat - ein Regressionsfehler hier wuerde das Token
 * wieder im Zitat sichtbar machen (Inhaberpapier-Leck) statt der
 * Zusammenfassung.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TalerQuoteSummaryTest {

  @get:Rule
  val recipientTestRule = RecipientTestRule()

  private val context = ApplicationProvider.getApplicationContext<Application>()

  @Test
  fun returnsNullForBodyWithoutUri() {
    assertNull(TalerQuoteSummary.buildOrNull(context, "Hallo, wie geht's?"))
  }

  @Test
  fun summarizesGroupSplitByShareCountWithoutExposingAnyUri() {
    val uris = listOf(
      "taler://pay-push/exchange.demo.taler.net/quoteA1",
      "taler://pay-push/exchange.demo.taler.net/quoteA2",
      "taler://pay-push/exchange.demo.taler.net/quoteA3",
    )
    val body = uris.joinToString("\n\n")

    val summary = TalerQuoteSummary.buildOrNull(context, body)

    assertEquals("Split payment · 3 shares", summary)
    uris.forEach { uri -> assertFalse(summary!!.contains(uri)) }
  }

  @Test
  fun showsPendingStatusForUnknownSingleUri() {
    val uri = "taler://pay-push/exchange.demo.taler.net/quoteB"

    val summary = TalerQuoteSummary.buildOrNull(context, uri)

    assertEquals("Checking status…", summary)
    assertFalse(summary!!.contains(uri))
  }

  @Test
  fun showsAmountAndStatusForKnownSingleUriWithoutExposingTheUri() {
    val uri = "taler://pay-push/exchange.demo.taler.net/quoteC"
    SignalDatabase.talerPayments.upsertDetected(uri, threadId = 1)
    SignalDatabase.talerPayments.updateFromPreview(
      uri = uri,
      uriKind = "PAY_PUSH",
      status = TalerPaymentStatus.ANGENOMMEN,
      amount = "4.20",
      currency = "KUDOS",
      exchangeBaseUrl = "https://exchange.demo.taler.net/",
      summary = "test",
    )

    val summary = TalerQuoteSummary.buildOrNull(context, uri)

    assertEquals("4,20 KUDOS · Angenommen", summary)
    assertFalse(summary!!.contains(uri))
  }
}
