/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import net.taler.wallet.link.TalerUriKind
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.testutil.RecipientTestRule

/**
 * TalerConfirmationTracker.trackConfirmationInBody() ist das Gegenstueck zum
 * Icon in TalerConfirmationIconPresenter - nur eine hier als gueltig
 * eingestufte Nachricht bekommt spaeter das Icon statt Rohtext. Deckt genau
 * die drei Bedingungen ab, die eine erkannte Bestaetigungsphrase noch
 * verwerfen (Klasse-Doc: "sonst bleibt die Nachricht ganz normaler Text") -
 * falscher Thread, falsche Art, fremde (nicht eigene) Zahlung.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TalerConfirmationTrackerTest {

  @get:Rule
  val recipientTestRule = RecipientTestRule()

  private val context = ApplicationProvider.getApplicationContext<Application>()
  private val uri = "taler://pay-push/exchange.demo.taler.net/confirmTrackerA"

  private fun confirmationText(uri: String = this.uri) = context.getString(
    R.string.TalerFork_accept_confirmation_message,
    context.getString(R.string.TalerFork_kind_pay_push),
    uri,
  )

  private fun markOwnPayPush(uri: String, threadId: Long, isOwnPayment: Boolean = true, uriKind: String = TalerUriKind.PAY_PUSH.name) {
    SignalDatabase.talerPayments.upsertDetected(uri, threadId = threadId, isOwnPayment = false)
    SignalDatabase.talerPayments.updateFromPreview(
      uri = uri,
      uriKind = uriKind,
      status = TalerPaymentStatus.ANGENOMMEN,
      amount = "1",
      currency = "KUDOS",
      exchangeBaseUrl = "https://exchange.demo.taler.net/",
      summary = null,
      isOwnPayment = isOwnPayment,
    )
  }

  @Test
  fun tracksConfirmationForKnownOwnPaymentInTheSameThread() {
    markOwnPayPush(uri, threadId = 1)

    TalerConfirmationTracker.trackConfirmationInBody(context, confirmationText(), threadId = 1, messageId = 100)

    assertNotNull(SignalDatabase.talerConfirmationMessages.getByMessageId(100))
  }

  @Test
  fun ignoresConfirmationWhenThreadDoesNotMatch() {
    markOwnPayPush(uri, threadId = 1)

    TalerConfirmationTracker.trackConfirmationInBody(context, confirmationText(), threadId = 2, messageId = 101)

    assertNull(SignalDatabase.talerConfirmationMessages.getByMessageId(101))
  }

  @Test
  fun ignoresConfirmationWhenPaymentIsNotOwnPayment() {
    markOwnPayPush(uri, threadId = 1, isOwnPayment = false)

    TalerConfirmationTracker.trackConfirmationInBody(context, confirmationText(), threadId = 1, messageId = 102)

    assertNull(SignalDatabase.talerConfirmationMessages.getByMessageId(102))
  }

  @Test
  fun ignoresConfirmationWhenUriIsUnknown() {
    TalerConfirmationTracker.trackConfirmationInBody(context, confirmationText(), threadId = 1, messageId = 103)

    assertNull(SignalDatabase.talerConfirmationMessages.getByMessageId(103))
  }

  @Test
  fun ignoresPlainTextThatIsNotTheExactConfirmationPhrase() {
    markOwnPayPush(uri, threadId = 1)

    TalerConfirmationTracker.trackConfirmationInBody(context, "Hey, ${confirmationText()}", threadId = 1, messageId = 104)

    assertNull(SignalDatabase.talerConfirmationMessages.getByMessageId(104))
  }

  @Test
  fun ignoresBlankBody() {
    TalerConfirmationTracker.trackConfirmationInBody(context, "", threadId = 1, messageId = 105)
    TalerConfirmationTracker.trackConfirmationInBody(context, null, threadId = 1, messageId = 106)

    assertNull(SignalDatabase.talerConfirmationMessages.getByMessageId(105))
    assertNull(SignalDatabase.talerConfirmationMessages.getByMessageId(106))
  }
}
