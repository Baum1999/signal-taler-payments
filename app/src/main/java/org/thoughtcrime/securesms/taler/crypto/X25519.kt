package org.thoughtcrime.securesms.taler.crypto

import java.math.BigInteger

/**
 * X25519-Skalarmultiplikation nach RFC 7748, als Gegenstueck zu wallet-cores
 * `platformCrypto.x25519`. Der Skalar wird wie dort vor der Verwendung
 * geklammert, damit der rohe 32-Byte-Schluessel aus der Taler-URI direkt
 * uebergeben werden kann.
 *
 * Zur fehlenden Seitenkanalresistenz gilt dieselbe Begruendung wie in
 * [Ed25519]: der Skalar liegt jedem Inhaber der URI ohnehin vor.
 */
object X25519 {
  private val P: BigInteger = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)
  private val A24: BigInteger = BigInteger.valueOf(121665)

  fun scalarMult(scalar: ByteArray, uCoordinate: ByteArray): ByteArray {
    require(scalar.size == 32) { "X25519-Skalar muss 32 Bytes haben" }
    require(uCoordinate.size == 32) { "X25519-U-Koordinate muss 32 Bytes haben" }

    val clamped = scalar.copyOf()
    clamped[0] = (clamped[0].toInt() and 248).toByte()
    clamped[31] = (clamped[31].toInt() and 127 or 64).toByte()
    val k = leToBigInteger(clamped)

    val maskedU = uCoordinate.copyOf()
    maskedU[31] = (maskedU[31].toInt() and 0x7f).toByte()
    val u = leToBigInteger(maskedU).mod(P)

    var x2 = BigInteger.ONE
    var z2 = BigInteger.ZERO
    var x3 = u
    var z3 = BigInteger.ONE
    var swapped = false

    for (bit in 254 downTo 0) {
      val bitSet = k.testBit(bit)
      if (bitSet != swapped) {
        val tx = x2; x2 = x3; x3 = tx
        val tz = z2; z2 = z3; z3 = tz
        swapped = bitSet
      }

      val a = (x2 + z2).mod(P)
      val aa = a.multiply(a).mod(P)
      val b = (x2 - z2).mod(P)
      val bb = b.multiply(b).mod(P)
      val e = (aa - bb).mod(P)
      val c = (x3 + z3).mod(P)
      val d = (x3 - z3).mod(P)
      val da = d.multiply(a).mod(P)
      val cb = c.multiply(b).mod(P)

      x3 = (da + cb).mod(P).let { it.multiply(it).mod(P) }
      z3 = u.multiply((da - cb).mod(P).let { it.multiply(it).mod(P) }).mod(P)
      x2 = aa.multiply(bb).mod(P)
      z2 = e.multiply((aa + A24.multiply(e)).mod(P)).mod(P)
    }

    if (swapped) {
      val tx = x2; x2 = x3; x3 = tx
      val tz = z2; z2 = z3; z3 = tz
    }

    return bigIntegerToLe(x2.multiply(z2.modPow(P - BigInteger.TWO, P)).mod(P))
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
