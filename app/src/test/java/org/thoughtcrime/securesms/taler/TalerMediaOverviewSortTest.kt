/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.database.TalerPaymentRecord

class TalerMediaOverviewSortTest {

  private fun record(uri: String, createdAt: Long) = TalerPaymentRecord(
    uri = uri,
    threadId = 1L,
    uriKind = "PAY_PUSH",
    status = TalerPaymentStatus.OFFEN,
    amount = "1.00",
    currency = "KUDOS",
    exchangeBaseUrl = null,
    summary = null,
    createdAt = createdAt,
    lastCheckedAt = null,
    consecutiveFailures = 0,
    isOwnPayment = true,
  )

  @Test
  fun newestFirst_sortsDescendingByCreatedAt() {
    val oldest = record("taler://pay-push/old", 1000L)
    val middle = record("taler://pay-push/mid", 2000L)
    val newest = record("taler://pay-push/new", 3000L)

    val sorted = TalerMediaOverviewSort.newestFirst(listOf(oldest, newest, middle))

    assertEquals(listOf(newest, middle, oldest), sorted)
  }

  @Test
  fun newestFirst_stableForEqualTimestamps() {
    val first = record("taler://pay-push/a", 1000L)
    val second = record("taler://pay-push/b", 1000L)

    val sorted = TalerMediaOverviewSort.newestFirst(listOf(first, second))

    assertEquals(listOf(first, second), sorted)
  }

  @Test
  fun newestFirst_emptyListStaysEmpty() {
    assertEquals(emptyList<TalerPaymentRecord>(), TalerMediaOverviewSort.newestFirst(emptyList()))
  }
}
