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
 * Einzige Stelle mit Domains fuer die https-Links nach DD 103 (siehe
 * [TalerHttpsLink]). Forks mit eigener Link-Domain ueberschreiben nur diese
 * Datei, damit ein Rebase auf den Hauptzweig konfliktfrei bleibt.
 */
object TalerLinkHosts {
    /** Domain, mit der [TalerHttpsLink.wrap] neue Links erzeugt. */
    const val WRAP_HOST = "wallet.taler.net"

    /** Domains, deren /link#-Links [TalerHttpsLink.unwrap] akzeptiert. */
    val ACCEPTED_HOSTS = listOf("wallet.taler.net")
}
