package org.thoughtcrime.securesms.taler

import net.taler.wallet.link.TalerOperationStatus
import net.taler.wallet.link.TalerUriKind
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Prueft den Resolver ohne Netz: ein Interceptor beantwortet die Anfragen, die
 * Contracts stammen aus der Referenzimplementierung (siehe
 * [org.thoughtcrime.securesms.taler.crypto.TalerContractCryptoTest]).
 */
class TalerPeerContractResolverTest {

  private val contractPrivateKey = "0440Y5GX4GNK4EA08X75AQ33D9RQGZW6HPA9Q8N9P2VVXHECTFD0"
  private val contractPublicKey = "WG1GK66FTPPHE8Y1D7WNDAGBKTW636TSJAYP2B1AYGMEQHWZHQR0"
  private val pursePublicKey = "XNHH63ZEN13M3YM8A6WKXBFZJQNPSM8XQ0CZGS8C29CV84CA63T0"

  private val pushContract =
    "1C71250Q38EJ08S654P2YCHN70XKWGA48X54TM20HZKE2VGV0S1ED5P85DABH348J189TBZ6KX57ZDT6GS2JA5AY3EE15C0CQ4RF6C6KTHE635877HMEQ64CNSP291WKAANT5R7GJ638N6VTRQ2HJRJA7DFGS8N21THPWVZAK2BZTW0Y47F3QV9FZHFY9K6A555QNT9PMCHPG6FVAX8MWEFA2Q292VT676ENDGTN21S01CN01BATPZ4GVXQ23Z9T1CQMP8BTZF3TFA3AER9YHT44GC4A7S8"
  private val pullContract =
    "1C71250Q38EJ08S654P2YCHN70XKWGA48X54TM521C7ENA5HXWCPSN9FC7BJM6012H4KN4HKVMH5TZ9VKYM3Y62X9FA3043MEKSQTZV3ZRAXSBRBR0QCQ2N5MN0NB25QW7HAJT3Z33JYPXC7DNXHA2RDQWYTPACVM0BFZN0AXM68KJYQW75FDTDT0VDQ2RSDPXCH0K5SC9WT1S1J0QZZPY7W5T38R"

  private val requestedUrls = mutableListOf<String>()

  private fun resolverAnswering(answer: (url: String) -> Pair<Int, String>): TalerPeerContractResolver {
    val client = OkHttpClient.Builder()
      .addInterceptor(
        Interceptor { chain ->
          val url = chain.request().url.toString()
          requestedUrls += url
          val (code, body) = answer(url)
          Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        }
      )
      .build()
    return TalerPeerContractResolver(client)
  }

  private fun contractJson(econtract: String) = """{"purse_pub":"$pursePublicKey","econtract":"$econtract"}"""

  @Test
  fun resolvesOpenPayPushAgainstExchangeFromUri() {
    val resolver = resolverAnswering { url ->
      when {
        url.contains("/contracts/") -> 200 to contractJson(pushContract)
        url.contains("/purses/") -> 200 to """{"balance":"KUDOS:12.5"}"""
        else -> 500 to ""
      }
    }

    val result = resolver.resolve("taler://pay-push/exchange.example.com:8080/sub/$contractPrivateKey")

    assertEquals(
      listOf(
        "https://exchange.example.com:8080/sub/contracts/$contractPublicKey",
        "https://exchange.example.com:8080/sub/purses/$pursePublicKey/deposit"
      ),
      requestedUrls
    )
    val preview = (result as TalerContractResolution.Ergebnis).preview
    assertEquals(TalerUriKind.PAY_PUSH, preview.uriKind)
    assertEquals(TalerOperationStatus.OFFEN, preview.status)
    assertEquals("12.5", preview.amount)
    assertEquals("KUDOS", preview.currency)
    assertEquals("Pizza fuer Signal", preview.summary)
    assertEquals("https://exchange.example.com:8080/sub/", preview.exchangeBaseUrl)
    assertEquals(4102444800L * 1000, preview.expirationTimestamp)
  }

