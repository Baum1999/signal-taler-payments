/*
 * This file is part of GNU Taler
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

package net.taler.wallet.link

import java.util.Locale

// Diese Datei muss identisch auch im Signal-Repo vorliegen, siehe scripts/sync-aidl.sh.

/**
 * Klassifiziert Taler-URIs anhand ihres Pfad-Praefixes - dieselbe Logik wie
 * `HandleUriScreen.processTalerUri()` (net.taler.wallet.HandleUriScreen.kt),
 * hier nur ohne die dortige HTTP(S)-Redirect-Aufloesung: klassifiziert werden
 * ausschliesslich fertige taler:/ext+taler:-URIs, keine http(s)-Merchant-Links.
 *
 * Beide Apps klassifizieren lokal mit dieser Datei - Signal seit dem Wegfall
 * der App-zu-App-Schnittstelle ebenfalls, statt Taler danach zu fragen.
 */
object TalerUriParser {

    fun classify(uri: String): TalerUriKind? {
        val normalized = uri.trim().lowercase(Locale.ROOT)
        val action = when {
            normalized.startsWith("taler://") -> normalized.removePrefix("taler://")
            normalized.startsWith("ext+taler://") -> normalized.removePrefix("ext+taler://")
            normalized.startsWith("taler+http://") -> normalized.removePrefix("taler+http://")
            else -> return null
        }
        return when {
            action.startsWith("pay-push/") -> TalerUriKind.PAY_PUSH
            action.startsWith("pay-pull/") -> TalerUriKind.PAY_PULL
            action.startsWith("pay/") || action.startsWith("pay-template/") -> TalerUriKind.PAY
            action.startsWith("withdraw/") || action.startsWith("withdraw-exchange/") -> TalerUriKind.WITHDRAW
            action.startsWith("refund/") -> TalerUriKind.REFUND
            else -> null
        }
    }
}
