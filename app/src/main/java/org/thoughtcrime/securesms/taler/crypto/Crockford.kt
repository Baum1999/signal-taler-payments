package org.thoughtcrime.securesms.taler.crypto

/**
 * Crockford-Base32 in der GNUnet-/Taler-Variante: Schluessel, Hashes und
 * Signaturen liegen in Taler-URIs und Exchange-Antworten in dieser Kodierung
 * vor. Portiert aus wallet-core (`taler-util/src/taler-crypto.ts`,
 * `encodeCrock`/`decodeCrock`) bzw. `taler-kotlin-android`s `CyptoUtils`.
 *
 * Abweichend von Standard-Base32 gibt es keine Fuellzeichen, und beim Dekodieren
 * werden O->0, I/L->1 und U->V zusammengefasst.
 */
object Crockford {
  private const val ENC_TABLE = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

  fun encode(data: ByteArray): String {
    val sb = StringBuilder()
    var bitBuf = 0
    var numBits = 0
    var pos = 0
    while (pos < data.size || numBits > 0) {
      if (pos < data.size && numBits < 5) {
        bitBuf = (bitBuf shl 8) or (data[pos++].toInt() and 0xff)
        numBits += 8
      }
      if (numBits < 5) {
        bitBuf = bitBuf shl (5 - numBits)
        numBits = 5
      }
      sb.append(ENC_TABLE[(bitBuf ushr (numBits - 5)) and 31])
      numBits -= 5
    }
    return sb.toString()
  }

  fun decode(encoded: String): ByteArray {
    val out = ByteArray(encoded.length * 5 / 8)
    var bitBuf = 0
    var bitPos = 0
    var readPos = 0
    var outPos = 0
    while (readPos < encoded.length || bitPos > 0) {
      if (readPos < encoded.length) {
        bitBuf = (bitBuf shl 5) or valueOf(encoded[readPos++])
        bitPos += 5
      }
      while (bitPos >= 8) {
        out[outPos++] = ((bitBuf shr (bitPos - 8)) and 0xff).toByte()
        bitPos -= 8
      }
      if (readPos == encoded.length && bitPos > 0) {
        bitBuf = (bitBuf shl (8 - bitPos)) and 0xff
        bitPos = if (bitBuf == 0) 0 else 8
      }
    }
    return out
  }

  /** Dekodiert und besteht auf exakt [expectedLength] Bytes. */
  fun decodeFixed(encoded: String, expectedLength: Int): ByteArray {
    val decoded = decode(encoded)
    require(decoded.size == expectedLength) {
      "erwartet wurden $expectedLength Bytes, dekodiert wurden ${decoded.size}"
    }
    return decoded
  }

  private fun valueOf(c: Char): Int {
    val normalized = when (c) {
      'o', 'O' -> '0'
      'i', 'I', 'l', 'L' -> '1'
      'u', 'U' -> 'V'
      else -> c
    }
    if (normalized in '0'..'9') {
      return normalized - '0'
    }
    val upper = normalized.uppercaseChar()
    if (upper in 'A'..'Z') {
      var skipped = 0
      if (upper > 'I') skipped++
      if (upper > 'L') skipped++
      if (upper > 'O') skipped++
      if (upper > 'U') skipped++
      return upper - 'A' + 10 - skipped
    }
    throw IllegalArgumentException("ungueltiges Crockford-Zeichen")
  }
}
