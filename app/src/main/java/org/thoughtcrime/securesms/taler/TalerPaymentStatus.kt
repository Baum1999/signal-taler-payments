package org.thoughtcrime.securesms.taler

import net.taler.wallet.link.TalerOperationStatus

/**
 * Zustand eines Taler-Vorgangs aus Signals Sicht - Obermenge von
 * [TalerOperationStatus] (das kommt von Taler) um die beiden rein lokalen
 * Zustaende LOKAL_ABGELEHNT und TALER_NICHT_VERBUNDEN, die Taler nie liefert.
 * Siehe docs/API.md, Abschnitt "Zustaende".
 */
enum class TalerPaymentStatus {
  OFFEN,
  ANGENOMMEN,
  LOKAL_ABGELEHNT,
  ABGELAUFEN,
  UNBEKANNT_OFFLINE,
  UNGUELTIG,
  TALER_NICHT_VERBUNDEN;

  companion object {
    fun fromTalerStatus(status: TalerOperationStatus): TalerPaymentStatus = when (status) {
      TalerOperationStatus.OFFEN -> OFFEN
      TalerOperationStatus.ANGENOMMEN -> ANGENOMMEN
      TalerOperationStatus.ABGELAUFEN -> ABGELAUFEN
      TalerOperationStatus.UNGUELTIG -> UNGUELTIG
      TalerOperationStatus.UNBEKANNT_OFFLINE -> UNBEKANNT_OFFLINE
    }
  }
}
