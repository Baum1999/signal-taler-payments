package org.thoughtcrime.securesms.taler

import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** SHA-256-Fingerabdruck (Hex, Grossbuchstaben, ohne Trenner) des Signing-Certs von [packageName]. */
fun signingCertSha256(pm: PackageManager, packageName: String): String? {
  val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val signingInfo = info.signingInfo ?: return null
    if (signingInfo.hasMultipleSigners()) {
      // Signature-Rotation lehnen wir bewusst ab, siehe docs/API.md Abschnitt 2.2.
      return null
    }
    signingInfo.apkContentsSigners?.firstOrNull() ?: return null
  } else {
    @Suppress("DEPRECATION")
    val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    @Suppress("DEPRECATION")
    info.signatures?.firstOrNull() ?: return null
  }
  val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
  return digest.joinToString("") { "%02X".format(it) }
}
