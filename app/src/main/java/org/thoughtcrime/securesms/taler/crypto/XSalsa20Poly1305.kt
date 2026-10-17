package org.thoughtcrime.securesms.taler.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * NaCl-SecretBox (XSalsa20-Poly1305), das AEAD-Verfahren, mit dem Taler
 * verschluesselte Contract-Terms ablegt. Gegenstueck zu wallet-cores
 * `xsalsa20Poly1305Encrypt`/`Decrypt` (`taler-util/src/xsalsa20poly1305.ts`),
 * dort ueber `@noble/ciphers` realisiert.
 *
 * Format wie bei `crypto_secretbox_easy`: 16-Byte-Poly1305-Tag vorangestellt,
 * danach der Ciphertext.
 */
object XSalsa20Poly1305 {
  private const val TAG_SIZE = 16
  private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)
  private val POLY1305_PRIME: BigInteger = BigInteger.TWO.pow(130) - BigInteger.valueOf(5)
  private val POLY1305_R_MASK = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)
  private val TAG_MODULUS: BigInteger = BigInteger.TWO.pow(128)

  /** Gibt den Klartext zurueck, oder `null`, wenn der Tag nicht passt. */
  fun open(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
    require(key.size == 32) { "SecretBox-Schluessel muss 32 Bytes haben" }
    require(nonce.size == 24) { "SecretBox-Nonce muss 24 Bytes haben" }
    if (ciphertext.size < TAG_SIZE) {
      return null
    }

    val subKey = hsalsa20(key, nonce.copyOf(16))
    val streamNonce = nonce.copyOfRange(16, 24)

    val body = ciphertext.copyOfRange(TAG_SIZE, ciphertext.size)
    val firstBlock = salsa20Block(subKey, streamNonce, 0)
    val expectedTag = poly1305(body, firstBlock.copyOf(32))
    if (!MessageDigest.isEqual(expectedTag, ciphertext.copyOf(TAG_SIZE))) {
      return null
    }

    val plaintext = ByteArray(body.size)
    var offset = 0
    var counter = 0L
    var keyStream = firstBlock
    var keyStreamOffset = 32
    while (offset < body.size) {
      if (keyStreamOffset == 64) {
        counter++
        keyStream = salsa20Block(subKey, streamNonce, counter)
        keyStreamOffset = 0
      }
      plaintext[offset] = (body[offset].toInt() xor keyStream[keyStreamOffset].toInt()).toByte()
      offset++
      keyStreamOffset++
    }
    return plaintext
  }

  private fun poly1305(message: ByteArray, key: ByteArray): ByteArray {
    val r = leToBigInteger(key.copyOf(16)).and(POLY1305_R_MASK)
    val s = leToBigInteger(key.copyOfRange(16, 32))

    var accumulator = BigInteger.ZERO
    var offset = 0
    while (offset < message.size) {
      val blockSize = minOf(16, message.size - offset)
      val block = ByteArray(blockSize + 1)
      message.copyInto(block, 0, offset, offset + blockSize)
      block[blockSize] = 1
      accumulator = accumulator.add(leToBigInteger(block)).multiply(r).mod(POLY1305_PRIME)
      offset += blockSize
    }

    val tag = accumulator.add(s).mod(TAG_MODULUS)
    val out = ByteArray(TAG_SIZE)
    val magnitude = tag.toByteArray()
    var source = magnitude.size - 1
    var target = 0
    while (source >= 0 && target < TAG_SIZE) {
      out[target++] = magnitude[source--]
    }
    return out
  }

  private fun hsalsa20(key: ByteArray, input: ByteArray): ByteArray {
    val state = initialState(key, input, 0, 4)
    val mixed = rounds(state)
    val out = ByteArray(32)
    val words = intArrayOf(mixed[0], mixed[5], mixed[10], mixed[15], mixed[6], mixed[7], mixed[8], mixed[9])
    for (i in words.indices) {
      writeLeInt(out, i * 4, words[i])
    }
    return out
  }

  private fun salsa20Block(key: ByteArray, nonce: ByteArray, counter: Long): ByteArray {
    val input = ByteArray(16)
    nonce.copyInto(input, 0)
    writeLeInt(input, 8, counter.toInt())
    writeLeInt(input, 12, (counter ushr 32).toInt())

    val state = initialState(key, input, 0, 4)
    val mixed = rounds(state)
    val out = ByteArray(64)
    for (i in 0 until 16) {
      writeLeInt(out, i * 4, mixed[i] + state[i])
    }
    return out
  }

  private fun initialState(key: ByteArray, input: ByteArray, inputOffset: Int, inputWords: Int): IntArray {
    val state = IntArray(16)
    state[0] = SIGMA[0]
    state[5] = SIGMA[1]
    state[10] = SIGMA[2]
    state[15] = SIGMA[3]
    for (i in 0 until 4) {
      state[1 + i] = readLeInt(key, i * 4)
      state[11 + i] = readLeInt(key, 16 + i * 4)
    }
    for (i in 0 until inputWords) {
      state[6 + i] = readLeInt(input, inputOffset + i * 4)
    }
    return state
  }

  private fun rounds(state: IntArray): IntArray {
    val x = state.copyOf()
    repeat(10) {
      quarterRound(x, 0, 4, 8, 12)
      quarterRound(x, 5, 9, 13, 1)
      quarterRound(x, 10, 14, 2, 6)
      quarterRound(x, 15, 3, 7, 11)

      quarterRound(x, 0, 1, 2, 3)
      quarterRound(x, 5, 6, 7, 4)
      quarterRound(x, 10, 11, 8, 9)
      quarterRound(x, 15, 12, 13, 14)
    }
    return x
  }

  private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
    x[b] = x[b] xor Integer.rotateLeft(x[a] + x[d], 7)
    x[c] = x[c] xor Integer.rotateLeft(x[b] + x[a], 9)
    x[d] = x[d] xor Integer.rotateLeft(x[c] + x[b], 13)
    x[a] = x[a] xor Integer.rotateLeft(x[d] + x[c], 18)
  }

  private fun readLeInt(source: ByteArray, offset: Int): Int {
    return (source[offset].toInt() and 0xff) or
      ((source[offset + 1].toInt() and 0xff) shl 8) or
      ((source[offset + 2].toInt() and 0xff) shl 16) or
      ((source[offset + 3].toInt() and 0xff) shl 24)
  }

  private fun writeLeInt(target: ByteArray, offset: Int, value: Int) {
    target[offset] = value.toByte()
    target[offset + 1] = (value ushr 8).toByte()
    target[offset + 2] = (value ushr 16).toByte()
    target[offset + 3] = (value ushr 24).toByte()
  }

  private fun leToBigInteger(bytes: ByteArray): BigInteger {
    return BigInteger(1, bytes.reversedArray())
  }
}
