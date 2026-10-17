/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.database.TalerPaymentRecord
import java.math.BigDecimal

/**
 * [TalerThreadSummary.aggregate] ist die reine Rechenschicht hinter der
 * Zusammenfassung im Taler-Tab - bewusst ohne Context/Strings, damit genau
 * die Faelle testbar sind, die die Kopfzeile bestimmen (wer ist dran, was war
 * zuletzt, wie steht der Saldo). Gleiches Muster wie AmountSplit/AmountSplitTest
 * auf der Taler-Seite.
 */
class TalerThreadSummaryTest {

  private var nextUri = 0

  private fun record(
    status: TalerPaymentStatus,
    isOwnPayment: Boolean = false,
    amount: String? = null,
    currency: String? = null,
    exchangeBaseUrl: String? = null,
    summary: String? = null,
    createdAt: Long = 0,
    lastCheckedAt: Long? = null,
    uri: String = "taler://pay-push/exchange.test/key${nextUri++}",
  ) = TalerPaymentRecord(
    uri = uri,
    threadId = 1,
    uriKind = "PAY_PUSH",
    status = status,
    amount = amount,
    currency = currency,
    exchangeBaseUrl = exchangeBaseUrl,
    summary = summary,
    createdAt = createdAt,
    lastCheckedAt = lastCheckedAt,
    consecutiveFailures = 0,
    isOwnPayment = isOwnPayment,
  )

  @Test
  fun aggregate_emptyList_isAllZero() {
    val result = TalerThreadSummary.aggregate(emptyList())

    assertEquals(0, result.total)
    assertEquals(0, result.openForMe)
    assertEquals(0, result.openForOther)
    assertEquals(0, result.unverifiable)
    assertEquals(null, result.latest)
    assertEquals(emptyList<CurrencyBalance>(), result.balances)
    assertEquals(emptyList<String>(), result.exchanges)
    assertEquals(null, result.lastCheckedAt)
  }

  @Test
  fun aggregate_splitsOpenByWhoMustAct() {
    val result = TalerThreadSummary.aggregate(
      listOf(
        record(TalerPaymentStatus.OFFEN, isOwnPayment = false),
        record(TalerPaymentStatus.OFFEN, isOwnPayment = false),
        record(TalerPaymentStatus.OFFEN, isOwnPayment = true),
        record(TalerPaymentStatus.ANGENOMMEN, isOwnPayment = true),
      )
    )

    assertEquals(4, result.total)
    assertEquals(2, result.openForMe)
    assertEquals(1, result.openForOther)
  }

  @Test
  fun aggregate_balanceSplitsAcceptedByDirection() {
    val result = TalerThreadSummary.aggregate(
      listOf(
        record(TalerPaymentStatus.ANGENOMMEN, isOwnPayment = false, amount = "12.50", currency = "KUDOS"),
        record(TalerPaymentStatus.ANGENOMMEN, isOwnPayment = false, amount = "5.00", currency = "KUDOS"),
        record(TalerPaymentStatus.ANGENOMMEN, isOwnPayment = true, amount = "4.00", currency = "KUDOS"),
      )
    )

    assertEquals(
      listOf(CurrencyBalance("KUDOS", BigDecimal("17.50"), BigDecimal("4.00"))),
      result.balances
    )
  }

  @Test
  fun aggregate_balanceIgnoresEverythingButAccepted() {
    val result = TalerThreadSummary.aggregate(
      listOf(
        record(TalerPaymentStatus.ANGENOMMEN, amount = "5.00", currency = "KUDOS"),
        record(TalerPaymentStatus.OFFEN, amount = "99.00", currency = "KUDOS"),
        record(TalerPaymentStatus.LOKAL_ABGELEHNT, amount = "99.00", currency = "KUDOS"),
        record(TalerPaymentStatus.LOKAL_ABGEBROCHEN, amount = "99.00", currency = "KUDOS"),
        record(TalerPaymentStatus.ABGELAUFEN, amount = "99.00", currency = "KUDOS"),
      )
    )

    assertEquals(
      listOf(CurrencyBalance("KUDOS", BigDecimal("5.00"), BigDecimal.ZERO)),
      result.balances
    )
  }

  @Test
  fun aggregate_keepsCurrenciesApartAndSorted() {
    val result = TalerThreadSummary.aggregate(
      listOf(
        record(TalerPaymentStatus.ANGENOMMEN, isOwnPayment = true, amount = "3.00", currency = "EUR"),
        record(TalerPaymentStatus.ANGENOMMEN, isOwnPayment = false, amount = "12.50", currency = "KUDOS"),
      )
    )

    assertEquals(
      listOf(
        CurrencyBalance("EUR", BigDecimal.ZERO, BigDecimal("3.00")),
        CurrencyBalance("KUDOS", BigDecimal("12.50"), BigDecimal.ZERO),
      ),
      result.balances
    )
  }

  @Test
  fun aggregate_latestIsNewestByCreatedAt() {
    val newest = record(TalerPaymentStatus.OFFEN, createdAt = 300, summary = "Pizza")

    val result = TalerThreadSummary.aggregate(
      listOf(
        record(TalerPaymentStatus.ANGENOMMEN, createdAt = 100),
        newest,
        record(TalerPaymentStatus.ANGENOMMEN, createdAt = 200),
      )
    )

    assertEquals(newest, result.latest)
  }

