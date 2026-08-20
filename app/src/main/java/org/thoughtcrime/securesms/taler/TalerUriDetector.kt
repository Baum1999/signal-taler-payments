package org.thoughtcrime.securesms.taler

/**
 * Erkennt Taler-URIs (taler://, ext+taler://, payto://) in freiem Text - die
 * gleichen drei Schemata, die Talers eigenes Manifest als Intent-Filter
 * registriert (wallet/src/main/AndroidManifest.xml, siehe docs/API.md).
 *
 * Reine Textmustererkennung, keine Validierung. Ob eine gefundene URI
 * tatsaechlich gueltig ist, entscheidet immer Taler selbst
 * (TalerLinkClient.validateUri) - siehe docs/API.md Abschnitt 2.8: eine
 * gefundene URI ist nur ein Kandidat, keine Vertrauensquelle.
 */
object TalerUriDetector {
  private val URI_REGEX = Regex("(?i)\\b(?:taler|ext\\+taler|payto)://\\S+")
  private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ')', ']', '}', '!', '?', ';', ':')

  fun findUris(text: String): List<String> =
    URI_REGEX.findAll(text)
      .map { it.value.trimEnd(*TRAILING_PUNCTUATION) }
      .distinct()
      .toList()

  /**
   * Prueft, ob [text] GENAU eine wohlgeformte Taler-URI ist - der gesamte
   * String, kein Praefix/Suffix, kein zusaetzlicher Text, keine zweite URI.
   * Sicherheitskritisch: einzige Gate zwischen einem Intent-Extra einer
   * exportierten Activity (TalerReturnActivity) und einer echten ausgehenden
   * Nachricht - siehe TalerUriDetectorTest fuer die Randfaelle.
   */
  fun isExactlyOneUri(text: String): Boolean = findUris(text).firstOrNull() == text
}
