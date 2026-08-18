package org.thoughtcrime.securesms.taler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * correlationId -> (uri, threadId), TTL 15 Minuten (docs/API.md 2.10).
 * In-Memory, kein Persistenz-Bedarf: ueberlebt einen Prozess-Neustart nicht,
 * genau wie TalerPollingCoordinator.started (og. Design-Entscheidung dort) -
 * ein verwaister Eintrag nach Prozess-Tod ist unschaedlich (Rueckspruenge
 * fuer laengst beendete Vorgaenge werden ohnehin nach 15 Minuten ignoriert).
 * [take] ist Single-Use: eine correlationId gehoert zu genau einem
 * Annehmen-Versuch, ein zweiter Rücksprung mit derselben ID (z. B. durch
 * Replay) findet nichts mehr.
 */
object TalerCorrelationStore {

  data class Entry(val uri: String, val threadId: Long)

  private data class StoredEntry(val entry: Entry, val createdAt: Long)

  private val TTL_MS = TimeUnit.MINUTES.toMillis(15)
  private val entries = ConcurrentHashMap<String, StoredEntry>()

  fun put(correlationId: String, uri: String, threadId: Long) {
    entries[correlationId] = StoredEntry(Entry(uri, threadId), System.currentTimeMillis())
  }

  fun take(correlationId: String, now: Long = System.currentTimeMillis()): Entry? {
    val stored = entries.remove(correlationId) ?: return null
    if (now - stored.createdAt > TTL_MS) return null
    return stored.entry
  }
}
