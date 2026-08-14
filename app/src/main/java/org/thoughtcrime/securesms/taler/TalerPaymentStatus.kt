package org.thoughtcrime.securesms.taler

import net.taler.wallet.link.TalerOperationStatus

/**
 * Zustand eines Taler-Vorgangs aus Signals Sicht - Obermenge von
 * [TalerOperationStatus] (das kommt von Taler) um die rein lokalen
 * Zustaende, die Taler nie liefert. Siehe docs/API.md, Abschnitt "Zustaende".
 *
 * NICHT_INSTALLIERT/NICHT_VERTRAUENSWUERDIG/TALER_NICHT_VERBUNDEN waren bis
 * REVIEW.md P1 zu einem einzigen TALER_NICHT_VERBUNDEN zusammengefasst - drei
 * fuer den Nutzer sehr unterschiedliche Faelle ("App fehlt" vs. "App hat die
 * falsche Signatur, Verbindung wird verweigert" vs. "noch nicht verbunden")
 * sahen dadurch auf der Karte gleich aus. Der mittlere Fall ist ein
 * Sicherheitshinweis, kein Installationshinweis - deshalb eigener Zustand
 * mit eigenem Text statt eines gemeinsamen Fallbacks.
 */
enum class TalerPaymentStatus {
  OFFEN,
  ANGENOMMEN,
  LOKAL_ABGELEHNT,
  ABGELAUFEN,
  UNBEKANNT_OFFLINE,
  UNGUELTIG,
  TALER_NICHT_VERBUNDEN,
  NICHT_INSTALLIERT,
  NICHT_VERTRAUENSWUERDIG;

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
