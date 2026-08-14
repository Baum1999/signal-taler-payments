// Lokale Schnittstelle zwischen "GNU Taler fuer Signal" und "Signal fuer GNU".
// Siehe docs/API.md im Signal-Taler-Arbeitsverzeichnis fuer den vollstaendigen
// Entwurf. Diese Datei muss byte-identisch auch im Signal-Repo vorliegen
// (AIDL-Marshalling verlangt identische Struktur auf beiden Seiten).
//
// Schritt 4 (aktueller Stand): getConnectionState (Schritt 3) plus die drei
// lesenden Methoden. prepareSend/prepareRefund kommen erst in Schritt 6/7,
// wenn sie tatsaechlich implementiert und auf dem Geraet getestet sind.
package net.taler.wallet.link;

import net.taler.wallet.link.ConnectionStateResult;
import net.taler.wallet.link.UriValidationResult;
import net.taler.wallet.link.PaymentPreviewResult;
import net.taler.wallet.link.OperationStatusResult;

interface ITalerLink {
    ConnectionStateResult getConnectionState();
    UriValidationResult validateUri(String uri);
    PaymentPreviewResult previewForUri(String uri);
    OperationStatusResult statusForUri(String uri);
}
