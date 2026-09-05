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

import org.thoughtcrime.securesms.database.TalerPaymentRecord
import java.math.BigDecimal

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
 * Eigene, noch offene Anteile der Sammelkarte, fuer die "Abbrechen"/
 * "Aktualisieren" Sinn ergeben (siehe TalerCardActionGate.showCancel, dieselbe
 * Bedingung wie beim Einzel-URI-Pfad, nur pro Anteil angewendet). Liefert nur
 * fuer [GroupCardRole.Creator] etwas - Empfaenger und Nicht-Teilnehmer haben
 * nie eigene ausgehende Anteile in dieser Karte.
 */
fun resolveCancelableUris(
  uris: List<String>,
  records: List<TalerPaymentRecord?>,
  role: GroupCardRole
): List<String> {
  if (role !is GroupCardRole.Creator) return emptyList()
  return uris.zip(records)
    .filter { (_, record) -> TalerCardActionGate.showCancel(record) }
    .map { it.first }
}

/**
 * Anteile, die DIESES Geraet ueber [claimedUris] (GroupClaimTracker) selbst
 * beansprucht hat und die mittlerweile angenommen wurden - fuer "Rueckerstatten"
 * (TalerCardActionGate.showRefund, dieselbe Bedingung wie beim Einzel-URI-Pfad).
 */
fun resolveRefundableUris(
  uris: List<String>,
  records: List<TalerPaymentRecord?>,
  claimedUris: Set<String>
): List<String> {
  return uris.zip(records)
    .filter { (uri, record) -> uri in claimedUris && TalerCardActionGate.showRefund(record) }
    .map { it.first }
}

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

/**
 * Rein rechnerische Plausibilitaetspruefung des von Taler mitgelieferten
 * [totalAmount] gegen den tatsaechlich bekannten Pro-Anteil-Betrag
 * [perShareAmount] (alle Anteile derselben Sammelnachricht tragen denselben
 * Betrag, siehe TalerPaymentCardPresenter.bindGroupCard) - schuetzt davor,
 * eine falsche/manipulierte Summe anzuzeigen (Regel: nie eine ungeprueften
 * Zahl anzeigen).
 *
 * [uriCount] (= [totalAmount].uri.size) ist immer die Empfaengerzahl OHNE den
 * Sender (dieser bekommt nie einen eigenen Link, siehe
 * OutgoingPushComposable.kt/AmountSplit.kt in taler-android). Der Divisor,
 * durch den der Gesamtbetrag tatsaechlich geteilt wurde, ist deshalb
 * [uriCount] + 1, wenn der Sender seinen eigenen Anteil mitgezaehlt hat
 * ([includeSelf] true), sonst [uriCount] (splitDivisor in AmountSplit.kt).
 *
 * Toleranz 1e-6: [perShareAmount] entsteht sender-seitig durch Abrunden auf
 * 8 Nachkommastellen (RoundingMode.FLOOR), Divisor*Anteil liegt also immer
 * leicht UNTER dem echten Gesamtbetrag, nie darueber - der Fehler bleibt bei
 * maximal erlaubten 10 Mitgliedern (MAX_SPLIT_MEMBERS) weit unter 1e-6.
 *
 * Liefert den verifizierten Gesamtbetrag zur Anzeige, oder null, wenn eine
 * der Angaben fehlt, nicht parsbar ist oder nicht zusammenpasst - der
 * Aufrufer zeigt in diesem Fall keine Summe an (fail-safe statt Absturz: die
 * Nachricht kann von jedem Gruppenmitglied stammen, angreiferkontrollierte
 * Daten duerfen die Kartenansicht nicht crashen lassen).
 */
fun computeVerifiedTotal(
  totalAmount: String?,
  includeSelf: Boolean?,
  uriCount: Int,
  perShareAmount: String?
): BigDecimal? {
  if (totalAmount == null || includeSelf == null || perShareAmount == null || uriCount <= 0) {
    return null
  }
  val total = totalAmount.toBigDecimalOrNull() ?: return null
  val perShare = perShareAmount.toBigDecimalOrNull() ?: return null
  val divisor = if (includeSelf) uriCount + 1 else uriCount
  val expected = perShare.multiply(BigDecimal(divisor))
  val tolerance = BigDecimal("0.000001")
  return if ((expected - total).abs() <= tolerance) total else null
}
