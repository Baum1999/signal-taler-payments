package org.thoughtcrime.securesms.taler

import net.taler.wallet.link.TalerHttpsLink

/**
 * Flache Anzeige-Metadaten des compose-abort-Links (spec-multi-uri-link.md
 * 3.1/3.2). Taler entscheidet nie auf ihrer Grundlage. Bewusst ohne Betraege:
 * Taler zeigt ohnehin nur die Werte aus der eigenen Transaktion.
 */
data class TalerAbortMeta(
  val reason: String,
  val isGroup: Boolean,
  val recipientHint: String?,
  val memberCount: Int?,
) {
  companion object {
    const val REASON_MENU = "menu"
    const val MAX_RECIPIENT_HINT_LENGTH = 80
  }
}

/**
 * Ein laufender compose-abort: [uris] ist der gerade an Taler geschickte
 * Stapel (so wie in der Datenbank gespeichert, ggf. als DD-103-https-Link),
 * [remainingBatches] die noch nicht geschickten Stapel. Der naechste startet
 * erst nach dem Ruecksprung des vorherigen.
 */
data class TalerAbortRequest(
  val uris: List<String>,
  val remainingBatches: List<List<String>>,
  val meta: TalerAbortMeta,
  val threadId: Long,
)

/** Ergebnis je URI im Ruecksprung (link-protokoll.md Abschnitt 9). */
enum class TalerAbortOutcome(val wireValue: String) {
  ABORTED("aborted"),
  ALREADY_CLAIMED("already_claimed"),
  EXPIRED("expired"),
  DECLINED("declined"),
  NOT_OWN("not_own"),
  FAILED("failed");

  companion object {
    fun fromWire(value: String): TalerAbortOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

data class TalerAbortResult(val uri: String, val outcome: TalerAbortOutcome)

/** Was Signal mit einem Abbruch-Ergebnis tut. */
sealed interface TalerAbortFollowUp {
  /** `aborted`: erst der Exchange belegt den Abbruch (TalerUriRefreshJob.TriggerType.ABORT_CHECK). */
  data object VerifyAbort : TalerAbortFollowUp

  /** Status direkt setzen (`expired` -> ABGELAUFEN). */
  data class SetStatus(val status: TalerPaymentStatus) : TalerAbortFollowUp

  /** Nichts erzwingen, nur den echten Zustand abfragen. */
  data object Refresh : TalerAbortFollowUp
}

/**
 * Reine Entscheidungslogik fuer compose-abort, Spiegel von signal-ios
 * TalerAbortPlan. Kein DB-Zugriff, kein Android: URIs sind Inhaberpapiere
 * und werden nie geloggt.
 *
 * "Abbrechen" laeuft immer ueber compose-abort, auch bei nur einer URI, nie
 * ueber den Aktionslink taler://pay-push/...: den deutet Taler bei einer URI,
 * die seine Wallet nicht kennt, als "Annehmen". compose-abort beantwortet
 * dieselbe Lage mit `not_own`.
 */
object TalerAbortPlan {

  /** Hoechstens so viele URIs traegt ein compose-abort-Link. */
  const val MAX_URIS_PER_LINK = 32

  /** Teilt [uris] (Dubletten entfernt, Reihenfolge erhalten) in Stapel zu hoechstens [MAX_URIS_PER_LINK]. */
  fun batches(uris: List<String>): List<List<String>> = uris.distinct().chunked(MAX_URIS_PER_LINK)

  /**
   * Liest die `result`-Werte des Ruecksprungs. Geteilt wird am ersten `|`;
   * ein Eintrag ohne `|` oder mit unbekanntem Ergebnis wird einzeln verworfen.
   */
  fun parseResults(values: List<String>): List<TalerAbortResult> =
    values.mapNotNull { value ->
      val separator = value.indexOf('|')
      if (separator <= 0) return@mapNotNull null
      val outcome = TalerAbortOutcome.fromWire(value.substring(separator + 1)) ?: return@mapNotNull null
      TalerAbortResult(value.substring(0, separator), outcome)
    }

  /**
   * Bildet die Ergebnisse auf Folgeaktionen ab: `aborted` -> erst
   * verifizieren, `expired` -> ABGELAUFEN, alles andere (`already_claimed`,
   * `declined`, `not_own`, `failed`) -> nur Refresh. Ergebnisse fuer URIs,
   * die nicht in [allowed] stehen, werden ignoriert (der Ruecksprung ist
   * angreiferkontrolliert). Verglichen wird entpackt: Taler bekommt und meldet
   * taler://, der Datensatz kann den DD-103-https-Link tragen; zurueck kommt
   * immer die URI aus [allowed].
   */
  fun apply(results: List<TalerAbortResult>, allowed: List<String>): List<Pair<String, TalerAbortFollowUp>> {
    val allowedByKey = LinkedHashMap<String, String>()
    allowed.forEach { allowedByKey.putIfAbsent(TalerHttpsLink.unwrap(it), it) }
    return results.mapNotNull { result ->
      val original = allowedByKey[TalerHttpsLink.unwrap(result.uri)] ?: return@mapNotNull null
      val followUp = when (result.outcome) {
        TalerAbortOutcome.ABORTED -> TalerAbortFollowUp.VerifyAbort
        TalerAbortOutcome.EXPIRED -> TalerAbortFollowUp.SetStatus(TalerPaymentStatus.ABGELAUFEN)
        TalerAbortOutcome.ALREADY_CLAIMED,
        TalerAbortOutcome.DECLINED,
        TalerAbortOutcome.NOT_OWN,
        TalerAbortOutcome.FAILED -> TalerAbortFollowUp.Refresh
      }
      original to followUp
    }
  }

  /**
   * Ob nach diesem Ruecksprung der naechste Stapel starten darf: nur wenn
   * Taler ueberhaupt geantwortet und der Nutzer nicht alles abgelehnt hat.
   */
  fun shouldContinue(results: List<TalerAbortResult>): Boolean =
    results.isNotEmpty() && !results.all { it.outcome == TalerAbortOutcome.DECLINED }
}
