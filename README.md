# Signal für GNU

Dies ist ein Fork von [Signal Android](https://github.com/signalapp/Signal-Android), nur für eigene Entwicklungszwecke gedacht, nicht offiziell und nicht für die Allgemeinheit.

## Was wurde geändert

- **Taler-Zahlungen in Chats**: `taler://`-Links werden in Chats erkannt und als Zahlungskarte (statt reinem Text) gerendert, inkl. Status (angefragt/bezahlt/abgelaufen/erstattet) per Polling.
- **Senden**: eigener Taler-Button im Anhang-Menü, Annehmen/Ablehnen eingehender Zahlungen, Weiterleiten-Dialog (TalerForwardGate).
- **Gruppen-Split**: Sammelkarte für mehrere Zahlungs-URIs in einer Nachricht, mit Gesamtbetrag und Anteils-Tracking pro Teilnehmer.
- **Peer-Zahlungen**: Zahlungen laufen über `taler://`-Deep-Links, die Vorschau (Betrag/Zweck) wird lokal aus dem Vertrag entschlüsselt (`TalerContractCrypto`), ohne Rückfrage an die Taler-App.
- **Medienübersicht**: eigener Tab für Taler-Zahlungen in der Chat-Medienübersicht.

Details zu einzelnen Fixes: `git log` auf dem `gnu-fork`-Branch.

## Lizenz

Copyright 2013 Signal Messenger, LLC

Lizenziert unter der GNU AGPLv3: https://www.gnu.org/licenses/agpl-3.0.html
