/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediaoverview

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.signal.core.util.concurrent.SimpleTask
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.TalerPaymentRecord
import org.thoughtcrime.securesms.taler.TalerMediaOverviewSort
import org.thoughtcrime.securesms.taler.TalerPaymentCardPresenter

/**
 * Taler-Tab der Medien-Uebersicht (siehe
 * docs/superpowers/specs/2026-10-01-taler-media-overview-tab-design.md).
 * Keine Attachment-/MediaTable-Pipeline (anders als MediaOverviewPageFragment)
 * - laedt stattdessen direkt aus TalerPaymentTable und rendert jede Zahlung
 * ueber die bereits vorhandene TalerPaymentCardPresenter.renderStandalone().
 */
class TalerPaymentOverviewFragment : Fragment(R.layout.fragment_taler_payment_overview) {

  companion object {
    private const val THREAD_ID_ARG = "thread_id"

    fun newInstance(threadId: Long): Fragment {
      val fragment = TalerPaymentOverviewFragment()
      fragment.arguments = Bundle().apply { putLong(THREAD_ID_ARG, threadId) }
      return fragment
    }
  }

  private var threadId: Long = MediaTable.ALL_THREADS
  private lateinit var container: LinearLayout
  private lateinit var emptyView: TextView

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    threadId = requireArguments().getLong(THREAD_ID_ARG, MediaTable.ALL_THREADS)
    container = view.findViewById(R.id.taler_payment_overview_container)
    emptyView = view.findViewById(R.id.taler_payment_overview_empty)
  }

  override fun onResume() {
    super.onResume()
    loadAndRender()
  }

  private fun loadAndRender() {
    SimpleTask.run(
      {
        val records = if (threadId == MediaTable.ALL_THREADS) {
          SignalDatabase.talerPayments.getAll()
        } else {
          SignalDatabase.talerPayments.getForThread(threadId)
        }
        TalerMediaOverviewSort.newestFirst(records)
      },
      { sorted -> renderRecords(sorted) }
    )
  }

  private fun renderRecords(records: List<TalerPaymentRecord>) {
    if (!isAdded) return

    container.removeAllViews()

    if (records.isEmpty()) {
      container.visibility = View.GONE
      emptyView.visibility = View.VISIBLE
      return
    }

    container.visibility = View.VISIBLE
    emptyView.visibility = View.GONE

    for (record in records) {
      val cardView = TalerPaymentCardPresenter.renderStandalone(requireContext(), record)
      cardView.setOnClickListener { navigateToChat(record.threadId) }
      container.addView(cardView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
  }

  private fun navigateToChat(targetThreadId: Long) {
    val recipient = SignalDatabase.threads.getRecipientForThreadId(targetThreadId) ?: return
    val intent = ConversationIntents.createBuilderSync(requireContext(), recipient.id, targetThreadId).build()
    startActivity(intent)
  }
}
