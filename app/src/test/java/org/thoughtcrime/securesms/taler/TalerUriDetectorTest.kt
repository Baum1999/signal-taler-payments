package org.thoughtcrime.securesms.taler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deckt beide oeffentlichen Funktionen von TalerUriDetector ab:
 *
 * - findUris(): reine Textmustererkennung (mehrere URIs, Trailing-Punktuation,
 *   Dedup, Gross-/Kleinschreibung, leerer Text) - live genutzt von
 *   TalerPaymentCardPresenter, TalerPaymentTracker und TalerConfirmationDetector.
 *
 * - isExactlyOneUri(): die einzige Gate zwischen den angreiferkontrollierten
 *   Intent-Parametern von TalerReturnActivity (exportiert) und einer echten
 *   ausgehenden Nachricht (siehe TalerReturnActivity.kt, sendComposedPayment).
 *   Deshalb hier explizit auf die nicht-offensichtlichen Randfaelle getestet:
 *   eingebetteter Text, eingebettete Whitespaces, leerer String, mehrere URIs
 *   im selben String.
 */
class TalerUriDetectorTest {

  @Test
  fun findsMultipleUrisInOneText() {
    val text = "Hier: taler://pay-push/exchange.example/AAA und dann noch taler://pay-pull/exchange.example/BBB"
    assertEquals(
      listOf("taler://pay-push/exchange.example/AAA", "taler://pay-pull/exchange.example/BBB"),
      TalerUriDetector.findUris(text)
    )
  }

  @Test
  fun stripsTrailingPunctuationAtSentenceEnd() {
    assertEquals(listOf("taler://pay-push/exchange.example/AAA"), TalerUriDetector.findUris("Schau mal: taler://pay-push/exchange.example/AAA."))
    assertEquals(listOf("taler://pay-push/exchange.example/AAA"), TalerUriDetector.findUris("taler://pay-push/exchange.example/AAA!"))
    assertEquals(listOf("taler://pay-push/exchange.example/AAA"), TalerUriDetector.findUris("(taler://pay-push/exchange.example/AAA)"))
    assertEquals(listOf("taler://pay-push/exchange.example/AAA"), TalerUriDetector.findUris("taler://pay-push/exchange.example/AAA?"))
    assertEquals(listOf("taler://pay-push/exchange.example/AAA"), TalerUriDetector.findUris("[taler://pay-push/exchange.example/AAA]"))
  }

  @Test
  fun deduplicatesRepeatedUri() {
    val text = "taler://pay-push/exchange.example/AAA taler://pay-push/exchange.example/AAA"
    assertEquals(listOf("taler://pay-push/exchange.example/AAA"), TalerUriDetector.findUris(text))
  }

  @Test
  fun findsAllThreeSchemes() {
    val text = "taler://pay-push/x ext+taler://pay-pull/y payto://iban/DE1234567890"
    assertEquals(3, TalerUriDetector.findUris(text).size)
  }

  @Test
  fun isCaseInsensitiveForSchemeDetection() {
    assertEquals(1, TalerUriDetector.findUris("TALER://pay-push/exchange.example/AAA").size)
  }

  @Test
  fun returnsEmptyListForTextWithoutUris() {
    assertEquals(emptyList<String>(), TalerUriDetector.findUris("Hallo, wie geht es dir?"))
  }

  @Test
  fun returnsEmptyListForEmptyBody() {
    assertEquals(emptyList<String>(), TalerUriDetector.findUris(""))
  }

  @Test
  fun `a clean single uri returns true`() {
    assertTrue(TalerUriDetector.isExactlyOneUri("taler://pay-push/exchange.example/abc123"))
  }

  @Test
  fun `uri with garbage text before it returns false`() {
    assertFalse(TalerUriDetector.isExactlyOneUri("hello taler://pay-push/exchange.example/abc123"))
  }

  @Test
  fun `uri with garbage text after it returns false`() {
    assertFalse(TalerUriDetector.isExactlyOneUri("taler://pay-push/exchange.example/abc123 world"))
  }

  @Test
  fun `uri with embedded whitespace inside it returns false`() {
    // \S+ in the regex stops at the first whitespace, so a string like this
    // only ever matches the fragment up to the space - never the whole string.
    assertFalse(TalerUriDetector.isExactlyOneUri("taler://pay-push/exchange.example/abc 123"))
  }

  @Test
  fun `empty string returns false`() {
    assertFalse(TalerUriDetector.isExactlyOneUri(""))
  }

  @Test
  fun `two uris glued together with no separator is a single greedy match, so it returns true`() {
    // Non-obvious regex quirk, verified rather than assumed: URI_REGEX's \S+ is greedy
    // and there is no \b word-boundary transition between "...abc123" and "taler://..."
    // (digit and letter are both \w), so findAll produces exactly ONE match spanning the
    // whole glued string here - not two. isExactlyOneUri correctly (if surprisingly)
    // returns true, since findUris().firstOrNull() really does equal the whole input.
    // This is why test case "two uris" below uses a whitespace separator instead - that
    // is what actually produces two distinct matches and exercises the intended check.
    assertTrue(
      TalerUriDetector.isExactlyOneUri(
        "taler://pay-push/exchange.example/abc123taler://pay-push/exchange.example/def456"
      )
    )
  }

  @Test
  fun `two uris separated by whitespace returns false`() {
    assertFalse(
      TalerUriDetector.isExactlyOneUri(
        "taler://pay-push/exchange.example/abc123 taler://pay-push/exchange.example/def456"
      )
    )
  }

  @Test
  fun `payto uri alone still returns true - detector is pattern-only, not kind-restricted`() {
    // isExactlyOneUri only proves "well-formed Taler-ish URI, whole string" -
    // rejecting non-peer kinds is a separate, additional check the caller
    // (TalerReturnActivity) performs afterwards via TalerUriParser.classify(),
    // not here.
    assertTrue(TalerUriDetector.isExactlyOneUri("payto://iban/DE1234567890/?message=test"))
  }
}
