# Signal Android with GNU Taler payments

This is a fork of [Signal Android](https://github.com/signalapp/Signal-Android) that adds peer-to-peer payments with [GNU Taler](https://taler.net). It is a development fork, not an official Signal release.

The `main` branch contains only the Taler feature on top of upstream Signal and is regularly rebased onto `signalapp/Signal-Android` `main`.

## What it adds

- **Taler payments in chats**: `taler://` links and https payment links are detected in messages and rendered as a payment card instead of plain text, including their status (requested, paid, expired, refunded).
- **Sending**: a Taler entry in the attachment menu, accepting and declining incoming payments, a forward dialog for payment messages.
- **Group split**: one card for a message carrying several payment URIs, with the total and per-participant progress.
- **Peer payments**: payments are handed to the GNU Taler wallet as `taler://` deep links. The preview (amount, subject) is decrypted locally from the peer contract, without asking the wallet.
- **Media overview**: a Taler tab in the conversation media overview.

The wallet side lives in the matching fork of [taler-android](https://git.taler.net/taler-android.git).

## License

Copyright 2013 Signal Messenger, LLC

Licensed under the GNU AGPLv3: https://www.gnu.org/licenses/agpl-3.0.html
