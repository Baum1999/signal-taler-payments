package org.thoughtcrime.securesms.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MessageTypesTalerTest {

  @Test
  public void specialTypeTalerPaymentUpdate_isWithinSpecialTypesMask() {
    long bit = MessageTypes.SPECIAL_TYPE_TALER_PAYMENT_UPDATE;

    assertEquals(bit, bit & MessageTypes.SPECIAL_TYPES_MASK);
  }

  @Test
  public void specialTypeTalerPaymentUpdate_doesNotCollideWithExistingSpecialTypes() {
    long bit = MessageTypes.SPECIAL_TYPE_TALER_PAYMENT_UPDATE;

    assertTrue(bit != MessageTypes.SPECIAL_TYPE_STORY_REACTION);
    assertTrue(bit != MessageTypes.SPECIAL_TYPE_GIFT_BADGE);
    assertTrue(bit != MessageTypes.SPECIAL_TYPE_PAYMENTS_NOTIFICATION);
    assertTrue(bit != MessageTypes.SPECIAL_TYPE_PAYMENTS_ACTIVATE_REQUEST);
    assertTrue(bit != MessageTypes.SPECIAL_TYPE_REPORTED_SPAM);
    assertTrue(bit != MessageTypes.SPECIAL_TYPE_MESSAGE_REQUEST_ACCEPTED);
    assertTrue(bit != MessageTypes.SPECIAL_TYPE_PAYMENTS_ACTIVATED);
    assertTrue(bit != MessageTypes.SPECIAL_TYPE_PAYMENTS_TOMBSTONE);
    assertTrue(bit != MessageTypes.SPECIAL_TYPE_BLOCKED);
    assertTrue(bit != MessageTypes.SPECIAL_TYPE_UNBLOCKED);
    assertTrue(bit != MessageTypes.SPECIAL_TYPE_POLL_TERMINATE);
    assertTrue(bit != MessageTypes.SPECIAL_TYPE_PINNED_MESSAGE);
  }
}
