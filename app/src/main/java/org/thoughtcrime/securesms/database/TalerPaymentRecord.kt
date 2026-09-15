package org.thoughtcrime.securesms.database

import org.thoughtcrime.securesms.taler.TalerPaymentStatus

/**
 * Eine Zeile aus [TalerPaymentTable] - ein Vorgang pro Taler-URI.
 *
 * Bewusst in einer eigenen Datei statt neben der Tabelle: die Tabelle bringt
 * die gesamte Android-/SQLite-Abhaengigkeitskette mit, dieser Record dagegen
 * ist reines Kotlin. Dadurch bleibt die Auswertung darueber (siehe
 * [org.thoughtcrime.securesms.taler.TalerThreadSummary]) ohne Android-Kontext
 * testbar.
 */
data class TalerPaymentRecord(
  val uri: String,
  val threadId: Long,
  val uriKind: String?,
  val status: TalerPaymentStatus,
  val amount: String?,
  val currency: String?,
  val exchangeBaseUrl: String?,
  val summary: String?,
  val createdAt: Long,
  val lastCheckedAt: Long?,
  val consecutiveFailures: Int,
  val isOwnPayment: Boolean,
)

/**
 * Kandidat fuers Polling (REVIEW.md B2) - schlanker als [TalerPaymentRecord],
 * enthaelt nur, was [org.thoughtcrime.securesms.taler.TalerPollingCoordinator]
 * fuer die Backoff-Entscheidung braucht.
 */
data class TalerPaymentPollCandidate(
  val uri: String,
  val lastCheckedAt: Long?,
  val consecutiveFailures: Int,
)
