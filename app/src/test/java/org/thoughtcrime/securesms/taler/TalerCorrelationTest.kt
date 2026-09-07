/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TalerCorrelation.shortHash() ist die einzige Stelle, an der eine
 * Taler-URI (Inhaberpapier, siehe docs/API.md) fuer Logs/Job-Queue-Namen in
 * eine Nicht-Klartext-Form gebracht wird - deckt genau die Eigenschaften ab,
 * auf die sich Aufrufer verlassen (REVIEW.md B1/B1b): deterministisch, immer
 * 12 Hex-Zeichen, und die Original-URI taucht nicht im Ergebnis auf.
 */
class TalerCorrelationTest {

  private val uri = "taler://pay-push/exchange.demo.taler.net/regressionA?c=SECRETTOKEN"

  @Test
  fun isDeterministicForTheSameUri() {
    assertEquals(TalerCorrelation.shortHash(uri), TalerCorrelation.shortHash(uri))
  }

  @Test
  fun isTwelveLowercaseHexCharacters() {
    val hash = TalerCorrelation.shortHash(uri)
    assertEquals(12, hash.length)
    assertTrue("nicht rein lowercase-hex: $hash", Regex("^[0-9a-f]{12}$").matches(hash))
  }

  @Test
  fun differsForDifferentUris() {
    val other = "taler://pay-push/exchange.demo.taler.net/regressionB?c=OTHERTOKEN"
    assertNotEquals(TalerCorrelation.shortHash(uri), TalerCorrelation.shortHash(other))
  }

  @Test
  fun neverContainsTheOriginalUriOrItsSecretToken() {
    val hash = TalerCorrelation.shortHash(uri)
    assertFalse(hash.contains("SECRETTOKEN"))
    assertFalse(hash.contains("taler://"))
  }

  @Test
  fun handlesEmptyStringWithoutThrowing() {
    val hash = TalerCorrelation.shortHash("")
    assertEquals(12, hash.length)
  }
}
