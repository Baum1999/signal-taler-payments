package org.thoughtcrime.securesms.taler

import net.taler.wallet.link.TalerOperationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TalerPaymentStatusTest {

  /**
   * Bewusst KEIN `else`-Zweig (REVIEW.md P3): ein neuer Wert in
   * TalerOperationStatus (Taler-Seite der AIDL-Schnittstelle) laesst diese
   * Testdatei nicht mehr kompilieren, statt dass TalerPaymentStatus.fromTalerStatus()
   * ihn still auf einen falschen/unklaren Wert abbildet.
   */
  private fun expected(status: TalerOperationStatus): TalerPaymentStatus = when (status) {
    TalerOperationStatus.OFFEN -> TalerPaymentStatus.OFFEN
    TalerOperationStatus.ANGENOMMEN -> TalerPaymentStatus.ANGENOMMEN
    TalerOperationStatus.ABGELAUFEN -> TalerPaymentStatus.ABGELAUFEN
    TalerOperationStatus.UNGUELTIG -> TalerPaymentStatus.UNGUELTIG
    TalerOperationStatus.UNBEKANNT_OFFLINE -> TalerPaymentStatus.UNBEKANNT_OFFLINE
  }

  @Test
  fun mapsEveryTalerOperationStatusValueAsExpected() {
    for (status in TalerOperationStatus.entries) {
      assertEquals(
        "unerwartetes Mapping fuer $status",
        expected(status),
        TalerPaymentStatus.fromTalerStatus(status)
      )
    }
  }

  @Test
  fun localOnlyStatesAreNotProducedByFromTalerStatus() {
    // LOKAL_ABGELEHNT und TALER_NICHT_VERBUNDEN/NICHT_INSTALLIERT/
    // NICHT_VERTRAUENSWUERDIG liefert Taler nie (docs/API.md Abschnitt
    // "Zustaende") - fromTalerStatus() kann sie schon vom Eingabetyp her
    // (TalerOperationStatus) nicht zurueckgeben. Dieser Test haelt das
    // Contract explizit fest, falls TalerPaymentStatus je vereinfacht wird.
    val talerOnlyReachable = TalerOperationStatus.entries.map { expected(it) }.toSet()
    val localOnly = setOf(
      TalerPaymentStatus.LOKAL_ABGELEHNT,
      TalerPaymentStatus.TALER_NICHT_VERBUNDEN,
      TalerPaymentStatus.NICHT_INSTALLIERT,
      TalerPaymentStatus.NICHT_VERTRAUENSWUERDIG,
    )
    assertEquals(emptySet<TalerPaymentStatus>(), talerOnlyReachable.intersect(localOnly))
  }
}
