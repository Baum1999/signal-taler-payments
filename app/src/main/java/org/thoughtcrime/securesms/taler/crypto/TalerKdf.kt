package org.thoughtcrime.securesms.taler.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Talers eigene KDF, portiert aus wallet-core (`taler-util/src/taler-crypto.ts`,
 * `kdf`). Das ist *kein* Standard-HKDF und darf nicht durch libsignals
 * `HKDF.deriveSecrets` ersetzt werden: Extract laeuft ueber HMAC-SHA512,
 * Expand ueber HMAC-SHA256, der Blockzaehler steht am *Ende* des Puffers und
 * `info` steht hinter dem vorangegangenen Block.
 */
object TalerKdf {
  private const val BLOCK_SIZE = 32

  fun derive(outputLength: Int, ikm: ByteArray, salt: ByteArray?, info: ByteArray?): ByteArray {
    val effectiveSalt = salt ?: ByteArray(64)
    val effectiveInfo = info ?: ByteArray(0)

    val prk = hmac("HmacSHA512", effectiveSalt, ikm)

    val blocks = (outputLength + BLOCK_SIZE - 1) / BLOCK_SIZE
    val output = ByteArray(blocks * BLOCK_SIZE)
    for (i in 0 until blocks) {
      val buffer: ByteArray
      if (i == 0) {
        buffer = ByteArray(effectiveInfo.size + 1)
        effectiveInfo.copyInto(buffer, 0)
      } else {
        buffer = ByteArray(BLOCK_SIZE + effectiveInfo.size + 1)
        output.copyInto(buffer, 0, (i - 1) * BLOCK_SIZE, i * BLOCK_SIZE)
        effectiveInfo.copyInto(buffer, BLOCK_SIZE)
      }
      buffer[buffer.size - 1] = (i + 1).toByte()
      hmac("HmacSHA256", prk, buffer).copyInto(output, i * BLOCK_SIZE)
    }
    return output.copyOf(outputLength)
  }

  private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance(algorithm)
    mac.init(SecretKeySpec(key, algorithm))
    return mac.doFinal(data)
  }
}
