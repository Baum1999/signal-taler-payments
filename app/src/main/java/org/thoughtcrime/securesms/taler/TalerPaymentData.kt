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
 * - recipientAcis: Bei Gruppen-Split: sortierte Liste der ACIs (als String) der
 *   Empfaenger, nur bei Gruppen-Split-Versand gesetzt (null = keine Split-Info).
 *   Jeder Empfaenger-Client nutzt diese Liste, um die eigene Rolle/den eigenen
 *   Index zu bestimmen (siehe GroupSplitCard.kt)
 */
@Serializable
data class TalerPaymentData(
    val legacyText: String,
    val version: Int = 1,
    val includeSelf: Boolean? = null,
    val totalAmount: String? = null,
    val uri: List<String>,
    val recipientAcis: List<String>? = null
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
