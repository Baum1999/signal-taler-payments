package org.thoughtcrime.securesms.taler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class TalerCorrelationStoreTest {

  @Test
  fun `put then take returns the stored entry`() {
    TalerCorrelationStore.put("corr-1", "taler://pay-push/host/id", threadId = 42L)

    val entry = TalerCorrelationStore.take("corr-1")

    assertEquals("taler://pay-push/host/id", entry?.uri)
    assertEquals(42L, entry?.threadId)
  }

  @Test
  fun `take is single-use - second take returns null`() {
    TalerCorrelationStore.put("corr-2", "taler://pay-push/host/id2", threadId = 7L)

    TalerCorrelationStore.take("corr-2")
    val second = TalerCorrelationStore.take("corr-2")

    assertNull(second)
  }

  @Test
  fun `unknown correlationId returns null`() {
    assertNull(TalerCorrelationStore.take("does-not-exist"))
  }

  @Test
  fun `expired entry (older than 15 minutes) returns null`() {
    // put() itself always stamps System.currentTimeMillis(), so we verify expiry by
    // calling take() with an explicit "now" far in the future relative to a fresh put().
    TalerCorrelationStore.put("corr-3", "taler://pay-push/host/id3", threadId = 1L)
    val farFuture = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(16)

    val entry = TalerCorrelationStore.take("corr-3", now = farFuture)

    assertNull(entry)
  }

  @Test
  fun `entry within TTL is still returned`() {
    TalerCorrelationStore.put("corr-4", "taler://pay-push/host/id4", threadId = 2L)
    val withinTtl = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(14)

    val entry = TalerCorrelationStore.take("corr-4", now = withinTtl)

    assertEquals(2L, entry?.threadId)
  }

  @Test
  fun `entry exactly at the 15-minute TTL boundary is still valid`() {
    // take() expires with a strict "now - createdAt > TTL_MS" check, so exactly TTL_MS
    // after storage the entry must still be returned - only just past it should it expire.
    // put() stamps its own System.currentTimeMillis() internally, so "before" (captured
    // immediately prior to the call, on the same thread with no intervening work) pins
    // down createdAt closely enough to exercise the boundary rather than the interior.
    val before = System.currentTimeMillis()
    TalerCorrelationStore.put("corr-5", "taler://pay-push/host/id5", threadId = 3L)
    val boundary = before + TimeUnit.MINUTES.toMillis(15)

    val entry = TalerCorrelationStore.take("corr-5", now = boundary)

    assertEquals(3L, entry?.threadId)
  }

  @Test
  fun `put with null uri (compose-send flow) returns entry with null uri`() {
    TalerCorrelationStore.put("corr-6", uri = null, threadId = 9L)

    val entry = TalerCorrelationStore.take("corr-6")

    assertNull(entry?.uri)
    assertEquals(9L, entry?.threadId)
  }
}
