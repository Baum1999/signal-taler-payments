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

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// Diese Datei muss identisch auch im Signal-Repo vorliegen, siehe ITalerLink.aidl.
// Bewusst kein Betrag-/Zweck-Feld: Taler leitet Betrag und Zweck aus der eigenen
// Transaktion zu originalUri ab (Taler ist Source of Truth), siehe docs/API.md 2.4.
@Parcelize
data class PrepareRefundRequest(
    val originalUri: String,    // die urspruenglich angenommene pay-push-URI
    val correlationId: String,  // von Signal erzeugt, docs/API.md 2.7
    val returnUri: String,      // Signal-Deep-Link fuer den Ruecksprung, docs/API.md 2.7
) : Parcelable
