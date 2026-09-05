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
// Traegt bewusst keinen Betrag/Waehrung/Zweck mehr (anders als in frueheren
// Fassungen) - Taler fragt das jetzt selbst in ComposeSendScreen ab
// (Wiederverwendung von OutgoingPushComposable), analog zur Begruendung bei
// PrepareRefundRequest: Taler ist Quelle der Wahrheit fuer Betraege, docs/API.md 2.4.
@Parcelize
data class PrepareSendRequest(
    val recipientHint: String?,           // z.B. Anzeigename des Chatkontakts, rein informativ fuer Talers UI
    val isGroup: Boolean,
    val memberCount: Int?,                // nur gesetzt wenn isGroup; Gesamtgroesse INKLUSIVE Sender
                                           // (Recipient.participantIds.size) - aktuell nur Anzeigetext,
                                           // Konvention fuer eine spaetere Aufteilungs-Rechner-UI, docs/API.md 2.4
    val disappearingMessagesSeconds: Int, // 0 = aus, sonst Sekunden (Recipient.expiresInSeconds)
    val correlationId: String,            // von Signal erzeugt, docs/API.md 2.7
    val returnUri: String,                // Signal-Deep-Link fuer den Ruecksprung, docs/API.md 2.7
    // Anfangszustand des Richtungs-Toggles in ComposeSendScreen (Nutzer-
    // Vorgabe 2026-09-05: nur EIN Button in Signal, die eigentliche Wahl
    // zwischen Senden/Anfordern trifft der Nutzer dort per Toggle, nicht
    // hier). Signal setzt hier immer PAY_PUSH (siehe TalerSendActions.kt).
    // Nur PAY_PUSH/PAY_PULL sind gueltig - die drei anderen TalerUriKind-
    // Werte klassifizieren nur EMPFANGENE URIs und werden hier nie gesetzt.
    val direction: TalerUriKind,
) : Parcelable
