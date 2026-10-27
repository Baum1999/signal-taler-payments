/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import net.taler.wallet.link.TalerHttpsLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalerAbortPlanTest {

  private val a = "taler://pay-push/exchange.example.org/AAAA"
  private val b = "taler://pay-push/exchange.example.org/BBBB"

  @Test
  fun batches_dropDuplicatesAndSplitAtLimit() {
    val uris = (0 until TalerAbortPlan.MAX_URIS_PER_LINK + 1).map { "$a$it" } + "${a}0"
    val batches = TalerAbortPlan.batches(uris)
    assertEquals(2, batches.size)
    assertEquals(TalerAbortPlan.MAX_URIS_PER_LINK, batches[0].size)
    assertEquals(listOf("$a${TalerAbortPlan.MAX_URIS_PER_LINK}"), batches[1])
  }

  @Test
  fun batches_emptyInputYieldsNoBatch() {
    assertTrue(TalerAbortPlan.batches(emptyList()).isEmpty())
  }

  @Test
  fun parseResults_splitsAtFirstPipeAndDropsUnknown() {
    val results = TalerAbortPlan.parseResults(
      listOf("$a|not_own", "$b|aborted", "$a|bogus", "no-separator", "|aborted", "$a|x|aborted")
    )
    assertEquals(
      listOf(
        TalerAbortResult(a, TalerAbortOutcome.NOT_OWN),
        TalerAbortResult(b, TalerAbortOutcome.ABORTED),
      ),
      results
    )
  }

  @Test
  fun apply_mapsOutcomesAndIgnoresForeignUris() {
    val results = listOf(
      TalerAbortResult(a, TalerAbortOutcome.ABORTED),
      TalerAbortResult(b, TalerAbortOutcome.EXPIRED),
      TalerAbortResult("taler://pay-push/evil.example.org/CCCC", TalerAbortOutcome.ABORTED),
    )
    val followUps = TalerAbortPlan.apply(results, allowed = listOf(a, b))
    assertEquals(
      listOf(
        a to TalerAbortFollowUp.VerifyAbort,
        b to TalerAbortFollowUp.SetStatus(TalerPaymentStatus.ABGELAUFEN),
      ),
      followUps
    )
  }

  /** Zahlung von einem anderen Geraet: Taler meldet not_own, Signal aendert keinen Status. */
  @Test
  fun apply_notOwnOnlyRefreshes() {
    val followUps = TalerAbortPlan.apply(listOf(TalerAbortResult(a, TalerAbortOutcome.NOT_OWN)), allowed = listOf(a))
    assertEquals(listOf(a to TalerAbortFollowUp.Refresh), followUps)
  }

  @Test
  fun apply_matchesUnwrappedHttpsLinkAndReturnsStoredForm() {
    val wrapped = TalerHttpsLink.wrap(a)
    val followUps = TalerAbortPlan.apply(listOf(TalerAbortResult(a, TalerAbortOutcome.DECLINED)), allowed = listOf(wrapped))
    assertEquals(listOf(wrapped to TalerAbortFollowUp.Refresh), followUps)
  }

  @Test
  fun shouldContinue_onlyWhenNotAllDeclined() {
    assertFalse(TalerAbortPlan.shouldContinue(emptyList()))
    assertFalse(TalerAbortPlan.shouldContinue(listOf(TalerAbortResult(a, TalerAbortOutcome.DECLINED))))
    assertTrue(
      TalerAbortPlan.shouldContinue(
        listOf(TalerAbortResult(a, TalerAbortOutcome.DECLINED), TalerAbortResult(b, TalerAbortOutcome.ABORTED))
      )
    )
  }
}
