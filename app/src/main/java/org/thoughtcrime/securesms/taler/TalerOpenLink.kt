/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.taler

import net.taler.wallet.link.TalerHttpsLink
import java.security.MessageDigest

/**
 * Chat zu einer Zahlung oeffnen per Hash (link-protokoll.md Abschnitt 12):
 * Taler schickt `<scheme>://taler-return?uriHash=<hex>&uriHash=<hex>...`
 * statt der URI. Den Hash kennt Taler auch dann noch, wenn die Zahlung
 * angenommen, abgelaufen oder abgebrochen ist und wallet-core die URI nicht
 * mehr liefert. Der Hash verraet nichts ueber das Inhaberpapier.
 *
 * Hash = SHA-256 ueber die UTF-8-Bytes der `taler://`-URI, klein als Hex,
 * wie Taler ihn im OwnUriTracker speichert und wie taler-ios ihn im
 * `taler-open`-Link schickt.
 */
object TalerOpenLink {

  const val URI_HASH_PARAM = "uriHash"
  const val MAX_HASHES = 32

  private val HASH = Regex("[0-9a-f]{64}")

  /** Gueltige Hashes in Link-Reihenfolge: klein gelesen, ohne Duplikate, hoechstens [MAX_HASHES]. */
  fun parseHashes(values: List<String>): List<String> =
    values.map { it.lowercase() }.filter { HASH.matches(it) }.distinct().take(MAX_HASHES)

  /**
   * Hash einer gespeicherten URI. Ein DD-103-Link aus dem Nachrichtentext
   * wird vorher zu seiner `taler://`-URI entpackt, denn Taler hasht die URI,
   * die es selbst erzeugt hat.
   */
  fun hashOf(uri: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(TalerHttpsLink.unwrap(uri).toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }

  /** Die Eintraege aus [candidates], deren URI-Hash in [hashes] steht, in der Reihenfolge von [hashes]. */
  fun <T> matches(hashes: List<String>, candidates: List<T>, uriOf: (T) -> String): List<T> {
    val hashed = candidates.map { hashOf(uriOf(it)) to it }
    return hashes.mapNotNull { hash -> hashed.firstOrNull { it.first == hash }?.second }
  }
}
