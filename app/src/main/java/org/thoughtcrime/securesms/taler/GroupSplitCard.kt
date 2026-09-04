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

/**
 * Reine Entscheidungslogik fuer die Gruppen-Split-Sammelkarte: eine Karte pro
 * Nachricht statt eine pro URI. Bestimmt, welche Rolle der Betrachter hat
 * (Ersteller/Empfaenger/kein Teilnehmer), welche der N URIs als naechstes zum
 * Annehmen angeboten wird, und wie viele Anteile bereits angenommen wurden.
 *
 * "Mein Anteil" ist dabei nur ein Startpunkt fuer die Suche nach einem noch
 * offenen Anteil, kein festes Slot-Assignment - ein Mitglied kann bewusst
 * mehrere Anteile beanspruchen, z.B. um jemanden ohne Taler-Wallet bar
 * auszuzahlen.
 */
sealed class GroupCardRole {
  data object Creator : GroupCardRole()
  data class Recipient(val myIndex: Int) : GroupCardRole()
  data object NotAParticipant : GroupCardRole()
}

fun resolveGroupCardRole(
  recipientAcis: List<String>?,
  senderAci: String,
  myAci: String
): GroupCardRole {
  return if (myAci == senderAci) {
    GroupCardRole.Creator
  } else if (recipientAcis != null && recipientAcis.contains(myAci)) {
    GroupCardRole.Recipient(myIndex = recipientAcis.indexOf(myAci))
  } else {
    GroupCardRole.NotAParticipant
  }
}

/**
 * Sucht ab [startIndex] (mit Umlauf ans Listenende) den ersten noch offenen
 * Anteil - offen bedeutet Status null oder [TalerPaymentStatus.OFFEN]. Liefert
 * null, wenn kein Anteil mehr offen ist oder die Liste leer ist.
 */
fun resolveTargetUri(
  uris: List<String>,
  statuses: List<TalerPaymentStatus?>,
  startIndex: Int
): String? {
  if (uris.isEmpty()) return null
  for (offset in uris.indices) {
    val index = (startIndex + offset) % uris.size
    val status = statuses[index]
    if (status == null || status == TalerPaymentStatus.OFFEN) {
      return uris[index]
    }
  }
  return null
}

fun countAccepted(statuses: List<TalerPaymentStatus?>): Int =
  statuses.count { it == TalerPaymentStatus.ANGENOMMEN }

/**
 * Fuer den Weiterleiten-Wahl-Dialog (TalerForwardGate): true, wenn mindestens
 * einer der Anteile noch OFFEN ist - dann ist "als Text" weiterleiten immer
 * noch ein Inhaberpapier-Risiko (wer den Link zuerst oeffnet, kann DIESEN
 * Anteil einloesen), auch wenn andere Anteile derselben Sammelnachricht
 * bereits entschieden sind. Bei einer einzelnen Zahlung (Liste der Laenge 1)
 * verhaelt sich dies wie der bisherige Einzel-URI-Check.
 */
fun anyOpen(statuses: List<TalerPaymentStatus?>): Boolean =
  statuses.any { it == TalerPaymentStatus.OFFEN }
