package org.thoughtcrime.securesms.messagedetails

/**
 * [RecipientHeader] traegt selbst keinen Bezug zur konkreten Nachricht (ist
 * ein geteiltes Enum) - dieser Wrapper haengt an, ob die zugehoerige
 * Nachricht eine Taler-Zahlung ist, damit RecipientHeaderViewHolder dieselben
 * Kreis-Icons wie ConversationItemFooter zeigen kann statt der Haekchen.
 */
internal data class RecipientHeaderState(
  val header: RecipientHeader,
  val isTalerPayment: Boolean,
)
