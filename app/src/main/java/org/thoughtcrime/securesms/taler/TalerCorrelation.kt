package org.thoughtcrime.securesms.taler

import java.security.MessageDigest

/**
 * Nicht umkehrbarer Kurz-Hash einer Taler-URI, ausschliesslich fuer
 * Korrelation zwischen Logzeilen und als Job-Queue-Name gedacht - siehe
 * REVIEW.md B1/B1b. Die URI selbst ist ein Inhaberpapier (docs/API.md) und
 * darf nirgends im Klartext landen: kein Log, keine Exception-Message, die
 * ueber den Binder geht, kein Job-Queue-Name (der landet persistent in
 * Signals Job-Datenbank, siehe JobDatabase.QUEUE_KEY).
 *
 * SHA-256, 48 Bit (12 Hex-Zeichen) reichen fuer Korrelation in einer
 * einzelnen Geraete-Log-Session bei weitem aus, sind aber kein
 * Sicherheitsmechanismus - nur Rauschvermeidung/Log-Hygiene.
 */
object TalerCorrelation {
  fun shortHash(uri: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(12)
  }
}
