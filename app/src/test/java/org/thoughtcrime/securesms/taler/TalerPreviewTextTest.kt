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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R

/**
 * Spiegel der Review-Focus-Faelle aus dem Plan
 * 2026-10-02-taler-preview-chatlist-notification.md: Die Vorschau darf die
 * rohe URI aus Chatliste und Banner entfernen, aber niemals nutzergeschriebenen
 * Text um eine URI verschlucken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TalerPreviewTextTest {

  private val context = ApplicationProvider.getApplicationContext<Application>()
  private val payPushUri = "taler://pay-push/exchange.demo.taler.net/regressionA"
  private val payPullUri = "taler://pay-pull/exchange.demo.taler.net/regressionB"
  private val paytoUri = "payto://iban/DE89370400440532013000"

  private fun sendBody(uri: String = payPushUri): String = context.getString(
    R.string.TalerFork_send_message,
    context.getString(R.string.TalerFork_kind_pay_push),
    uri
  )

  private fun requestBody(uri: String = payPullUri): String = context.getString(
    R.string.TalerFork_request_message,
    context.getString(R.string.TalerFork_kind_pay_pull),
    uri
  )

  private fun confirmationBody(uri: String = payPushUri): String = context.getString(
    R.string.TalerFork_accept_confirmation_message,
    context.getString(R.string.TalerFork_kind_pay_push),
    uri
  )

  private fun legacyBody(uris: List<String>): String =
    "This is a GNU Taler link. Update Signal or copy the URI below into the GNU Taler app to make the payment." +
      "\n\n" + uris.joinToString("\n\n")

  // Review Focus 2: fremder Client ohne Feld 9000, exaktes send-Format

  @Test
  fun sendFormatWithoutPayloadYieldsPayPushLabel() {
    assertEquals(R.string.TalerFork_kind_pay_push, TalerPreviewText.labelRes(context, null, sendBody()))
    assertTrue(TalerPreviewText.isTalerMessage(context, null, sendBody()))
  }

  @Test
  fun requestFormatWithoutPayloadYieldsPayPullLabel() {
    assertEquals(R.string.TalerFork_kind_pay_pull, TalerPreviewText.labelRes(context, null, requestBody()))
  }

  @Test
  fun confirmationFormatYieldsAcceptedStatusLabel() {
    assertEquals(R.string.TalerFork_update_accepted, TalerPreviewText.labelRes(context, null, confirmationBody()))
  }

  // Review Focus 3: Gruppen-Split mit und ohne Payload

  @Test
  fun groupSplitWithPayloadYieldsGroupSplitLabel() {
    val payload = TalerPaymentPayload(
      uris = listOf(payPushUri, payPullUri, "taler://pay-push/exchange.demo.taler.net/regressionC"),
      isGroupSplit = true
    )

    assertEquals(R.string.TalerFork_preview_group_split, TalerPreviewText.labelRes(context, payload, legacyBody(payload.uris)))
  }

  @Test
  fun groupSplitWithoutPayloadYieldsGroupSplitLabel() {
    assertEquals(
      R.string.TalerFork_preview_group_split,
      TalerPreviewText.labelRes(context, null, legacyBody(listOf(payPushUri, payPullUri, "taler://pay-push/exchange.demo.taler.net/regressionC")))
    )
  }

  // Review Focus 1: nutzergeschriebener Text mit URI bleibt sichtbar

  @Test
  fun userTextWithUriIsNotATalerMessage() {
    assertNull(TalerPreviewText.labelRes(context, null, "Schau mal, Geld für dich: $payPushUri"))
    assertNull(TalerPreviewText.labelRes(context, null, "Hey, look at this: $payPushUri"))
    assertFalse(TalerPreviewText.isTalerMessage(context, null, "payment: $payPushUri, please"))
  }

  @Test
  fun plainTextAndEmptyBodyAreNotTalerMessages() {
    assertNull(TalerPreviewText.labelRes(context, null, "Hello"))
    assertNull(TalerPreviewText.labelRes(context, null, ""))
    assertNull(TalerPreviewText.labelRes(context, null, "  \n  "))
  }

  // Review Focus 8: payto:// wird erkannt, aber nicht klassifiziert

  @Test
  fun paytoUriWithoutPayloadYieldsUnknownLabel() {
    assertEquals(R.string.TalerFork_kind_unknown, TalerPreviewText.labelRes(context, null, paytoUri))
  }

  @Test
  fun payloadWithUnclassifiableUriYieldsUnknownLabel() {
    assertEquals(R.string.TalerFork_kind_unknown, TalerPreviewText.labelRes(context, TalerPaymentPayload(uris = listOf(paytoUri)), "irgendwas"))
  }

  // Regel 1: Payload ist entscheidend, Body ist dann egal

  @Test
  fun payloadWinsOverForeignBody() {
    val payload = TalerPaymentPayload(uris = listOf(payPullUri))

    assertEquals(R.string.TalerFork_kind_pay_pull, TalerPreviewText.labelRes(context, payload, "fremder, unpassender Text"))
  }

  @Test
  fun payloadWithEmptyUrisFallsBackToBodyHeuristic() {
    assertEquals(R.string.TalerFork_kind_pay_push, TalerPreviewText.labelRes(context, TalerPaymentPayload(uris = emptyList()), sendBody()))
    assertNull(TalerPreviewText.labelRes(context, TalerPaymentPayload(uris = emptyList()), "Hello"))
  }

  // Review Focus 5: Notiz an mich - gleiche Behandlung wie 1:1

  @Test
  fun selfChatPaymentBehavesLikeAnyOtherPayment() {
    assertEquals(R.string.TalerFork_kind_pay_push, TalerPreviewText.labelRes(context, null, sendBody()))
  }

  // Aeltere Fork-Versionen: TalerPaymentData als JSON im Body

  @Test
  fun legacyJsonBodyYieldsLabel() {
    val json = """{"legacyText":"This is a GNU Taler link.","version":1,"uri":["$payPushUri"]}"""

    assertEquals(R.string.TalerFork_kind_pay_push, TalerPreviewText.labelRes(context, null, json))
  }

  @Test
  fun legacyJsonBodyWithMultipleUrisYieldsGroupSplitLabel() {
    val json = """{"legacyText":"This is a GNU Taler link.","version":1,"uri":["$payPushUri","$payPullUri"]}"""

    assertEquals(R.string.TalerFork_preview_group_split, TalerPreviewText.labelRes(context, null, json))
  }

  // Normalisierung: zusammengefallene Leerzeichen stoeren nicht

  @Test
  fun whitespaceVariationsStillMatchTheSendFormat() {
    val kind = context.getString(R.string.TalerFork_kind_pay_push)
    val body = "Payment sent:  $kind  ($payPushUri)"

    assertEquals(R.string.TalerFork_kind_pay_push, TalerPreviewText.labelRes(context, null, body))
  }

  // Bare-URI-Nachricht (kein Format, kein Payload): Resttext leer

  @Test
  fun bareUriYieldsKindLabel() {
    assertEquals(R.string.TalerFork_kind_pay_pull, TalerPreviewText.labelRes(context, null, payPullUri))
  }

  @Test
  fun extTalerUriIsDetected() {
    assertEquals(R.string.TalerFork_kind_pay_push, TalerPreviewText.labelRes(context, null, "ext+taler://pay-push/exchange.demo.taler.net/regressionA"))
  }
}
