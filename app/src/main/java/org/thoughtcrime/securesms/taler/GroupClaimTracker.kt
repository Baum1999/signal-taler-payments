/*
 * This file is part of GNU Taler integration for Signal
 * (C) 2026 Taler Systems S.A.
 *
 * GNU Taler is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3, or (at your option) any later version.
 *
 * GNU Taler is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * GNU Taler; see the file COPYING.  If not, see <http://www.gnu.org/licenses/>
 */

package org.thoughtcrime.securesms.taler

import android.content.Context
import android.content.SharedPreferences

/**
 * Merkt sich dauerhaft, welche einzelnen Pay-Push-URIs DIESES Geraet bereits
 * als Teil eines Gruppen-Splits angenommen hat - eigene, kleine
 * SharedPreferences-Datei nach demselben Muster wie SentRefundStore.kt
 * (taler-android). Wird die Sammelkarte einer Gruppen-Split-Nachricht erneut
 * angezeigt (z.B. nach App-Neustart oder beim Scrollen), kann die UI so
 * erkennen, dass dieser Betrachter bereits einen der N Anteile beansprucht
 * hat, und ihn nicht versehentlich einen zweiten annehmen lassen.
 *
 * Reiner Vorhandensein-Speicher (Boolean-Keys), keine Werte noetig.
 */
class GroupClaimTracker(private val prefs: SharedPreferences) {

  constructor(context: Context) : this(
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  )

  fun track(uri: String) {
    prefs.edit().putBoolean(uri, true).apply()
  }

  fun hasClaimedAny(uris: List<String>): Boolean = uris.any { prefs.contains(it) }

  companion object {
    private const val PREFS_NAME = "taler_group_claims"
  }
}
