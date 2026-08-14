package org.thoughtcrime.securesms.taler

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Billig, aber faengt genau den Tippfehler ab, der die Kopplung
 * stillschweigend kaputtmachen wuerde (REVIEW.md P3): ein gepinnter
 * SHA-256-Fingerabdruck, der nicht aus exakt 64 Hex-Zeichen besteht, kann nie
 * mit einem echten signingCertSha256()-Ergebnis uebereinstimmen - Signal
 * wuerde Taler dann fuer JEDEN Nutzer als "nicht vertrauenswuerdig" ablehnen,
 * nicht nur bei einem echten Signaturwechsel.
 */
class TalerAllowlistTest {

  private val hex64 = Regex("^[0-9A-Fa-f]{64}$")

  @Test
  fun certSha256IsSixtyFourHexChars() {
    assertTrue(
      "TalerAllowlist.CERT_SHA256 ist keine 64-stellige Hex-Zahl: ${TalerAllowlist.CERT_SHA256}",
      hex64.matches(TalerAllowlist.CERT_SHA256)
    )
  }
}
