package org.thoughtcrime.securesms.taler.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Die offiziellen GNU-Taler-Testvektoren (`taler-exchange-tvg`) fuer die
 * Krypto-Kette, mit der Signal Peer-Contract-Terms selbst aufloest. Uebernommen
 * aus der im Repo liegenden Referenzimplementierung
 * (`wallet-core/packages/taler-util/src/taler-crypto.test.ts` und
 * `xsalsa20poly1305.test.ts`).
 *
 * Diese Tests sind der eigentliche Nachweis, dass die Eigenimplementierung
 * byte-identisch zu wallet-core rechnet - ohne Geraet, ohne Taler-App und ohne
 * laufende Exchange-Instanz.
 */
class TalerCryptoVectorTest {

  @Test
  fun crockfordRoundTrip() {
    val input = "Hello, World"
    val encoded = Crockford.encode(input.toByteArray(Charsets.UTF_8))
    assertEquals(input, String(Crockford.decode(encoded), Charsets.UTF_8))
  }

  @Test
  fun hashCodeVector() {
    val input = "91JPRV3F5GG4EKJN41A62V35E8"
    val expected =
      "CW96WR74JS8T53EC8GKSGD49QKH4ZNFTZXDAWMMV5GJ1E4BM6B8GPN5NVHDJ8ZVXNCW7Q4WBYCV61HCA3PZC2YJD850DT29RHHN7ESR"

    assertEquals(expected, Crockford.encode(TalerCrypto.sha512(Crockford.decode(input))))
  }

  @Test
  fun eddsaPublicKeyVector() {
    val privateKey = "9TM70AKDTS57AWY9JK2J4TMBTMW6K62WHHGZWYDG0VM5ABPZKD40"
    val publicKey = "8GSJZ649T2PXMKZC01Y4ANNBE7MF14QVK9SQEC4E46ZHKCVG8AS0"

    assertEquals(publicKey, Crockford.encode(Ed25519.getPublicKey(Crockford.decode(privateKey))))
  }

  @Test
  fun kdfVector() {
    val salt = "94KPT83PCNS7J83KC5P78Y8"
    val ikm = "94KPT83MD1JJ0WV5CDS6AX10D5Q70XBM41NPAY90DNGQ8SBJD5GPR"
    val context =
      "94KPT83141HPYVKMCNW78833D1TPWTSC41GPRWVF41NPWVVQDRG62WS04XMPWSKF4WG6JVH0EHM6A82J8S1G"
    val expected =
      "GTMR4QT05Z9WF5HKVG0WK9RPXGHSMHJNW377G9GJXCA8B0FEKPF4D27RJMSJZYWSQNTBJ5EYVV7ZW18B48Z0JVJJ80RHB706Y96Q358"

    val derived = TalerKdf.derive(
      outputLength = 64,
      ikm = Crockford.decode(ikm),
      salt = Crockford.decode(salt),
      info = Crockford.decode(context)
    )

    assertEquals(expected, Crockford.encode(derived))
  }

  @Test
  fun keyExchangeVector() {
    val ecdhePrivate = "4AFZWMSGTVCHZPQ0R81NWXDCK4N58G7SDBBE5KXE080Y50370JJG"
    val eddsaPrivate = "1KG54M8T3X8BSFSZXCR3SQBSR7Y9P53NX61M864S7TEVMJ2XVPF0"
    val eddsaPublic = "7BXWKG6N224C57RTDV8XEAHR108HG78NMA995BE8QAT5GC1S7E80"
    val keyMaterial =
      "PKZ42Z56SVK2796HG1QYBRJ6ZQM2T9QGA3JA4AAZ8G7CWK9FPX175Q9JE5P0ZAX3HWWPHAQV4DPCK10R9X3SAXHRV0WF06BHEC2ZTKR"

    assertEquals(eddsaPublic, Crockford.encode(Ed25519.getPublicKey(Crockford.decode(eddsaPrivate))))
    assertEquals(
      keyMaterial,
      Crockford.encode(
        TalerCrypto.keyExchangeEcdhEddsa(
          Crockford.decode(ecdhePrivate),
          Crockford.decode(eddsaPublic)
        )
      )
    )
  }

  @Test
  fun secretBoxStaysNaClCompatible() {
    val key = ByteArray(32) { it.toByte() }
    val nonce = ByteArray(24) { (it + 32).toByte() }
    val message = "GNU Taler Noble migration".toByteArray(Charsets.UTF_8)
    val ciphertext = hex(
      "dcc697b8d4db9561740a192bf466eac4" +
        "b6171885194636f37b151b016e24d2d4d4dbdaf9906d4a1bd2"
    )

    assertArrayEquals(message, XSalsa20Poly1305.open(ciphertext, nonce, key))
  }

  @Test
  fun secretBoxRejectsTamperedCiphertext() {
    val key = ByteArray(32) { it.toByte() }
    val nonce = ByteArray(24) { (it + 32).toByte() }
    val ciphertext = hex(
      "dcc697b8d4db9561740a192bf466eac4" +
        "b6171885194636f37b151b016e24d2d4d4dbdaf9906d4a1bd2"
    )
    ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()

    assertNull(XSalsa20Poly1305.open(ciphertext, nonce, key))
  }

  private fun hex(value: String): ByteArray {
    return ByteArray(value.length / 2) {
      value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
  }
}
