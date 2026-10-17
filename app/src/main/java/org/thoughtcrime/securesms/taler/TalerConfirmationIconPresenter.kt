package org.thoughtcrime.securesms.taler

import android.graphics.PorterDuff
import android.widget.ImageView
import androidx.core.content.ContextCompat
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.util.visible

/**
 * Steuert das kleine Icon, das TalerConfirmationDetector-erkannte
 * Bestaetigungsnachrichten ("Zahlung fuer [Kind] akzeptiert") anstelle des
 * Rohtexts zeigen - ausgelagert aus V2ConversationItemTextOnlyViewHolder nach
 * demselben Muster wie TalerPaymentCardPresenter (REVIEW.md H4).
 *
 * Grau, solange die zugeordnete eigene Zahlung (TalerConfirmationMessageTable)
 * noch nicht als ANGENOMMEN bestaetigt ist, blau danach - analog zum
 * Lesebestaetigungs-Haken (DeliveryStatusView.setRead()).
 */
object TalerConfirmationIconPresenter {

  /**
   * @return true, wenn [messageId] eine erkannte Bestaetigungsnachricht ist
   * (der Aufrufer muss dann den Rohtext der Nachricht ausblenden).
   */
  fun present(icon: ImageView?, messageId: Long, grayColor: Int): Boolean {
    if (icon == null) return false

    val confirmation = SignalDatabase.talerConfirmationMessages.getByMessageId(messageId)
    if (confirmation == null) {
      icon.visible = false
      return false
    }

    val status = SignalDatabase.talerPayments.getByUri(confirmation.uri)?.status
    val color = if (status == TalerPaymentStatus.ANGENOMMEN) {
      ContextCompat.getColor(icon.context, org.signal.core.ui.R.color.signal_colorPrimary)
    } else {
      grayColor
    }
    icon.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    icon.visible = true
    return true
  }
}
