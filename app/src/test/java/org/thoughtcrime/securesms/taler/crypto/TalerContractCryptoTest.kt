package org.thoughtcrime.securesms.taler.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Golden-Vektoren fuer die komplette Contract-Entschluesselung. Erzeugt mit der
 * im Repo liegenden Referenz (`wallet-core/packages/taler-util`,
 * `encryptContractForMerge`/`encryptContractForDeposit`) aus festen Schluesseln
 * und fester Nonce, und dort per Rueckentschluesselung gegengeprueft.
 *
 * Ergaenzt [TalerCryptoVectorTest]: der prueft die einzelnen Primitiven, dieser
 * hier ihr Zusammenspiel - KDF-Info je Richtung, Nonce-Aufteilung, Header,
 * mergePriv-Offset, zlib und das abschliessende NUL-Byte.
 */
class TalerContractCryptoTest {

  private val contractPrivateKey = Crockford.decodeFixed("0440Y5GX4GNK4EA08X75AQ33D9RQGZW6HPA9Q8N9P2VVXHECTFD0", 32)
  private val pursePublicKey = Crockford.decodeFixed("XNHH63ZEN13M3YM8A6WKXBFZJQNPSM8XQ0CZGS8C29CV84CA63T0", 32)

  private val pushContract = Crockford.decode(
    "1C71250Q38EJ08S654P2YCHN70XKWGA48X54TM20HZKE2VGV0S1ED5P85DABH348J189TBZ6KX57ZDT6GS2JA5AY3EE15C0CQ4RF6C6KTHE635877HMEQ64CNSP291WKAANT5R7GJ638N6VTRQ2HJRJA7DFGS8N21THPWVZAK2BZTW0Y47F3QV9FZHFY9K6A555QNT9PMCHPG6FVAX8MWEFA2Q292VT676ENDGTN21S01CN01BATPZ4GVXQ23Z9T1CQMP8BTZF3TFA3AER9YHT44GC4A7S8"
  )
  private val pullContract = Crockford.decode(
    "1C71250Q38EJ08S654P2YCHN70XKWGA48X54TM521C7ENA5HXWCPSN9FC7BJM6012H4KN4HKVMH5TZ9VKYM3Y62X9FA3043MEKSQTZV3ZRAXSBRBR0QCQ2N5MN0NB25QW7HAJT3Z33JYPXC7DNXHA2RDQWYTPACVM0BFZN0AXM68KJYQW75FDTDT0VDQ2RSDPXCH0K5SC9WT1S1J0QZZPY7W5T38R"
  )

  @Test
  fun contractPublicKeyMatchesReference() {
    assertEquals(
      "WG1GK66FTPPHE8Y1D7WNDAGBKTW636TSJAYP2B1AYGMEQHWZHQR0",
      Crockford.encode(Ed25519.getPublicKey(contractPrivateKey))
    )
  }

  @Test
  fun decryptsPayPushContract() {
    assertEquals(
      """{"amount":"KUDOS:12.5","purse_expiration":{"t_s":4102444800},"summary":"Pizza fuer Signal"}""",
      TalerContractCrypto.decrypt(pushContract, pursePublicKey, contractPrivateKey, TalerContractCrypto.Direction.MERGE)
    )
  }

  @Test
  fun decryptsPayPullContract() {
    assertEquals(
      """{"amount":"EUR:3.20","purse_expiration":{"t_s":"never"},"summary":"Kaffee zurueck"}""",
      TalerContractCrypto.decrypt(pullContract, pursePublicKey, contractPrivateKey, TalerContractCrypto.Direction.DEPOSIT)
    )
  }

  @Test
  fun rejectsContractDecryptedInWrongDirection() {
    assertNull(
      TalerContractCrypto.decrypt(pullContract, pursePublicKey, contractPrivateKey, TalerContractCrypto.Direction.MERGE)
    )
  }

  @Test
  fun rejectsContractForDifferentPurse() {
    val otherPurse = pursePublicKey.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    assertNull(
      TalerContractCrypto.decrypt(pushContract, otherPurse, contractPrivateKey, TalerContractCrypto.Direction.MERGE)
    )
  }

  @Test
  fun rejectsTruncatedContract() {
    assertNull(
      TalerContractCrypto.decrypt(pushContract.copyOf(20), pursePublicKey, contractPrivateKey, TalerContractCrypto.Direction.MERGE)
    )
  }
}
