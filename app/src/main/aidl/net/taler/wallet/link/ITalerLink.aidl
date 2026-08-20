// Lokale Schnittstelle zwischen "GNU Taler fuer Signal" und "Signal fuer GNU".
// Siehe docs/API.md im Signal-Taler-Arbeitsverzeichnis fuer den vollstaendigen
// Entwurf. Diese Datei muss byte-identisch auch im Signal-Repo vorliegen
// (AIDL-Marshalling verlangt identische Struktur auf beiden Seiten).
//
// Schritt 5 (aktueller Stand): getConnectionState (Schritt 3), die drei
// lesenden Methoden sowie prepareSend. prepareRefund kommt erst in
// Meilenstein 6, wenn es tatsaechlich implementiert und auf dem Geraet
// getestet ist.
package net.taler.wallet.link;

import net.taler.wallet.link.ConnectionStateResult;
import net.taler.wallet.link.UriValidationResult;
import net.taler.wallet.link.PaymentPreviewResult;
import net.taler.wallet.link.OperationStatusResult;
import net.taler.wallet.link.PrepareSendRequest;
import net.taler.wallet.link.PrepareSendResult;

interface ITalerLink {
    ConnectionStateResult getConnectionState();
    UriValidationResult validateUri(String uri);
    PaymentPreviewResult previewForUri(String uri);
    OperationStatusResult statusForUri(String uri);
    PrepareSendResult prepareSend(in PrepareSendRequest request);
}
