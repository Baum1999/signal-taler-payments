/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import net.taler.wallet.link.TalerHttpsLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TalerOpenLinkTest {

  private val a = "taler://pay-push/exchange.example.org/AAAA"
  private val b = "taler://pay-push/exchange.example.org/BBBB"

  // printf '%s' 'taler://pay-push/exchange.example.org/AAAA' | shasum -a 256
  private val hashA = "1a203a81914ee8035af5da817dd6223364c6b224cade68859cfd41b53563b445"
  private val hashB = "311a5d05aae274a6a7fb1d285e64760c9087835b91b93946d4faf1a0518bca5c"

  @Test
  fun hashOf_isTheLowercaseSha256OfTheTalerUri() {
    assertEquals(hashA, TalerOpenLink.hashOf(a))
  }

  @Test
  fun hashOf_unwrapsTheHttpsLinkFromTheMessageBody() {
    // Eigene Zahlungen stehen (vor V339) unter dem DD-103-Link aus dem Body,
    // Taler hasht die taler://-URI.
    assertEquals(hashA, TalerOpenLink.hashOf(TalerHttpsLink.wrap(a)))
  }

  @Test
  fun parseHashes_keepsValidHashesInLinkOrderLowercasedAndWithoutDuplicates() {
    val parsed = TalerOpenLink.parseHashes(listOf(hashB, "zz", hashA.uppercase(), hashB, hashA.take(63), ""))
    assertEquals(listOf(hashB, hashA), parsed)
  }

  @Test
  fun parseHashes_stopsAtTheLimit() {
    val many = (0 until TalerOpenLink.MAX_HASHES + 5).map { "%064x".format(it) }
    assertEquals(TalerOpenLink.MAX_HASHES, TalerOpenLink.parseHashes(many).size)
  }

  @Test
  fun matches_findsStoredUrisInLinkOrder() {
    val stored = listOf(TalerHttpsLink.wrap(a), b, "taler://pay-push/exchange.example.org/CCCC")
    assertEquals(listOf(b, TalerHttpsLink.wrap(a)), TalerOpenLink.matches(listOf(hashB, hashA), stored) { it })
  }

  @Test
  fun matches_unknownHashFindsNothing() {
    assertTrue(TalerOpenLink.matches(listOf("%064x".format(1)), listOf(a, b)) { it }.isEmpty())
  }
}
