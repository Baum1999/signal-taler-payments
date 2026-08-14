package org.thoughtcrime.securesms.taler

/**
 * Erwartete Identitaet der Taler-App, gegen die Signal vor jedem Bind prueft.
 * Fingerabdruck berechnet aus keys/taler-fuer-signal.p12 (siehe docs/API.md,
 * Abschnitt 2.2: `keytool -list -v -alias talerfuersignal`, Feld SHA256).
 */
object TalerAllowlist {
  const val PACKAGE = "de.lenkenhoff.talerfuersignal.fdroid"
  const val CERT_SHA256 = "6A2EA96A46D6DCEE7F1D544A6AC2D58305F1141967C67D237CC68D8B97F0BA1D"
  const val BIND_ACTION = "net.taler.wallet.link.BIND"
}
