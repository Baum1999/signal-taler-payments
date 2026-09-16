package org.thoughtcrime.securesms.taler

import android.content.Context
import android.content.pm.PackageManager

/**
 * Ob die Taler-Wallet auf diesem Geraet vorhanden und die erwartete ist.
 * Beides rein lokale PackageManager-Fragen, ohne Netz und ohne die App zu
 * starten.
 *
 * Seit dem Wegfall der App-zu-App-Schnittstelle ist das die einzige
 * verbliebene Aussage ueber die Wallet - und sie betrifft nur noch, ob der
 * Nutzer eine Zahlung ausloesen kann. Der Zustand einer Zahlung selbst kommt
 * inzwischen vom Exchange (siehe [TalerPeerContractResolver]) und ist von der
 * Installation unabhaengig.
 */
object TalerInstallation {

  fun isInstalled(context: Context): Boolean = try {
    context.packageManager.getPackageInfo(TalerAllowlist.PACKAGE, 0)
    true
  } catch (e: PackageManager.NameNotFoundException) {
    false
  }

  /**
   * Installiert UND mit dem gepinnten Zertifikat signiert. Eine
   * gleichnamige, aber fremd signierte App ist genau der Fall, gegen den die
   * Allowlist schuetzt (docs/API.md Abschnitt 2.2) - sie gilt hier deshalb
   * als nicht nutzbar, nicht als nicht installiert.
   */
  fun isTrusted(context: Context): Boolean {
    val actual = try {
      signingCertSha256(context.packageManager, TalerAllowlist.PACKAGE)
    } catch (e: PackageManager.NameNotFoundException) {
      null
    } ?: return false
    return actual.equals(TalerAllowlist.CERT_SHA256, ignoreCase = true)
  }
}
