/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2.items

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.LinearLayoutCompat

/**
 * Custom LinearLayoutCompat that will intercept EXACTLY measure-specs and
 * overwrite them with AT_MOST. This guarantees that wrap_content is respected
 * when the Layout is within a constraintlayout.
 */
class ShrinkWrapLinearLayout @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : LinearLayoutCompat(context, attrs) {

  // GNU-Fork (Signal-Taler-Integration): Die EXACTLY->AT_MOST-Umschreibung
  // unten laesst match_parent-Kinder (z.B. die Taler-Karte) ebenfalls auf
  // AT_MOST schrumpfen, obwohl sie die volle verfuegbare Breite nutzen
  // sollen - siehe TalerCardMeasureListener in
  // V2ConversationItemTextOnlyViewHolder, der diesen Wert nach dem ersten
  // Messpass ausliest, um die Kartenbreite explizit zu erzwingen.
  var availableWidth: Int = 0
    private set

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    availableWidth = MeasureSpec.getSize(widthMeasureSpec)
    val shrinkWrapWidthSpec = shrinkWrapWidthMeasureSpec(widthMeasureSpec)
    super.onMeasure(shrinkWrapWidthSpec, heightMeasureSpec)
  }

  private fun shrinkWrapWidthMeasureSpec(widthMeasureSpec: Int): Int {
    val mode = MeasureSpec.getMode(widthMeasureSpec)
    val size = MeasureSpec.getSize(widthMeasureSpec)

    return if (mode == MeasureSpec.EXACTLY) {
      MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
    } else {
      widthMeasureSpec
    }
  }
}
