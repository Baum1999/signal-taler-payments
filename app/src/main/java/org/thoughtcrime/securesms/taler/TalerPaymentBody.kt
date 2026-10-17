/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import android.content.Context
import org.thoughtcrime.securesms.R

/**
 * Klartext-Body fuer Zahlungsnachrichten aus dem talerPaymentData-Pfad.
 *
 * Der Satz vor den URIs gehoert Signal (TalerFork_payment_legacy_text) und
 * nicht Taler: Taler schickt seit 2026-10-11 kein legacyText mehr, und ein
 * von aelteren Taler-Versionen mitgeschickter Text wird ignoriert. Text
 * zuerst, dann die URIs durch Leerzeilen getrennt - "copy the URI below"
 * stimmt nur so, und TalerUriDetector findet jede URI einzeln.
 */
object TalerPaymentBody {

  fun send(context: Context, uris: List<String>): String =
    context.getString(R.string.TalerFork_payment_legacy_text) + "\n\n" + uris.joinToString("\n\n")

  fun refundDraft(context: Context, uris: List<String>): String =
    context.getString(R.string.TalerFork_refund_message, context.getString(R.string.TalerFork_payment_legacy_text)) +
      "\n\n" + uris.joinToString("\n\n")
}
