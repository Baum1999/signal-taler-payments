package org.thoughtcrime.securesms.taler

import net.taler.wallet.link.TalerOperationStatus

/**
 * Zustand eines Taler-Vorgangs aus Signals Sicht - Obermenge von
 * [TalerOperationStatus] um die rein lokalen Zustaende. Siehe docs/API.md,
 * Abschnitt "Zustaende".
 *
 * NICHT_INSTALLIERT/NICHT_VERTRAUENSWUERDIG/TALER_NICHT_VERBUNDEN gab es hier
 * bis zum Wegfall der App-zu-App-Schnittstelle: solange der Zustand nur ueber
 * die Taler-App zu erfahren war, war deren Fehlen ein Zustand des Vorgangs.
 * Signal fragt den Exchange inzwischen selbst - ob die Wallet installiert ist,
 * sagt jetzt nichts mehr ueber die Zahlung aus, sondern nur darueber, ob der
 * Nutzer sie annehmen kann. Das gehoert an die Aktionen, nicht in diesen
 * Zustand.
 */
enum class TalerPaymentStatus {
  OFFEN,
  ANGENOMMEN,
  LOKAL_ABGELEHNT,
  LOKAL_ABGEBROCHEN,
  ABGELAUFEN,
  UNBEKANNT_OFFLINE,
  UNGUELTIG;

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