  @Test
  fun aggregate_latestBreaksTiesByUriSoOrderOfRowsDoesNotMatter() {
    val a = record(TalerPaymentStatus.OFFEN, createdAt = 100, uri = "taler://pay-push/e/aaa")
    val b = record(TalerPaymentStatus.OFFEN, createdAt = 100, uri = "taler://pay-push/e/bbb")

    assertEquals(b, TalerThreadSummary.aggregate(listOf(a, b)).latest)
    assertEquals(b, TalerThreadSummary.aggregate(listOf(b, a)).latest)
  }

  @Test
  fun aggregate_exchangesAreDistinctAndSorted() {
    val result = TalerThreadSummary.aggregate(
      listOf(
        record(TalerPaymentStatus.ANGENOMMEN, exchangeBaseUrl = "exchange.demo.taler.net"),
        record(TalerPaymentStatus.OFFEN, exchangeBaseUrl = "exchange.demo.taler.net"),
        record(TalerPaymentStatus.OFFEN, exchangeBaseUrl = "bank.example.org"),
        record(TalerPaymentStatus.UNBEKANNT_OFFLINE, exchangeBaseUrl = null),
      )
    )

    assertEquals(listOf("bank.example.org", "exchange.demo.taler.net"), result.exchanges)
  }

  @Test
  fun aggregate_lastCheckedAtIsTheMostRecentCheck() {
    val result = TalerThreadSummary.aggregate(
      listOf(
        record(TalerPaymentStatus.OFFEN, lastCheckedAt = 500),
        record(TalerPaymentStatus.OFFEN, lastCheckedAt = null),
        record(TalerPaymentStatus.OFFEN, lastCheckedAt = 900),
      )
    )

    assertEquals(900L, result.lastCheckedAt)
  }

  // --- Kopfzeilen-Variante -------------------------------------------------
  // Rangfolge: Handlungsbedarf schlaegt Wissensluecke schlaegt "erledigt".
  // Getrennt von der Formatierung, damit die Rangfolge ohne Context pruefbar ist.

  private fun headlineFor(vararg records: TalerPaymentRecord) =
    TalerThreadSummary.headline(TalerThreadSummary.aggregate(records.toList()))

  @Test
  fun headline_noRecords_isEmpty() {
    assertEquals(TalerHeadline.Empty, headlineFor())
  }

  @Test
  fun headline_onlyIncomingOpen_asksTheUserToAct() {
    assertEquals(
      TalerHeadline.WaitingForMe(2),
      headlineFor(
        record(TalerPaymentStatus.OFFEN, isOwnPayment = false),
        record(TalerPaymentStatus.OFFEN, isOwnPayment = false),
        record(TalerPaymentStatus.ANGENOMMEN),
      )
    )
  }

  @Test
  fun headline_onlyOutgoingOpen_waitsForTheOtherSide() {
    assertEquals(
      TalerHeadline.WaitingForOther(1),
      headlineFor(
        record(TalerPaymentStatus.OFFEN, isOwnPayment = true),
        record(TalerPaymentStatus.ANGENOMMEN),
      )
    )
  }

  @Test
  fun headline_openOnBothSides_namesBothCounts() {
    assertEquals(
      TalerHeadline.WaitingForBoth(forMe = 1, forOther = 2),
      headlineFor(
        record(TalerPaymentStatus.OFFEN, isOwnPayment = false),
        record(TalerPaymentStatus.OFFEN, isOwnPayment = true),
        record(TalerPaymentStatus.OFFEN, isOwnPayment = true),
      )
    )
  }

  @Test
  fun headline_openBeatsUnverifiable() {
    assertEquals(
      TalerHeadline.WaitingForMe(1),
      headlineFor(
        record(TalerPaymentStatus.OFFEN, isOwnPayment = false),
        record(TalerPaymentStatus.UNBEKANNT_OFFLINE),
        record(TalerPaymentStatus.UNBEKANNT_OFFLINE),
      )
    )
  }

  @Test
  fun headline_unverifiableBeatsAllSettled() {
    assertEquals(
      TalerHeadline.Unverifiable(1),
      headlineFor(
        record(TalerPaymentStatus.ANGENOMMEN),
        record(TalerPaymentStatus.ABGELAUFEN),
        record(TalerPaymentStatus.UNBEKANNT_OFFLINE),
      )
    )
  }

  @Test
  fun headline_nothingPending_reportsTheTotal() {
    assertEquals(
      TalerHeadline.AllSettled(3),
      headlineFor(
        record(TalerPaymentStatus.ANGENOMMEN),
        record(TalerPaymentStatus.ABGELAUFEN),
        record(TalerPaymentStatus.LOKAL_ABGELEHNT),
      )
    )
  }

  @Test
  fun aggregate_countsOnlyOfflineAsUnverifiable() {
    val result = TalerThreadSummary.aggregate(
      listOf(
        record(TalerPaymentStatus.UNBEKANNT_OFFLINE),
        record(TalerPaymentStatus.UNBEKANNT_OFFLINE),
        record(TalerPaymentStatus.UNGUELTIG),
        record(TalerPaymentStatus.ANGENOMMEN),
        record(TalerPaymentStatus.ABGELAUFEN),
      )
    )

    assertEquals(2, result.unverifiable)
  }
}
