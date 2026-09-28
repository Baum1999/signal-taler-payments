package org.thoughtcrime.securesms.taler

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.taler.wallet.link.PaymentPreviewResult
import net.taler.wallet.link.TalerOperationStatus
import net.taler.wallet.link.TalerUriKind
import net.taler.wallet.link.TalerUriParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.taler.crypto.Crockford
import org.thoughtcrime.securesms.taler.crypto.Ed25519
import org.thoughtcrime.securesms.taler.crypto.TalerContractCrypto
import java.io.IOException
import java.util.Locale

/**
 * Loest `pay-push`/`pay-pull`-URIs ohne die Taler-App auf: Contract-Chiffrat
 * beim Exchange abholen, lokal entschluesseln, Purse-Status abfragen. Beides
 * sind oeffentliche, unauthentifizierte Endpunkte; der zum Entschluesseln
 * noetige Einmal-Schluessel steckt in der URI selbst.
 *
 * Gegenstueck in wallet-core: `pay-peer-push-credit.ts` (Abruf) und
 * `taler-util/src/taler-crypto.ts` (`decryptContractForMerge`/`ForDeposit`).
 */
class TalerPeerContractResolver(
  private val httpClient: OkHttpClient = AppDependencies.okHttpClient
) {

  fun resolve(uri: String): TalerContractResolution {
    val parsed = parseUri(uri) ?: return TalerContractResolution.Fehlgeschlagen(TalerPaymentStatus.UNGUELTIG)

    return try {
      resolveParsed(parsed)
    } catch (e: IOException) {
      Log.w(TAG, "Exchange nicht erreichbar fuer ${TalerCorrelation.shortHash(uri)}", e)
      TalerContractResolution.Offline
    } catch (e: Exception) {
      Log.w(TAG, "Contract-Aufloesung fehlgeschlagen fuer ${TalerCorrelation.shortHash(uri)}", e)
      TalerContractResolution.Fehlgeschlagen(TalerPaymentStatus.UNGUELTIG)
    }
  }

  private fun resolveParsed(parsed: ParsedPeerUri): TalerContractResolution {
    val contractPublicKey = Crockford.encode(Ed25519.getPublicKey(parsed.contractPrivateKey))

    val contractBody = get("${parsed.exchangeBaseUrl}contracts/$contractPublicKey")
      ?: return TalerContractResolution.Fehlgeschlagen(TalerPaymentStatus.ABGELAUFEN)
    val contract = JSON.decodeFromString(ContractResponse.serializer(), contractBody)

    val direction = if (parsed.kind == TalerUriKind.PAY_PUSH) TalerContractCrypto.Direction.MERGE else TalerContractCrypto.Direction.DEPOSIT
    val termsJson = TalerContractCrypto.decrypt(
      encryptedContract = Crockford.decode(contract.encryptedContract),
      pursePublicKey = Crockford.decode(contract.pursePublicKey),
      contractPrivateKey = parsed.contractPrivateKey,
      direction = direction
    ) ?: return TalerContractResolution.Fehlgeschlagen(TalerPaymentStatus.UNGUELTIG)
    val terms = JSON.decodeFromString(ContractTerms.serializer(), termsJson)

    val expiration = timestampMillis(terms.purseExpiration)
    if (expiration != null && expiration <= System.currentTimeMillis()) {
      return TalerContractResolution.Fehlgeschlagen(TalerPaymentStatus.ABGELAUFEN)
    }

    val statusPath = if (parsed.kind == TalerUriKind.PAY_PUSH) "deposit" else "merge"
    val statusBody = get("${parsed.exchangeBaseUrl}purses/${contract.pursePublicKey}/$statusPath")
      ?: return TalerContractResolution.Fehlgeschlagen(TalerPaymentStatus.ABGELAUFEN)
    val purseStatus = JSON.decodeFromString(PurseStatusResponse.serializer(), statusBody)

    val (amount, currency) = splitAmount(terms.amount)

    return TalerContractResolution.Ergebnis(
      PaymentPreviewResult(
        uriKind = parsed.kind,
        status = if (isSettled(parsed.kind, purseStatus)) TalerOperationStatus.ANGENOMMEN else TalerOperationStatus.OFFEN,
        amount = amount,
        currency = currency,
        exchangeBaseUrl = parsed.exchangeBaseUrl,
        summary = terms.summary,
        expirationTimestamp = expiration
      )
    )
  }

  /**
   * Der Purse-Status ist die einzige Quelle, aus der Signal ohne wallet-core
   * ableiten kann, ob die Gegenseite den Vorgang schon abgeschlossen hat:
   * beim Push-Fall wird die Purse vom Empfaenger zusammengefuehrt (merge),
   * beim Pull-Fall zahlt der Schuldner ein (deposit).
   */
  private fun isSettled(kind: TalerUriKind, status: PurseStatusResponse): Boolean {
    val timestamp = if (kind == TalerUriKind.PAY_PUSH) status.mergeTimestamp else status.depositTimestamp
    return timestampMillis(timestamp) != null
  }

  /** Gibt `null` zurueck, wenn der Exchange die Ressource nicht (mehr) kennt. */
  private fun get(url: String): String? {
    val request = Request.Builder().url(url).get().build()
    httpClient.newCall(request).execute().use { response ->
      if (response.code == 404 || response.code == 410) {
        return null
      }
      if (!response.isSuccessful) {
        throw IOException("unerwarteter Status ${response.code}")
      }
      return response.body?.string() ?: throw IOException("leere Antwort")
    }
  }

  private fun parseUri(uri: String): ParsedPeerUri? {
    val kind = TalerUriParser.classify(uri)
    if (kind != TalerUriKind.PAY_PUSH && kind != TalerUriKind.PAY_PULL) {
      return null
    }

    val withoutQuery = uri.trim().substringBefore('?')
    val lowercase = withoutQuery.lowercase(Locale.ROOT)
    val scheme = when {
      lowercase.startsWith("taler+http://") -> "http"
      else -> "https"
    }
    val body = withoutQuery.substring(withoutQuery.indexOf("://") + 3)

    val segments = body.split('/').filter { it.isNotEmpty() }
    if (segments.size < 3) {
      return null
    }

    val host = segments[1]
    val path = segments.subList(2, segments.size - 1)
    val baseUrl = buildString {
      append(scheme).append("://").append(host).append('/')
      for (segment in path) {
        append(segment).append('/')
      }
    }

    val contractPrivateKey = try {
      Crockford.decodeFixed(segments.last(), 32)
    } catch (e: IllegalArgumentException) {
      return null
    }

    return ParsedPeerUri(kind, baseUrl, contractPrivateKey)
  }

  private fun splitAmount(amount: String): Pair<String?, String?> {
    val separator = amount.indexOf(':')
    if (separator <= 0) {
      return amount to null
    }
    return amount.substring(separator + 1) to amount.substring(0, separator)
  }

  private fun timestampMillis(timestamp: TalerTimestamp?): Long? {
    val seconds = (timestamp?.seconds as? JsonPrimitive)?.longOrNull ?: return null
    return seconds * 1000
  }

  private class ParsedPeerUri(
    val kind: TalerUriKind,
    val exchangeBaseUrl: String,
    val contractPrivateKey: ByteArray
  )

  @Serializable
  private class ContractResponse(
    @kotlinx.serialization.SerialName("purse_pub") val pursePublicKey: String,
    @kotlinx.serialization.SerialName("econtract") val encryptedContract: String
  )

  @Serializable
  private class PurseStatusResponse(
    @kotlinx.serialization.SerialName("deposit_timestamp") val depositTimestamp: TalerTimestamp? = null,
    @kotlinx.serialization.SerialName("merge_timestamp") val mergeTimestamp: TalerTimestamp? = null
  )

  @Serializable
  private class ContractTerms(
    val amount: String,
    val summary: String = "",
    @kotlinx.serialization.SerialName("purse_expiration") val purseExpiration: TalerTimestamp? = null
  )

  /** `t_s` ist entweder eine Zahl oder der String "never". */
  @Serializable
  private class TalerTimestamp(
    @kotlinx.serialization.SerialName("t_s") val seconds: JsonElement? = null
  )

  companion object {
    private val TAG = Log.tag(TalerPeerContractResolver::class.java)


    private val JSON = Json { ignoreUnknownKeys = true }
  }
}

sealed interface TalerContractResolution {
  data class Ergebnis(val preview: PaymentPreviewResult) : TalerContractResolution

  /** Exchange nicht erreichbar - der Job soll es spaeter erneut versuchen. */
  data object Offline : TalerContractResolution

  data class Fehlgeschlagen(val status: TalerPaymentStatus) : TalerContractResolution
}
