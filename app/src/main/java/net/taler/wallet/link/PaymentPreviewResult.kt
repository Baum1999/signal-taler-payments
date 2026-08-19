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
//
// exchangeBaseUrl und expirationTimestamp fuer PAY_PUSH/PAY_PULL kommen nicht
// aus preparePeerPushCredit/preparePeerPullDebit direkt (die liefern das nicht
// vollstaendig), sondern aus einem zweiten Aufruf getTransactionById(transactionId)
// - siehe TalerLinkService.detailsFor(). Fuer PAY/WITHDRAW/REFUND (noch nicht an
// wallet-core angebunden) bleiben alle Felder null.
//
// isOwnPayment gibt an, ob die URI zu einer eigenen ausgehenden Zahlung des Nutzers
// gehoert (true) oder eine eingehende Zahlungsanfrage von jemand anderem ist (false).
@Parcelize
data class PaymentPreviewResult(
    val uriKind: TalerUriKind,
    val status: TalerOperationStatus,
    val amount: String?,
    val currency: String?,
    val exchangeBaseUrl: String?,
    val summary: String?,
    val expirationTimestamp: Long?,
    val isOwnPayment: Boolean = false,
) : Parcelable
