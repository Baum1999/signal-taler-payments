/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import net.taler.wallet.link.TalerUriKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R

/**
 * TalerConfirmationDetector.match() ist die einzige Huerde zwischen
 * eingehendem Fremdtext und einer automatischen TalerUriRefreshJob-Anstossung
 * (TalerConfirmationTracker) - deckt die Randfaelle ab, bei denen ein exakter
 * Volltext-Match faelschlich zu locker oder zu streng sein koennte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TalerConfirmationDetectorTest {

  private val context = ApplicationProvider.getApplicationContext<Application>()
  private val uri = "taler://pay-push/exchange.demo.taler.net/regressionA"

  @Test
  fun matchesExactConfirmationTextForPayPush() {
    val text = context.getString(
      R.string.TalerFork_accept_confirmation_message,
      context.getString(R.string.TalerFork_kind_pay_push),
      uri,
    )

    val match = TalerConfirmationDetector.match(context, text)

    assertEquals(TalerConfirmationMatch(uri, TalerUriKind.PAY_PUSH), match)
  }

  @Test
  fun matchesExactConfirmationTextForPayPull() {
    val text = context.getString(
      R.string.TalerFork_accept_confirmation_message,
      context.getString(R.string.TalerFork_kind_pay_pull),
      uri,
    )

    val match = TalerConfirmationDetector.match(context, text)

    assertEquals(TalerConfirmationMatch(uri, TalerUriKind.PAY_PULL), match)
  }

  @Test
  fun rejectsTextWithLeadingContentAroundTheTemplate() {
    val expected = context.getString(
      R.string.TalerFork_accept_confirmation_message,
      context.getString(R.string.TalerFork_kind_pay_push),
      uri,
    )

    assertNull(TalerConfirmationDetector.match(context, "Hey, $expected"))
    assertNull(TalerConfirmationDetector.match(context, "$expected!"))
    assertNull(TalerConfirmationDetector.match(context, "$expected\n"))
  }

  @Test
  fun rejectsTextWithNoUri() {
    assertNull(TalerConfirmationDetector.match(context, "Payment for Taler payment link accepted"))
  }

  @Test
  fun rejectsTextWithMultipleUris() {
    val text = context.getString(
      R.string.TalerFork_accept_confirmation_message,
      context.getString(R.string.TalerFork_kind_pay_push),
      "$uri taler://pay-push/exchange.demo.taler.net/regressionB",
    )

    assertNull(TalerConfirmationDetector.match(context, text))
  }

  @Test
  fun rejectsPlainUriWithoutTheConfirmationTemplate() {
    assertNull(TalerConfirmationDetector.match(context, uri))
  }
}
