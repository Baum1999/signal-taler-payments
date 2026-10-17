/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

/**
 * What the long press menu can ask for.
 */
enum class ReactionAction {
  REPLY,
  EDIT,
  FORWARD,
  RESEND,
  DOWNLOAD,
  COPY,
  MULTISELECT,
  PAYMENT_DETAILS,
  VIEW_INFO,
  DELETE,
  END_POLL,
  PIN_MESSAGE,
  UNPIN_MESSAGE,
  STAR_MESSAGE,
  UNSTAR_MESSAGE,
  VIEW_STICKER_PACK,

  // Taler payment messages: actions that live in the long press menu instead of
  // buttons on the payment card, plus forward/copy variants that replace the
  // standard forward/copy entries for Taler messages.
  TALER_REJECT,
  TALER_CANCEL,
  TALER_REFRESH,
  TALER_REFUND,
  TALER_FORWARD_IMAGE,
  TALER_FORWARD_TEXT,
  TALER_COPY_IMAGE,
  TALER_COPY_TEXT
}
