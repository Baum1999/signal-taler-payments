package org.thoughtcrime.securesms.taler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

/**
 * Testet signingCertSha256Of() - die reine Entscheidungslogik hinter
 * signingCertSha256()/signingCertSha256FromSigningInfo() (REVIEW.md P3), mit
 * rohen Bytes statt einem echten android.content.pm.Signature/SigningInfo:
 * die Android-Klassen lassen sich in einem JVM-Unit-Test nicht robust
 * konstruieren, ohne PackageManager zu faken oder von einer bestimmten
 * Robolectric-SDK-Shadow-Version abzuhaengen.
 */
class TalerCertUtilTest {

  @Test
  fun multipleSignersIsRejected() {
    // Signature-Rotation/Mehrfach-Signer lehnen wir bewusst ab (docs/API.md 2.2) -
    // selbst wenn ein Signer-Byte vorhanden waere, muss das Ergebnis null sein.
    val result = signingCertSha256Of(hasMultipleSigners = true, firstSignerBytes = byteArrayOf(1, 2, 3))
    assertNull(result)
  }

  @Test
  fun noSignerIsRejected() {
    val result = signingCertSha256Of(hasMultipleSigners = false, firstSignerBytes = null)
    assertNull(result)
  }

  @Test
  fun correctHashIsAccepted() {
    val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
    val expectedHex = MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02X".format(it) }

    val result = signingCertSha256Of(hasMultipleSigners = false, firstSignerBytes = bytes)

    assertEquals(expectedHex, result)
  }

  @Test
  fun hashIsUppercaseHexWithoutSeparators() {
    val result = signingCertSha256Of(hasMultipleSigners = false, firstSignerBytes = byteArrayOf(0x00))
    assertEquals(64, result?.length)
    assertEquals(result, result?.uppercase())
    assertEquals(true, result?.matches(Regex("^[0-9A-F]+$")))
  }

  // signingCertSha256FromSigningInfo()/signingCertSha256FromSignatures() selbst
  // sind nur noch duenne Adapter (Signature/SigningInfo -> Bytes, dann Aufruf
  // von signingCertSha256Of() oben) - echte android.content.pm.Signature-Objekte
  // lassen sich in einem plain JVM-Unit-Test nicht konstruieren (Stub-android.jar),
  // die eigentliche Entscheidungslogik ist mit den Tests oben vollstaendig
  // abgedeckt.
}
