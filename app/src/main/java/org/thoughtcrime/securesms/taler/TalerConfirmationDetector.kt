package org.thoughtcrime.securesms.taler

import android.content.Context
import net.taler.wallet.link.TalerUriKind
import org.thoughtcrime.securesms.R

data class TalerConfirmationMatch(val uri: String, val kind: TalerUriKind)

/**
 * Erkennt die von TalerUriRefreshJob.maybeSendAcceptConfirmation verschickte
 * Bestaetigungsnachricht ("Zahlung fuer [Kind] akzeptiert (uri)") in
 * eingehendem Klartext - exakter Volltext-Match gegen
 * TalerFork_accept_confirmation_message mit jedem bekannten
 * TalerFork_kind_*-Label und der im Text eingebetteten Taler-URI. Die URI
 * ist die eindeutige Referenz, ueber die der Aufrufer (TalerConfirmationTracker)
 * die Nachricht spaeter zweifelsfrei der eigenen Zahlung zuordnet - ohne
 * Ratewerk ueber "neueste offene Zahlung im Thread".
 *
 * Ein Treffer ist kein Vertrauensbeweis (der Text koennte auch manuell
 * getippt sein) - der Aufrufer verwirft die Sondereinstufung deshalb wieder,
 * wenn sich zur extrahierten URI keine passende eigene Zahlung in der DB
 * findet.
 */
object TalerConfirmationDetector {
  private val MATCHABLE_KINDS = listOf(
    R.string.TalerFork_kind_pay_push to TalerUriKind.PAY_PUSH,
    R.string.TalerFork_kind_pay_pull to TalerUriKind.PAY_PULL,
    R.string.TalerFork_kind_pay to TalerUriKind.PAY,
    R.string.TalerFork_kind_withdraw to TalerUriKind.WITHDRAW,
    R.string.TalerFork_kind_refund to TalerUriKind.REFUND,
  )

  fun match(context: Context, text: String): TalerConfirmationMatch? {
    val uris = TalerUriDetector.findUris(text)
    if (uris.size != 1) return null
    val uri = uris[0]

    for ((labelRes, kind) in MATCHABLE_KINDS) {
      val expected = context.getString(
        R.string.TalerFork_accept_confirmation_message,
        context.getString(labelRes),
        uri,
      )
      if (text == expected) {
        return TalerConfirmationMatch(uri, kind)
      }
    }
    return null
  }
}
