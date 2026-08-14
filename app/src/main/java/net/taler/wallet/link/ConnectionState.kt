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

// Diese Datei muss identisch auch im Signal-Repo vorliegen, siehe ITalerLink.aidl.
//
// NICHT_VERTRAUENSWUERDIG (Cert-Mismatch) und NICHT_INSTALLIERT entstehen rein
// lokal beim Aufrufer (Signal bindet in diesen Faellen gar nicht erst) - sie
// sind hier bewusst nicht als Werte enthalten, siehe docs/API.md Abschnitt 2.4.
enum class ConnectionState {
    VERBUNDEN,
    NICHT_VERBUNDEN,
}
