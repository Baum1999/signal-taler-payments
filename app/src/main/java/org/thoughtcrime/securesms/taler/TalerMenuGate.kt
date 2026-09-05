package org.thoughtcrime.securesms.taler

import org.thoughtcrime.securesms.database.model.MessageRecord

/**
 * Minimaler Taler-Erkennungs-Check fuer Upstream-Menue-Logik (MenuState.java),
 * die sonst nichts von Taler weiss - haelt den Eingriff dort auf eine einzige
 * Bedingung beschraenkt (siehe REVIEW.md H4-Muster).
 */
object TalerMenuGate {
  fun messageHasTalerUris(messageRecord: MessageRecord): Boolean =
    urisFromMessageBody(messageRecord.body).isNotEmpty()
}
