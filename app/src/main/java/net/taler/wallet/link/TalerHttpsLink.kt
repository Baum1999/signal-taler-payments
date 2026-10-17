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

// Diese Datei muss identisch auch im Signal-Repo vorliegen, siehe scripts/sync-aidl.sh.

/**
 * https-Links fuer Taler-Aktionen nach DD 103 "HTTPS Links for Wallet Actions"
 * (https://git.gnunet.org/taler/taler-docs/file/design-documents/103-https-wallet-links.rst.html,
 * siehe auch docs/superpowers/specs/2026-10-16-taler-dd103-links-design.md):
 * `https://<host>/link#<aktion>`, damit Messenger den Link automatisch
 * anklickbar machen. taler-ios erzeugt dieselbe Kurzform.
 *
 * Der Payload steht im Fragment: Browser schicken es nie an den Server, auch
 * Link-Vorschau-Crawler der Messenger sehen den Contract-Schluessel nicht.
 * unwrap() liest ihn lokal, ohne Netzwerkanfrage.
 *
 * Bewusst kein Percent-Encoding: alle Zeichen, die in einem taler://-Link
 * vorkommen (Schema, Host, Pfadsegmente, Crockford-Alphabet 0-9A-Z ohne
 * ILOU), sind im Fragment bereits zulaessig.
 */
object TalerHttpsLink {
    private const val TALER_SCHEME = "taler://"

    /**
     * Regex-Quelltext (ohne Flags) fuer den Anfang eines akzeptierten Links
     * bis einschliesslich '#'. Aufrufer haengen ihre eigene Endzeichen-Klasse
     * an und matchen case-insensitiv (TalerUriDetector, TalerUriExtractor).
     */
    val LINK_PREFIX_PATTERN: String =
        "https://(?:" + TalerLinkHosts.ACCEPTED_HOSTS.joinToString("|") { Regex.escape(it) } + ")/link/?#"

    private val LINK_REGEX = Regex("^$LINK_PREFIX_PATTERN(.*)$", RegexOption.IGNORE_CASE)

    // Kurzform ohne Schema: muss mit einem Aktionsnamen + "/" beginnen
    // (pay-push/..., withdraw/...), damit z.B. "javascript:..." nicht zu
    // "taler://javascript:..." wird.
    private val SHORT_FORM = Regex("^[A-Za-z0-9-]+/.*")

    fun wrap(talerUri: String): String {
        val trimmed = talerUri.trim()
        val fragment = if (trimmed.startsWith(TALER_SCHEME, ignoreCase = true)) {
            trimmed.substring(TALER_SCHEME.length)
        } else {
            trimmed
        }
        return "https://${TalerLinkHosts.WRAP_HOST}/link#$fragment"
    }

    /**
     * Gibt den eingebetteten taler://-Link zurueck, falls [uri] ein Link auf
     * einen Host aus [TalerLinkHosts.ACCEPTED_HOSTS] mit exakt dem Pfad /link
     * (bzw. /link/) ist - sonst [uri] getrimmt, aber unveraendert. Das gilt
     * auch fuer leere Fragmente oder fremde Schemata im Fragment; die
     * Klassifizierung als "keine Taler-Aktion" bleibt Aufgabe der aufrufenden
     * Stelle, siehe TalerUriParser.classify().
     */
    fun unwrap(uri: String): String {
        val trimmed = uri.trim()
        val fragment = LINK_REGEX.find(trimmed)?.groupValues?.get(1) ?: return trimmed
        val lower = fragment.lowercase()
        return when {
            lower.startsWith(TALER_SCHEME) || lower.startsWith("ext+$TALER_SCHEME") -> fragment
            fragment.startsWith("/") && SHORT_FORM.matches(fragment.substring(1)) -> "taler:/$fragment"
            SHORT_FORM.matches(fragment) -> TALER_SCHEME + fragment
            else -> trimmed
        }
    }
}
