/*
 * This file is part of GNU Taler integration for Signal
 * (C) 2026 Taler Systems S.A.
 *
 * GNU Taler is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3, or (at your option) any later version.
 *
 * GNU Taler is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * GNU Taler; see the file COPYING.  If not, see <http://www.gnu.org/licenses/>
 */

package org.thoughtcrime.securesms.taler

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Datenstruktur für JSON-Format in Gruppen-Split-Transaktionen.
 * Wird von Taler an Signal gesendet, um Metadaten über die Zahlung zu transportieren.
 *
 * - legacyText: Fallback-Text für ältere Clients, die das JSON-Format nicht verstehen
 * - version: Versionsnummer des JSON-Formats (aktuell 1)
 * - includeSelf: Bei Gruppen-Split: ob der Sender sich selbst mitgezählt hat (null = keine Split-Info)
 * - totalAmount: Bei Gruppen-Split: der ursprüngliche Gesamtbetrag vor dem Split (null = keine Split-Info)
 * - uri: Liste der Taler-URIs (bei einem Gruppen-Split-Versand eine pro Empfaenger-Anteil,
 *   siehe PROMPT_parallel_group_split.md; bei einer regulaeren Einzelzahlung genau 1 Element)
 *
 * Kein recipientAcis-Feld (mehr): die Empfaenger-Rolle/der eigene Index bei
 * einem Gruppen-Split wird nicht mehr aus der Nachricht gelesen, sondern
 * lokal aus der bereits bekannten Gruppenmitgliedschaft hergeleitet (siehe
 * GroupSplitRecipients.kt) - unabhaengig davon, ob die Nachricht dieses
 * Legacy-JSON-Format oder das neuere TalerPaymentPayload traegt.
 */
@Serializable
data class TalerPaymentData(
    val legacyText: String,
    val version: Int = 1,
    val includeSelf: Boolean? = null,
    val totalAmount: String? = null,
    val uri: List<String>
)

/**
 * Strukturierte Zahlungsdaten fuer das Proto-Feld DataMessage.talerPayment
 * (Feld 9000, SignalService.proto) - ersetzt TalerPaymentData als
 * Transportformat fuer neu gesendete Nachrichten. legacyText ist hier
 * bewusst nicht enthalten: dieser Text existiert nur noch als Klartext im
 * `body`, fuer Clients ohne Kenntnis dieses Feldes.
 */
data class TalerPaymentPayload(
    val uris: List<String>,
    val version: Int = 1,
    val isGroupSplit: Boolean = false,
    val includeSelf: Boolean? = null,
    val totalAmount: String? = null
)

/**
 * Liefert die in [body] enthaltenen Taler-URIs. Versucht zuerst, [body] als
 * TalerPaymentData-JSON zu parsen - dessen uri-Liste ist die autoritative,
 * sauber deserialisierte Quelle (Meilenstein 2,
 * PROMPT_parallel_group_split.md: ein Gruppen-Split-Versand traegt hier N
 * Elemente). Nur wenn das fehlschlaegt oder die Liste leer ist, faellt dies
 * auf [TalerUriDetector.findUris] zurueck (Klartext-URI-Nachrichten aelterer
 * Taler-Versionen ohne JSON-Unterstuetzung).
 *
 * Wichtig, NICHT einfach TalerUriDetector.findUris() direkt auf einen
 * erkannten JSON-Body anwenden: dessen Regex ist reine \S+-Texterkennung
 * ueber freien Text. kotlinx.serialization erzeugt kompakte
 * (leerzeichenfreie) JSON-Ausgabe, in der auf eine URI direkt JSON-Syntax
 * folgt (schliessendes Anfuehrungszeichen/Klammer/etc. ohne Leerzeichen
 * dazwischen) - der Regex-Treffer wuerde diese Zeichen an die URI anhaengen
 * und sie damit ungueltig machen (TRAILING_PUNCTUATION in TalerUriDetector
 * deckt kein schliessendes Anfuehrungszeichen ab). Bei mehreren URIs im
 * selben JSON-Array wuerde die fehlende Trennung durch Leerzeichen sie sogar
 * zu einem einzigen, komplett unbrauchbaren "Treffer" verschmelzen.
 */
/**
 * Wie [urisFromMessageBody] intern - defensiv, weil [body] bei aelteren
 * Nachrichten oder Fremd-Clients reiner Klartext ohne JSON sein kann.
 * Oeffentlich (statt in TalerPaymentCardPresenter privat), damit auch
 * TalerMenuState (Long-Press-Menue der Gruppenkarte) dieselbe Parse-Logik
 * nutzen kann, ohne sie zu duplizieren.
 */
fun parsePaymentDataOrNull(body: String): TalerPaymentData? = try {
    Json.decodeFromString<TalerPaymentData>(body)
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

fun urisFromMessageBody(body: String): List<String> {
    val paymentData = try {
        Json.decodeFromString<TalerPaymentData>(body)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
    return if (paymentData != null && paymentData.uri.isNotEmpty()) {
        paymentData.uri
    } else {
        TalerUriDetector.findUris(body)
    }
}
