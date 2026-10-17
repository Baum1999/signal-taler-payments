package org.thoughtcrime.securesms.taler

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import java.security.MessageDigest

/** SHA-256-Fingerabdruck (Hex, Grossbuchstaben, ohne Trenner) des Signing-Certs von [packageName]. */
fun signingCertSha256(pm: PackageManager, packageName: String): String? {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    signingCertSha256FromSigningInfo(info.signingInfo)
  } else {
    @Suppress("DEPRECATION")
    val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    @Suppress("DEPRECATION")
    signingCertSha256FromSignatures(info.signatures)
  }
}

/**
 * Wandelt das API-28+-Ergebnis in die Rohform um, die [signingCertSha256Of]
 * testen kann (REVIEW.md P3) - [SigningInfo] laesst sich in einem JVM-Unit-Test
 * nicht robust konstruieren (kein stabiler Test-Doppelgaenger ohne
 * PackageManager-Fake/Robolectric-SDK-Abhaengigkeit), die eigentliche
 * Entscheidung (Mehrfach-Signer ablehnen, ersten Signer nehmen) haengt aber
 * nicht an der Android-Klasse selbst.
 */
internal fun signingCertSha256FromSigningInfo(signingInfo: SigningInfo?): String? {
  if (signingInfo == null) return null
  return signingCertSha256Of(
    hasMultipleSigners = signingInfo.hasMultipleSigners(),
    firstSignerBytes = signingInfo.apkContentsSigners?.firstOrNull()?.toByteArray(),
  )
}

/** Reine Entscheidungslogik fuer den Pre-API-28-Pfad, gleiche Begruendung wie oben. */
internal fun signingCertSha256FromSignatures(signatures: Array<Signature>?): String? =
  signingCertSha256Of(hasMultipleSigners = false, firstSignerBytes = signatures?.firstOrNull()?.toByteArray())

/**
 * Reine Entscheidungslogik ohne jede Android-Framework-Abhaengigkeit
 * (REVIEW.md P3) - testbar mit rohen Bytes statt einem echten [Signature]/
 * [SigningInfo]. Siehe TalerCertUtilTest: Mehrfach-Signer-/Kein-Signer-/
 * Korrekter-Hash-Fall.
 */
internal fun signingCertSha256Of(hasMultipleSigners: Boolean, firstSignerBytes: ByteArray?): String? {
  if (hasMultipleSigners) {
    // Signature-Rotation lehnen wir bewusst ab, siehe docs/API.md Abschnitt 2.2.
    return null
  }
  val bytes = firstSignerBytes ?: return null
  val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
  return digest.joinToString("") { "%02X".format(it) }
}
