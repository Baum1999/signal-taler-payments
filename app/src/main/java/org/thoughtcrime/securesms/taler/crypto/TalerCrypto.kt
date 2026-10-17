package org.thoughtcrime.securesms.taler.crypto

import java.security.MessageDigest

/**
 * Die zusammengesetzten Taler-Krypto-Operationen, die der Peer-Contract-Abruf
 * braucht. Gegenstuecke in wallet-core: `keyExchangeEcdhEddsa` und `hash`
 * (`taler-util/src/taler-crypto.ts`).
 */
object TalerCrypto {
  fun sha512(data: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-512").digest(data)
  }

  /**
   * Leitet das gemeinsame Geheimnis zwischen einem ECDHE-Privatschluessel und
   * einem EdDSA-Publickey ab: Konvertierung des Publickeys auf die
   * Montgomery-Kurve, X25519, dann SHA-512 ueber das Ergebnis.
   */
  fun keyExchangeEcdhEddsa(ecdhPrivateKey: ByteArray, eddsaPublicKey: ByteArray): ByteArray {
    val montgomery = Ed25519.toMontgomery(eddsaPublicKey)
    return sha512(X25519.scalarMult(ecdhPrivateKey, montgomery))
  }
}
