package org.thoughtcrime.securesms.taler.crypto

import java.util.zip.Inflater

/**
 * Entschluesselt die Contract-Terms einer Peer-Zahlung, wie sie der Exchange
 * unter `contracts/$CONTRACT_PUB` ausliefert. Gegenstueck zu wallet-cores
 * `decryptContractForMerge`/`decryptContractForDeposit`
 * (`taler-util/src/taler-crypto.ts`).
 *
 * Bewusst frei von Android und Netz, damit die komplette Kette gegen die
 * Referenzimplementierung testbar ist.
 */
object TalerContractCrypto {
  private const val NONCE_SIZE = 24
  private const val HEADER_SIZE = 8
  private const val MERGE_PRIVATE_KEY_SIZE = 32
  private const val MAX_CONTRACT_TERMS_LENGTH = 1024L * 1024L * 40L

  enum class Direction(val formatTag: Long, val kdfInfo: String, val payloadOffset: Int) {
    /** pay-push: der Empfaenger fuehrt die Purse zusammen. */
    MERGE(0L, "p2p-merge-contract", HEADER_SIZE + MERGE_PRIVATE_KEY_SIZE),

    /** pay-pull: der Schuldner zahlt in die Purse ein. */
    DEPOSIT(1L, "p2p-deposit-contract", HEADER_SIZE)
  }

  /**
   * Gibt das Contract-Terms-JSON zurueck, oder `null`, wenn das Chiffrat nicht
   * zu Schluessel und Richtung passt oder formal ungueltig ist.
   */
  fun decrypt(
    encryptedContract: ByteArray,
    pursePublicKey: ByteArray,
    contractPrivateKey: ByteArray,
    direction: Direction
  ): String? {
    if (encryptedContract.size <= NONCE_SIZE) {
      return null
    }

    val keySeed = TalerCrypto.keyExchangeEcdhEddsa(contractPrivateKey, pursePublicKey)
    val nonce = encryptedContract.copyOf(NONCE_SIZE)
    val key = TalerKdf.derive(32, keySeed, nonce, direction.kdfInfo.toByteArray(Charsets.UTF_8))

    val plaintext = XSalsa20Poly1305.open(
      encryptedContract.copyOfRange(NONCE_SIZE, encryptedContract.size),
      nonce,
      key
    ) ?: return null

    if (plaintext.size <= direction.payloadOffset) {
      return null
    }
    if (readUint32(plaintext, 0) != direction.formatTag) {
      return null
    }
    val contentLength = readUint32(plaintext, 4)
    if (contentLength < 1 || contentLength > MAX_CONTRACT_TERMS_LENGTH) {
      return null
    }

    val inflated = inflate(
      plaintext.copyOfRange(direction.payloadOffset, plaintext.size),
      contentLength.toInt()
    ) ?: return null

    // Die Referenz haengt vor dem Komprimieren ein '\0' an.
    return String(inflated, 0, inflated.size - 1, Charsets.UTF_8)
  }

  /**
   * zlib, nicht gzip - wallet-core komprimiert mit `fflate.zlibSync`. Die
   * deklarierte Laenge stammt aus dem Chiffrat der Gegenseite und begrenzt
   * bewusst, wie viel Speicher sie uns abverlangen kann.
   */
  private fun inflate(compressed: ByteArray, expectedLength: Int): ByteArray? {
    val inflater = Inflater()
    try {
      inflater.setInput(compressed)
      val out = ByteArray(expectedLength)
      var written = 0
      while (written < expectedLength && !inflater.finished()) {
        val count = inflater.inflate(out, written, expectedLength - written)
        if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
          break
        }
        written += count
      }
      return if (written == expectedLength) out else null
    } catch (e: java.util.zip.DataFormatException) {
      return null
    } finally {
      inflater.end()
    }
  }

  private fun readUint32(source: ByteArray, offset: Int): Long {
    return ((source[offset].toLong() and 0xff) shl 24) or
      ((source[offset + 1].toLong() and 0xff) shl 16) or
      ((source[offset + 2].toLong() and 0xff) shl 8) or
      (source[offset + 3].toLong() and 0xff)
  }
}