  @Test
  fun payPushCountsAsAcceptedOnceMerged() {
    val resolver = resolverAnswering { url ->
      if (url.contains("/contracts/")) 200 to contractJson(pushContract)
      else 200 to """{"balance":"KUDOS:0","merge_timestamp":{"t_s":1700000000}}"""
    }

    val result = resolver.resolve("taler://pay-push/exchange.example.com/$contractPrivateKey")

    assertEquals(TalerOperationStatus.ANGENOMMEN, (result as TalerContractResolution.Ergebnis).preview.status)
  }

  @Test
  fun payPullAsksMergeEndpointAndCountsAsAcceptedOnceDeposited() {
    val resolver = resolverAnswering { url ->
      if (url.contains("/contracts/")) 200 to contractJson(pullContract)
      else 200 to """{"balance":"EUR:3.20","deposit_timestamp":{"t_s":1700000000},"merge_timestamp":{"t_s":"never"}}"""
    }

    val result = resolver.resolve("taler://pay-pull/exchange.example.com/$contractPrivateKey")

    assertTrue(requestedUrls.last().endsWith("/purses/$pursePublicKey/merge"))
    val preview = (result as TalerContractResolution.Ergebnis).preview
    assertEquals(TalerOperationStatus.ANGENOMMEN, preview.status)
    assertEquals("EUR", preview.currency)
    assertEquals(null, preview.expirationTimestamp)
  }

  @Test
  fun ignoresQueryParametersAppendedForReturnFlow() {
    val resolver = resolverAnswering { url ->
      if (url.contains("/contracts/")) 200 to contractJson(pushContract) else 200 to "{}"
    }

    resolver.resolve("taler://pay-push/exchange.example.com/$contractPrivateKey?correlationId=abc&returnUri=signalfuergnu%3A%2F%2Ftaler-return")

    assertEquals("https://exchange.example.com/contracts/$contractPublicKey", requestedUrls.first())
  }

  @Test
  fun unknownContractIsExpired() {
    val resolver = resolverAnswering { 404 to "" }

    val result = resolver.resolve("taler://pay-push/exchange.example.com/$contractPrivateKey")

    assertEquals(TalerContractResolution.Fehlgeschlagen(TalerPaymentStatus.ABGELAUFEN), result)
  }

  @Test
  fun serverErrorIsOfflineSoPollingRetries() {
    val resolver = resolverAnswering { 503 to "" }

    val result = resolver.resolve("taler://pay-push/exchange.example.com/$contractPrivateKey")

    assertEquals(TalerContractResolution.Offline, result)
  }

  @Test
  fun networkFailureIsOffline() {
    val client = OkHttpClient.Builder()
      .addInterceptor(Interceptor { throw IOException("keine Verbindung") })
      .build()

    val result = TalerPeerContractResolver(client).resolve("taler://pay-push/exchange.example.com/$contractPrivateKey")

    assertEquals(TalerContractResolution.Offline, result)
  }

  @Test
  fun contractThatDoesNotDecryptIsInvalid() {
    val resolver = resolverAnswering { url ->
      if (url.contains("/contracts/")) 200 to contractJson(pullContract) else 200 to "{}"
    }

    // pay-pull-Chiffrat unter einer pay-push-URI: falsche KDF-Richtung.
    val result = resolver.resolve("taler://pay-push/exchange.example.com/$contractPrivateKey")

    assertEquals(TalerContractResolution.Fehlgeschlagen(TalerPaymentStatus.UNGUELTIG), result)
  }

  @Test
  fun malformedUriIsInvalidWithoutAnyRequest() {
    val resolver = resolverAnswering { 200 to "{}" }

    assertEquals(
      TalerContractResolution.Fehlgeschlagen(TalerPaymentStatus.UNGUELTIG),
      resolver.resolve("taler://pay-push/exchange.example.com/not-a-key")
    )
    assertEquals(
      TalerContractResolution.Fehlgeschlagen(TalerPaymentStatus.UNGUELTIG),
      resolver.resolve("taler://pay/merchant.example.com/order/")
    )
    assertTrue(requestedUrls.isEmpty())
  }
}
