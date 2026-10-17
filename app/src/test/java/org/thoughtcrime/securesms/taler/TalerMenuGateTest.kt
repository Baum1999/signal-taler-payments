/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.MmsHelper
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule

/**
 * TalerMenuGate.messageHasTalerUris() ist die einzige Bedingung, mit der
 * MenuState.java (Upstream-Datei) entscheidet, ob Taler-spezifische
 * Long-Press-Menuepunkte ueberhaupt in Frage kommen - eine falsch-negative
 * Antwort wuerde diese Menuepunkte fuer eine echte Taler-Nachricht
 * unsichtbar machen, eine falsch-positive wuerde sie fuer normalen Text
 * anbieten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TalerMenuGateTest {

  @get:Rule
  val recipientTestRule = RecipientTestRule()

  @Test
  fun trueForMessageBodyContainingATalerUri() {
    val record = insertMessage("taler://pay-push/exchange.demo.taler.net/menuGateA")
    assertTrue(TalerMenuGate.messageHasTalerUris(record))
  }

  @Test
  fun falseForPlainTextMessage() {
    val record = insertMessage("Hallo, wie geht's?")
    assertFalse(TalerMenuGate.messageHasTalerUris(record))
  }

  @Test
  fun falseForEmptyBody() {
    val record = insertMessage("")
    assertFalse(TalerMenuGate.messageHasTalerUris(record))
  }

  private fun insertMessage(body: String): org.thoughtcrime.securesms.database.model.MessageRecord {
    val recipientId = recipientTestRule.createRecipient("Menu Gate Test")
    val messageId = MmsHelper.insert(recipient = Recipient.resolved(recipientId), body = body)
    return SignalDatabase.messages.getMessageRecord(messageId)
  }
}
