package org.thoughtcrime.securesms.taler.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Die beiden Ed25519-Operationen, die Signal fuer die Peer-Contract-Aufloesung
 * braucht: die Ableitung des Public Keys aus dem in der Taler-URI enthaltenen
 * privaten Schluessel (fuer die Abruf-URL `contracts/$CONTRACT_PUB`) und die
 * Konvertierung eines Ed25519-Public-Keys auf die Montgomery-Kurve (fuer den
 * ECDH-Schritt). Gegenstuecke in wallet-core: `eddsaGetPublic` bzw.
 * `ed25519.utils.toMontgomery` (`taler-util/src/taler-crypto.ts`).
 *
 * Die Skalarmultiplikation ist bewusst nicht seitenkanalresistent: der einzige
 * hier verarbeitete Skalar ist der Einmal-Schluessel aus der URI, der ohnehin
 * jedem Link-Inhaber vorliegt und aus dem hier nur ein oeffentlicher Wert
 * abgeleitet wird. Fuer Langzeitgeheimnisse waere diese Implementierung
 * ungeeignet.
 */
object Ed25519 {
  private val P: BigInteger = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)
  private val D: BigInteger = BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555")
  private val BASE_X: BigInteger = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
  private val BASE_Y: BigInteger = BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")

  fun getPublicKey(seed: ByteArray): ByteArray {
    require(seed.size == 32) { "Ed25519-Seed muss 32 Bytes haben" }
    val hash = MessageDigest.getInstance("SHA-512").digest(seed)
    val scalar = hash.copyOf(32)
    scalar[0] = (scalar[0].toInt() and 248).toByte()
    scalar[31] = (scalar[31].toInt() and 127 or 64).toByte()
    return encodePoint(scalarMultBase(leToBigInteger(scalar)))
  }

  fun toMontgomery(publicKey: ByteArray): ByteArray {
    require(publicKey.size == 32) { "Ed25519-Public-Key muss 32 Bytes haben" }
    val cleared = publicKey.copyOf()
    cleared[31] = (cleared[31].toInt() and 0x7f).toByte()
    val y = leToBigInteger(cleared)
    val u = (BigInteger.ONE + y).multiply((BigInteger.ONE - y).mod(P).modInverse(P)).mod(P)
    return bigIntegerToLe(u)
  }

  private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

  private fun add(a: Point, b: Point): Point {
    val f = (a.y - a.x).multiply(b.y - b.x).mod(P)
    val g = (a.y + a.x).multiply(b.y + b.x).mod(P)
    val h = a.t.multiply(BigInteger.TWO).multiply(D).multiply(b.t).mod(P)
    val i = a.z.multiply(BigInteger.TWO).multiply(b.z).mod(P)
    val e = (g - f).mod(P)
    val fv = (i - h).mod(P)
    val gv = (i + h).mod(P)
    val hv = (g + f).mod(P)
    return Point(
      e.multiply(fv).mod(P),
      gv.multiply(hv).mod(P),
      fv.multiply(gv).mod(P),
      e.multiply(hv).mod(P)
    )
  }

  private fun scalarMultBase(scalar: BigInteger): Point {
    val base = Point(BASE_X, BASE_Y, BigInteger.ONE, BASE_X.multiply(BASE_Y).mod(P))
    var result = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    var addend = base
    var remaining = scalar
    while (remaining.signum() > 0) {
      if (remaining.testBit(0)) {
        result = add(result, addend)
      }
      addend = add(addend, addend)
      remaining = remaining.shiftRight(1)
    }
    return result
  }

  private fun encodePoint(point: Point): ByteArray {
    val zInverse = point.z.modInverse(P)
    val x = point.x.multiply(zInverse).mod(P)
    val y = point.y.multiply(zInverse).mod(P)
    val encoded = bigIntegerToLe(y)
    if (x.testBit(0)) {
      encoded[31] = (encoded[31].toInt() or 0x80).toByte()
    }
    return encoded
  }

  private fun leToBigInteger(bytes: ByteArray): BigInteger {
    return BigInteger(1, bytes.reversedArray())
  }

  private fun bigIntegerToLe(value: BigInteger): ByteArray {
    val out = ByteArray(32)
    val magnitude = value.toByteArray()
    var source = magnitude.size - 1
    var target = 0
    while (source >= 0 && target < 32) {
      out[target++] = magnitude[source--]
    }
    return out
  }
}
