package org.thoughtcrime.securesms.taler

import net.taler.wallet.link.TalerUriKind
import org.thoughtcrime.securesms.database.TalerPaymentRecord

/**
 * Reine Sichtbarkeits-Entscheidungen fuer die Einzel-URI-Zahlungskarte, aus
 * TalerPaymentCardPresenter.bind() extrahiert (siehe Plan "Aktionen ins
 * Long-Press-Menue verschieben") - dieselbe Wahrheitstabelle wird jetzt sowohl
 * von der Karte (nur noch Annehmen) als auch vom neuen Long-Press-Menue
 * (Ablehnen/Abbrechen/Aktualisieren/Rueckerstatten) verwendet, statt die
 * Bedingungen an zwei Stellen zu duplizieren.
 */
object TalerCardActionGate {

  /**
   * Annehmen/Ablehnen nur bei einer Karte mit konkretem DB-Eintrag im Zustand
   * OFFEN, beschraenkt auf PAY_PUSH (der Taler-seitige Ruecksprung-Mechanismus
   * ist bisher nur dafuer Ende-zu-Ende verdrahtet), und nur fuer die
   * Nicht-Absenderseite.
   */
  fun showAccept(record: TalerPaymentRecord?): Boolean =
    record?.status == TalerPaymentStatus.OFFEN &&
      record?.uriKind == TalerUriKind.PAY_PUSH.name &&
      record?.isOwnPayment == false

  fun showReject(record: TalerPaymentRecord?): Boolean = showAccept(record)

  /** Aktualisieren fuer die eigene noch offene ausgehende Zahlung. */
  fun showRefresh(record: TalerPaymentRecord?): Boolean =
    record?.status == TalerPaymentStatus.OFFEN &&
      record?.uriKind == TalerUriKind.PAY_PUSH.name &&
      record?.isOwnPayment == true

  /**
   * Abbrechen nur, wenn die Wallet dieses Geraets die Zahlung erzeugt hat.
   * Eine von einem anderen eigenen Geraet gesendete Zahlung (Sync-Transkript)
   * liegt in der Wallet jenes Geraets; hier gibt es nichts abzubrechen, der
   * Menuepunkt entfaellt deshalb ganz.
   */
  fun showCancel(record: TalerPaymentRecord?): Boolean =
    showRefresh(record) && record?.sentFromOtherDevice == false

  /** Rueckerstatten fuer eine von der Gegenseite bereits angenommene Zahlung. */
  fun showRefund(record: TalerPaymentRecord?): Boolean =
    record?.status == TalerPaymentStatus.ANGENOMMEN && record?.isOwnPayment == false
}
