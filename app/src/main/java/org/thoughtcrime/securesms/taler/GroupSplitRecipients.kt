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

import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Sortierte Liste der Empfaenger-ACIs eines Gruppen-Split (alle aktuellen
 * Gruppenmitglieder ausser [senderAci]), lokal aus der bereits bekannten
 * Gruppenmitgliedschaft hergeleitet - unabhaengig davon, ob/wie die
 * Nachricht selbst diese Liste transportiert (frueher JSON-Feld
 * recipientAcis in TalerPaymentData; DataMessage.talerPayment/
 * TalerPaymentPayload traegt es gar nicht erst). Jeder Gruppenclient kennt
 * die Mitgliedschaft bereits lokal (Signal synchronisiert Gruppenzustand
 * unabhaengig von dieser Nachricht) - die Liste muss deshalb in keinem
 * Nachrichtenformat mitgeschickt werden, auch nicht in einem zukuenftigen.
 *
 * Dieselbe Berechnung wie zuvor sender-seitig in TalerReturnActivity, nur
 * mit [senderAci] als Parameter statt der eigenen Account-ACI - Sender wie
 * Empfaenger kommen damit unabhaengig voneinander auf dieselbe sortierte
 * Liste (fuer resolveGroupCardRole/GroupSplitCard.kt).
 */
fun groupSplitRecipientAcis(groupRecipient: Recipient, senderAci: String): List<String> =
  groupRecipient.participantIds
    .map { Recipient.resolved(it) }
    .mapNotNull { if (it.aci.isPresent) it.aci.get().toString() else null }
    .filter { it != senderAci }
    .sorted()
