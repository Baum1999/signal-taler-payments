package org.thoughtcrime.securesms.taler

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * urisFromMessageBody() existiert, weil TalerUriDetector.findUris() - reine
 * \S+-Regex-Texterkennung ueber freien Text (siehe TalerUriDetectorTest,
 * insb. "two uris glued together with no separator is a single greedy
 * match") - an einem kompakten (leerzeichenfreien) kotlinx.serialization-
 * JSON-Body scheitert: auf eine URI folgt dort direkt JSON-Syntax ohne
 * trennendes Leerzeichen, was entweder Muell an die URI anhaengt oder -
 * bei mehreren URIs im selben uri-Array - mehrere URIs zu einem einzigen
 * unbrauchbaren "Treffer" verschmilzt (Meilenstein 2,
 * PROMPT_parallel_group_split.md).
 */
class TalerPaymentDataTest {

  @Test
  fun `single uri JSON body returns exactly that uri, not a regex-mangled string`() {
    val paymentData = TalerPaymentData(
      legacyText = "fallback",
      uri = listOf("taler://pay-push/exchange.example/AAA"),
    )
    val body = Json.encodeToString(paymentData)
    assertEquals(listOf("taler://pay-push/exchange.example/AAA"), urisFromMessageBody(body))
  }

  @Test
  fun `multi uri JSON body returns all uris in order, not glued into one`() {
    val paymentData = TalerPaymentData(
      legacyText = "fallback",
      uri = listOf(
        "taler://pay-push/exchange.example/AAA",
        "taler://pay-push/exchange.example/BBB",
        "taler://pay-push/exchange.example/CCC",
      ),
    )
    val body = Json.encodeToString(paymentData)
    assertEquals(
      listOf(
        "taler://pay-push/exchange.example/AAA",
        "taler://pay-push/exchange.example/BBB",
        "taler://pay-push/exchange.example/CCC",
      ),
      urisFromMessageBody(body),
    )
  }

  @Test
  fun `plain text legacy message without JSON falls back to regex detection`() {
    val body = "Payment: taler://pay-push/exchange.example/AAA"
    assertEquals(listOf("taler://pay-push/exchange.example/AAA"), urisFromMessageBody(body))
  }

  @Test
  fun `unrelated JSON that does not parse as TalerPaymentData falls back to regex`() {
    // Trailing Leerzeichen VOR dem schliessenden Anfuehrungszeichen ist
    // hier absichtlich, nicht nur Zierde: TalerUriDetector.TRAILING_PUNCTUATION
    // deckt kein schliessendes Anfuehrungszeichen ab (siehe Klassen-KDoc
    // oben) - ohne das Leerzeichen wuerde \S+ bis "AAA"} matchen und
    // trimEnd nur das } entfernen, das " aber stehen lassen. Das ist
    // TalerUriDetectorTest vorbehalten, nicht Ziel dieses Falls hier.
    val body = """{"foo":"taler://pay-push/exchange.example/AAA "}"""
    assertEquals(listOf("taler://pay-push/exchange.example/AAA"), urisFromMessageBody(body))
  }

  @Test
  fun `JSON with empty uri list falls back to regex, finding nothing`() {
    val paymentData = TalerPaymentData(legacyText = "fallback", uri = emptyList())
    val body = Json.encodeToString(paymentData)
    assertEquals(emptyList<String>(), urisFromMessageBody(body))
  }

  @Test
  fun `blank text returns empty list`() {
    assertEquals(emptyList<String>(), urisFromMessageBody(""))
  }

  @Test
  fun `recipientAcis survives a JSON round-trip in sorted order`() {
    val paymentData = TalerPaymentData(
      legacyText = "fallback",
      uri = listOf(
        "taler://pay-push/exchange.example/AAA",
        "taler://pay-push/exchange.example/BBB",
      ),
      recipientAcis = listOf("aci-b", "aci-c"),
    )
    val body = Json.encodeToString(paymentData)
    val decoded = Json.decodeFromString<TalerPaymentData>(body)
    assertEquals(listOf("aci-b", "aci-c"), decoded.recipientAcis)
  }

  @Test
  fun `recipientAcis defaults to null for messages without it, e g plain single payments`() {
    val paymentData = TalerPaymentData(
      legacyText = "fallback",
      uri = listOf("taler://pay-push/exchange.example/AAA"),
    )
    val body = Json.encodeToString(paymentData)
    val decoded = Json.decodeFromString<TalerPaymentData>(body)
    assertEquals(null, decoded.recipientAcis)
  }
}
