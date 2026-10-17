package org.thoughtcrime.securesms.taler

/**
 * Identity of the GNU Taler wallet app that Signal talks to, and the URI the
 * wallet uses to jump back to Signal.
 *
 * Signal checks the installed wallet against [PACKAGE] and [CERT_SHA256]
 * before sending it any explicit intent. The defaults are the official
 * F-Droid build of GNU Taler ("CN=Taler F-Droid, O=Taler Systems SA").
 * Builds that pair Signal with a differently signed wallet change these
 * values together with the `<queries>` entry and the taler-return intent
 * filter in AndroidManifest.xml.
 */
object TalerAllowlist {
  const val PACKAGE = "net.taler.wallet.fdroid"
  const val CERT_SHA256 = "EDB0D4397B99AF4391E0FC2178428470A36D5E2C4ACEBED5D9B3515947836180"
  const val BIND_ACTION = "net.taler.wallet.link.BIND"

  /** Scheme of [RETURN_URI]; must match the taler-return intent filter. */
  const val RETURN_SCHEME = "sgnl"
  const val RETURN_URI = "$RETURN_SCHEME://taler-return"
}
