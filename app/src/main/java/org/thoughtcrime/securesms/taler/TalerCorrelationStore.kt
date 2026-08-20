package org.thoughtcrime.securesms.taler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * correlationId -> (uri, threadId), TTL 15 Minuten (docs/API.md 2.10).
 * In-Memory, kein Persistenz-Bedarf: ueberlebt einen Prozess-Neustart nicht.
 *
 * WICHTIG (seit Meilenstein 5, gilt NICHT mehr uneingeschraenkt fuer beide
 * Richtungen): fuer den Annehmen-Flow (uri != null) stimmt die urspruengliche
 * Begruendung noch - ein verwaister Eintrag nach Prozess-Tod ist unschaedlich,
 * weil Taler-seitig noch nichts committet wurde und ein Rücksprung fuer einen
 * laengst beendeten Vorgang ohnehin ignoriert werden soll. Fuer den
 * Compose-Send-Flow (uri == null, Meilenstein 5) gilt das NICHT mehr: Taler
 * committet die Zahlung (initiatePeerPushDebit) bereits VOR dem Rücksprung
 * nach Signal. Geht der Eintrag in dieser Richtung verloren (Prozess-Tod
 * zwischen Start und Rücksprung, oder TTL-Ablauf waehrend eines laengeren
 * Taler-seitigen Vorgangs, z. B. Balance aufladen), hat der Nutzer eine
 * bereits committete, bezahlte Ueberweisung, aber Signal verschickt dafuer
 * NIE eine Nachricht und zeigt NIE eine Karte - kein Fehler, keine
 * Bestaetigung, auf keiner Seite. Das ist eine bekannte, aktuell akzeptierte
 * Einschraenkung dieses Meilensteins, kein bewusstes Sicherheitsdesign - eine
 * bessere Loesung (laengeres/persistentes TTL fuer den uri==null-Fall, oder
 * eine unknown-vs-lost-Unterscheidung mit Nutzerfeedback im Rücksprungpfad)
 * ist fuer einen spaeteren Meilenstein vorgesehen.
 *
 * [take] ist Single-Use: eine correlationId gehoert zu genau einem
 * Annehmen- bzw. Compose-Send-Versuch, ein zweiter Rücksprung mit derselben
 * ID (z. B. durch Replay) findet nichts mehr.
 */
object TalerCorrelationStore {

  data class Entry(val uri: String?, val threadId: Long)

  private data class StoredEntry(val entry: Entry, val createdAt: Long)

  private val TTL_MS = TimeUnit.MINUTES.toMillis(15)
  private val entries = ConcurrentHashMap<String, StoredEntry>()

  fun put(correlationId: String, uri: String?, threadId: Long) {
    entries[correlationId] = StoredEntry(Entry(uri, threadId), System.currentTimeMillis())
  }

  fun take(correlationId: String, now: Long = System.currentTimeMillis()): Entry? {
    val stored = entries.remove(correlationId) ?: return null
    if (now - stored.createdAt > TTL_MS) return null
    return stored.entry
  }
}
