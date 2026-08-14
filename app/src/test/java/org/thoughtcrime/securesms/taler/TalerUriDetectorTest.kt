package org.thoughtcrime.securesms.taler

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
