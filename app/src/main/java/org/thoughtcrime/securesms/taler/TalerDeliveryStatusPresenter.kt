package org.thoughtcrime.securesms.taler

import org.thoughtcrime.securesms.database.SignalDatabase

/**
 * Erkennt, ob eine ausgehende Nachricht eine Taler-Zahlung ist und ob sie
 * (bei mehreren URIs, z.B. Gruppen-Split) bereits vollstaendig angenommen
 * wurde - fuer die Kreis-Statusicons in ConversationItemFooter/
 * DeliveryStatusView (setTalerSent/Delivered/Read/Paid). Nutzt dieselbe
 * URI-Erkennung wie TalerPaymentCardPresenter/TalerConfirmationIconPresenter.
 */
object TalerDeliveryStatusPresenter {

  /**
   * @return null, wenn [body] keine Taler-Zahlungs-URI enthaelt (Aufrufer
   * zeigt dann die normalen Haekchen). Sonst [TalerPaymentStatus.ANGENOMMEN],
   * wenn ALLE enthaltenen URIs angenommen sind, sonst [TalerPaymentStatus.OFFEN]
   * als Platzhalter fuer "Taler-Zahlung, aber noch nicht vollstaendig
   * angenommen" (der Aufrufer unterscheidet dort ohnehin selbst weiter nach
   * pending/delivered/read).
   */
  @JvmStatic
  fun statusFor(body: String): TalerPaymentStatus? {
    val uris = urisFromMessageBody(body)
    if (uris.isEmpty()) return null

    val allAccepted = uris.all { SignalDatabase.talerPayments.getByUri(it)?.status == TalerPaymentStatus.ANGENOMMEN }
    return if (allAccepted) TalerPaymentStatus.ANGENOMMEN else TalerPaymentStatus.OFFEN
  }
}
