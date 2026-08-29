package org.thoughtcrime.securesms.taler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Welcher Ruecksprung-Flow zu einer correlationId gehoert. Ersetzt seit
 * Meilenstein 6 die vorherige implizite Unterscheidung ueber `uri == null`
 * (die fuer nur zwei Faelle reichte, aber keinen Platz fuer einen dritten
 * hatte - REFUND braucht wie ACCEPT_OR_CANCEL eine bekannte uri (die
 * originalUri, nur fuer Kontext/Log, NICHT zum Fast-Poll wie bei
 * ACCEPT_OR_CANCEL), verhaelt sich beim Ruecksprung aber wie SEND (Taler hat
 * bereits committet, eine neue Zahlung mit neuer URI entsteht) - siehe
 * TalerReturnActivity).
 */
enum class TalerCorrelationIntent { ACCEPT_OR_CANCEL, SEND, REFUND }

/**
 * correlationId -> (intent, uri, threadId), TTL 15 Minuten (docs/API.md 2.10).
 * In-Memory, kein Persistenz-Bedarf: ueberlebt einen Prozess-Neustart nicht.
 *
 * WICHTIG (seit Meilenstein 5, gilt NICHT mehr uneingeschraenkt fuer alle
 * drei Intents): fuer ACCEPT_OR_CANCEL stimmt die urspruengliche Begruendung
 * noch - ein verwaister Eintrag nach Prozess-Tod ist unschaedlich, weil
 * Taler-seitig noch nichts committet wurde und ein Rücksprung fuer einen
 * laengst beendeten Vorgang ohnehin ignoriert werden soll. Fuer SEND und
 * REFUND gilt das NICHT mehr: Taler committet die Zahlung
 * (initiatePeerPushDebit) bereits VOR dem Rücksprung nach Signal. Geht der
 * Eintrag in dieser Richtung verloren (Prozess-Tod zwischen Start und
 * Rücksprung, oder TTL-Ablauf waehrend eines laengeren Taler-seitigen
 * Vorgangs, z. B. Balance aufladen), hat der Nutzer eine bereits committete,
 * bezahlte Ueberweisung, aber Signal verschickt dafuer NIE eine Nachricht und
 * zeigt NIE eine Karte - kein Fehler, keine Bestaetigung, auf keiner Seite.
 * Das ist eine bekannte, aktuell akzeptierte Einschraenkung, kein bewusstes
 * Sicherheitsdesign - eine bessere Loesung (laengeres/persistentes TTL fuer
 * SEND/REFUND, oder eine unknown-vs-lost-Unterscheidung mit Nutzerfeedback im
 * Rücksprungpfad) ist fuer einen spaeteren Meilenstein vorgesehen.
 *
 * [take] ist Single-Use: eine correlationId gehoert zu genau einem
 * Annehmen-, Compose-Send- bzw. Compose-Refund-Versuch, ein zweiter
 * Rücksprung mit derselben ID (z. B. durch Replay) findet nichts mehr.
 */
object TalerCorrelationStore {

  data class Entry(val intent: TalerCorrelationIntent, val uri: String?, val threadId: Long)

  private data class StoredEntry(val entry: Entry, val createdAt: Long)

  private val TTL_MS = TimeUnit.MINUTES.toMillis(15)
  private val entries = ConcurrentHashMap<String, StoredEntry>()

  fun put(correlationId: String, intent: TalerCorrelationIntent, uri: String?, threadId: Long) {
    entries[correlationId] = StoredEntry(Entry(intent, uri, threadId), System.currentTimeMillis())
  }

  fun take(correlationId: String, now: Long = System.currentTimeMillis()): Entry? {
    val stored = entries.remove(correlationId) ?: return null
    if (now - stored.createdAt > TTL_MS) return null
    return stored.entry
  }
}
