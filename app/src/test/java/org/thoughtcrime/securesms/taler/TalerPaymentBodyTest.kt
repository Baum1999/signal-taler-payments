/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import net.taler.wallet.link.TalerHttpsLink
import net.taler.wallet.link.TalerLinkHosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Der Klartext vor den URIs kommt seit 2026-10-11 von Signal selbst
 * (TalerFork_payment_legacy_text), nicht mehr aus Talers talerPaymentData.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TalerPaymentBodyTest {

  private val context = ApplicationProvider.getApplicationContext<Application>()
  private val a = "taler://pay-push/exchange.demo.taler.net/AAA"
  private val b = "taler://pay-push/exchange.demo.taler.net/BBB"
  private val linkA = "https://${TalerLinkHosts.WRAP_HOST}/link#pay-push/exchange.demo.taler.net/AAA"
  private val linkB = "https://${TalerLinkHosts.WRAP_HOST}/link#pay-push/exchange.demo.taler.net/BBB"
  private val en = "This is a GNU Taler link. Update Signal or open the link below with the GNU Taler app to make the payment."

  @Test
  fun `send body is signal text then https links separated by blank lines`() {
    assertEquals("$en\n\n$linkA\n\n$linkB", TalerPaymentBody.send(context, listOf(a, b)))
  }

  @Test
  fun `refund draft prefixes signal text, not a taler supplied text`() {
    assertEquals("Refund: $en\n\n$linkA", TalerPaymentBody.refundDraft(context, listOf(a)))
  }

  @Test
  fun `links in the body unwrap to the original uris`() {
    assertEquals(listOf(a, b), TalerUriDetector.findUris(TalerPaymentBody.send(context, listOf(a, b))).map { TalerHttpsLink.unwrap(it) })
  }

  @Test
  fun `non taler schemes stay raw`() {
    val payto = "payto://iban/DE00"
    assertEquals("$en\n\n$payto", TalerPaymentBody.send(context, listOf(payto)))
  }

  @Test
  fun `preview still recognises the old uri text`() {
    val body = "This is a GNU Taler link. Update Signal or copy the URI below into the GNU Taler app to make the payment.\n\n$a"
    assertTrue(TalerPreviewText.isTalerMessage(context, null, body))
  }

  @Test
  fun `preview recognises the body signal builds itself`() {
    val body = TalerPaymentBody.send(context, listOf(a))
    assertTrue(TalerPreviewText.isTalerMessage(context, null, body))
  }

  @Test
  @Config(qualifiers = "de")
  fun `german body is recognised by the preview too`() {
    val body = TalerPaymentBody.send(context, listOf(a))
    assertFalse(body.startsWith("This is"))
    assertTrue(TalerPreviewText.isTalerMessage(context, null, body))
  }
}
