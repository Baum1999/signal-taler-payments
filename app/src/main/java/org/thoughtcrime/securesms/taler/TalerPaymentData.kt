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

/**
 * Datenstruktur für JSON-Format in Gruppen-Split-Transaktionen.
 * Wird von Taler an Signal gesendet, um Metadaten über die Zahlung zu transportieren.
 * 
 * - legacyText: Fallback-Text für ältere Clients, die das JSON-Format nicht verstehen
 * - version: Versionsnummer des JSON-Formats (aktuell 1)
 * - includeSelf: Bei Gruppen-Split: ob der Sender sich selbst mitgezählt hat (null = keine Split-Info)
 * - totalAmount: Bei Gruppen-Split: der ursprüngliche Gesamtbetrag vor dem Split (null = keine Split-Info)
 * - uri: Liste der Taler-URIs (aktuell immer genau 1 Element, für zukünftige Erweiterungen Liste)
 */
@Serializable
data class TalerPaymentData(
    val legacyText: String,
    val version: Int = 1,
    val includeSelf: Boolean? = null,
    val totalAmount: String? = null,
    val uri: List<String>
)
