package org.thoughtcrime.securesms.taler

import android.content.Context
import androidx.annotation.StringRes
import net.taler.wallet.link.TalerUriKind
import net.taler.wallet.link.TalerUriParser
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.model.databaseprotos.MessageExtras

/**
 * Ersetzt den Rohtext einer Taler-Zahlungsnachricht (Klartext plus volle
 * taler://-URI) in sekundaeren Anzeigeoberflaechen - Chatlisten-Vorschau,
 * Benachrichtigungs-Banner - durch ein kurzes, lokalisiertes Label. Die
 * In-Chat-Karte bleibt unberuehrt; Details (URI, Kopieren, QR) liefert sie.
 *
 * Spiegel von `TalerPreviewText.swift` (signal-ios): gleiche Faelle, gleiche
 * Reihenfolge. Einziger bewusster Unterschied: Android nimmt den strukturierten
 * Payload (DataMessage.talerPayment, Feld 9000) als erste Regel, iOS verzichtet
 * darauf (kein Store-Lookup im Renderpfad, siehe Kommentar im Swift-Spiegel);
 * die Body-Heuristik deckt beide Plattformen.
 *
 * Der Helper braucht [Context], weil die Send-/Request-/Bestaetigungsformate
 * auf Android lokalisierte Resources sind (TalerFork_send_message etc.) - der
 * Spiegel zu TalerConfirmationDetector.match(context, text), der aus dem
 * selben Grund Context nimmt. Tests laufen mit Robolectric wie
 * TalerConfirmationDetectorTest.
 *
 * Keine Betraege, Kontonamen oder Status-Suffixe im Label: Signal kennt keine
 * Taler-Salden, und Status-Lookups pro Row waeren N Datenbankzugriffe. Die
 * Vorschau sagt nur "es ist eine Taler-Zahlung dieser Art".
 */
object TalerPreviewText {

  /**
   * Klartext-Saetze vor den URIs einer Zahlungsnachricht. Heute setzt Signal
   * sie selbst (TalerFork_payment_legacy_text, values/values-de); fruehere
   * Versionen bekamen sie von der Taler-App (taler_payment_legacy_text).
   * Bewusst Konstanten statt getString: eine Nachricht eines deutschen
   * Absenders muss auch auf einem englischen Geraet erkannt werden.
   * Spiegel: TalerStrings.legacyText (iOS, nur Englisch).
   */
  private const val LEGACY_TEXT_EN =
    "This is a GNU Taler link. Update Signal or copy the URI below into the GNU Taler app to make the payment."
  private const val LEGACY_TEXT_DE =
    "Dies ist ein GNU-Taler-Link. Aktualisieren Sie Signal oder kopieren Sie den unten stehenden URI in die GNU-Taler-App, um die Zahlung durchzuführen."

  /**
   * true, wenn [body]/[structured] eine Taler-Zahlungsnachricht beschreibt und
   * die Vorschau deshalb das Label statt des Rohtexts zeigen soll.
   */
  fun isTalerMessage(context: Context, structured: TalerPaymentPayload?, body: String): Boolean =
    labelRes(context, structured, body) != null

  /**
   * Label-Resource fuer die Vorschau, oder null, wenn die Nachricht keine
   * Taler-Zahlungsnachricht ist (dann zeigt der Aufrufer den normalen Body).
   *
   * Reihenfolge (genaueste Erkennung zuerst):
   * 1. Bestaetigungsformat - exakter Volltext-Match per TalerConfirmationDetector.
   * 2. Strukturierter Payload (Feld 9000): entscheidend, Body ist dann egal
   *    (auch wenn der Legacy-Text fehlt oder fremd aussieht).
   * 3. Body-Heuristik fuer Nachrichten ohne Payload (fremde Clients,
   *    aeltere Fork-Versionen mit JSON-Body): Resttext (Body abzueglich aller
   *    gefundenen URIs) muss leer oder exakt einer der bekannten Protokollsaetze
   *    sein. Nutzergeschriebener Text um eine URI bleibt vollstaendig sichtbar.
   */
  fun labelRes(context: Context, structured: TalerPaymentPayload?, body: String): Int? {
    if (TalerConfirmationDetector.match(context, body) != null) {
      return R.string.TalerFork_update_accepted
    }

    if (structured != null && structured.uris.isNotEmpty()) {
      return labelForUris(structured.uris)
    }

    // Aeltere Fork-Versionen versendeten TalerPaymentData als JSON im Body.
    // parsePaymentDataOrNull liefert dort die sauber dekodierten URIs;
    // TalerUriDetector wuerde auf dem kompakten JSON stattdessen
    // JSON-Syntax an die URIs haengen (siehe Kommentar in TalerPaymentData.kt).
    val jsonBody = parsePaymentDataOrNull(body)
    if (jsonBody != null && jsonBody.uri.isNotEmpty()) {
      return labelForUris(jsonBody.uri)
    }

    val uris = TalerUriDetector.findUris(body)
    if (uris.isEmpty()) return null

    val rest = normalizeWhitespace(removeUris(body, uris))
    if (rest.isEmpty()) {
      return labelForUris(uris)
    }

    return when {
      matchesFormat(context, rest, R.string.TalerFork_accept_confirmation_message) -> R.string.TalerFork_update_accepted
      matchesFormat(context, rest, R.string.TalerFork_send_message) -> labelForUris(uris)
      matchesFormat(context, rest, R.string.TalerFork_request_message) -> labelForUris(uris)
      rest == normalizeWhitespace(LEGACY_TEXT_EN) || rest == normalizeWhitespace(LEGACY_TEXT_DE) -> labelForUris(uris)
      else -> null
    }
  }

