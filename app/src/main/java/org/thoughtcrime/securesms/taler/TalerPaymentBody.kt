/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import android.content.Context
import net.taler.wallet.link.TalerHttpsLink
import org.thoughtcrime.securesms.R

/**
 * Klartext-Body fuer Zahlungsnachrichten aus dem talerPaymentData-Pfad.
 *
 * Der Satz vor den URIs gehoert Signal (TalerFork_payment_legacy_text) und
 * nicht Taler: Taler schickt seit 2026-10-11 kein legacyText mehr, und ein
 * von aelteren Taler-Versionen mitgeschickter Text wird ignoriert. Text
 * zuerst, dann die Links durch Leerzeilen getrennt - "open the link below"
 * stimmt nur so, und TalerUriDetector findet jeden Link einzeln.
 *
 * taler://-URIs gehen als https-Link nach DD 103 in den Klartext
 * ([TalerHttpsLink.wrap]): den macht jeder Client anklickbar, ein rohes
 * taler:// meist nicht. Das strukturierte Feld (TalerPaymentPayload) behaelt
 * die taler://-URIs.
 */
object TalerPaymentBody {

  fun send(context: Context, uris: List<String>): String =
    context.getString(R.string.TalerFork_payment_legacy_text) + "\n\n" + links(uris)

  fun refundDraft(context: Context, uris: List<String>): String =
    context.getString(R.string.TalerFork_refund_message, context.getString(R.string.TalerFork_payment_legacy_text)) +
      "\n\n" + links(uris)

  // Nur taler:// laesst sich verpacken; payto:// u. ae. bleiben roh.
  private fun links(uris: List<String>): String =
    uris.joinToString("\n\n") { if (it.startsWith("taler://", ignoreCase = true)) TalerHttpsLink.wrap(it) else it }
}
