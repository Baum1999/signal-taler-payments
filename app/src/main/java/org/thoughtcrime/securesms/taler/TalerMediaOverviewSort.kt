/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import org.thoughtcrime.securesms.database.TalerPaymentRecord

/**
 * Reine Sortierlogik fuer den Taler-Tab der Medien-Uebersicht - getrennt von
 * der Fragment-Klasse, damit sie ohne Android-Kontext testbar ist (gleiches
 * Muster wie TalerCardActionGate). Kotlins sortedByDescending ist stabil,
 * gleiche createdAt-Werte (z.B. Gruppen-Split-Anteile derselben Nachricht)
 * behalten deshalb ihre urspruengliche Relativreihenfolge statt zufaellig
 * vertauscht zu werden.
 */
object TalerMediaOverviewSort {
  fun newestFirst(records: List<TalerPaymentRecord>): List<TalerPaymentRecord> =
    records.sortedByDescending { it.createdAt }
}