  /**
   * Dekodiert den Proto-Nachschub (Database.proto MessageExtras.talerPayment)
   * in TalerPaymentPayload - gleiches Mapping wie MessageTable.getOutgoingMessage
   * (MessageTable.kt), damit Chatliste und Banner dieselbe Quelle nutzen.
   */
  fun payloadFrom(messageExtras: MessageExtras?): TalerPaymentPayload? =
    messageExtras?.talerPayment?.let {
      TalerPaymentPayload(
        uris = it.uris,
        version = it.version ?: 1,
        isGroupSplit = it.isGroupSplit ?: false,
        includeSelf = it.includeSelf,
        totalAmount = it.totalAmount
      )
    }

  /**
   * Label je nach URI-Anzahl: mehr als eine URI ist ein Gruppen-Split
   * (TalerFork_preview_group_split), sonst Kind-Label der ersten URI.
   * Nicht klassifizierbare URIs (z. B. payto://) fallen auf kind_unknown -
   * kein Crash, keine URI im Label.
   */
  private fun labelForUris(uris: List<String>): Int =
    if (uris.size > 1) {
      R.string.TalerFork_preview_group_split
    } else {
      when (TalerUriParser.classify(uris.first())) {
        TalerUriKind.PAY_PUSH -> R.string.TalerFork_kind_pay_push
        TalerUriKind.PAY_PULL -> R.string.TalerFork_kind_pay_pull
        TalerUriKind.PAY -> R.string.TalerFork_kind_pay
        TalerUriKind.WITHDRAW -> R.string.TalerFork_kind_withdraw
        TalerUriKind.REFUND -> R.string.TalerFork_kind_refund
        null -> R.string.TalerFork_kind_unknown
      }
    }

  /**
   * Vergleicht den normalisierten Resttext gegen ein Nachrichtenformat
   * (TalerFork_send_message / _request_message / _accept_confirmation_message),
   * wobei %1$s fuer jedes moegliche lokalisierte Kind-Label steht und %2$s
   * (die URI) entfaellt. true, wenn der Rest exakt einem dieser Saetze
   * entspricht.
   */
  private fun matchesFormat(context: Context, normalizedRest: String, @StringRes formatRes: Int): Boolean {
    val format = context.getString(formatRes)
    return KIND_LABELS.any { kindLabelRes ->
      val expected = format
        .replace("%1\$s", context.getString(kindLabelRes))
        .replace("%2\$s", "")
      normalizedRest == normalizeWhitespace(expected)
    }
  }

  private val KIND_LABELS = listOf(
    R.string.TalerFork_kind_pay_push,
    R.string.TalerFork_kind_pay_pull,
    R.string.TalerFork_kind_pay,
    R.string.TalerFork_kind_withdraw,
    R.string.TalerFork_kind_refund,
    R.string.TalerFork_kind_unknown
  )

  private fun removeUris(body: String, uris: List<String>): String {
    var rest = body
    for (uri in uris) {
      rest = rest.replace(uri, "")
    }
    return rest
  }

  private fun normalizeWhitespace(text: String): String =
    text.trim().replace(Regex("\\s+"), " ")
}
