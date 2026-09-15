package org.thoughtcrime.securesms.taler

import org.thoughtcrime.securesms.database.TalerPaymentRecord
import java.math.BigDecimal

/**
 * Saldo einer einzelnen Waehrung ueber alle abgeschlossenen Vorgaenge eines
 * Threads. Ein- und Ausgang bleiben getrennt, weil die Kopfzeile beide Seiten
 * zeigen koennen muss ("+17,50 / -5,00") - [net] ist nur die Verrechnung
 * daraus.
 */
data class CurrencyBalance(
  val currency: String,
  val inflow: BigDecimal,
  val outflow: BigDecimal,
) {
  val net: BigDecimal get() = inflow.subtract(outflow)
}

/**
 * Verdichtung aller Taler-Vorgaenge eines Threads - die Zahlen, aus denen die
 * Kopfzeile im Taler-Tab ihre drei Zeilen baut.
 */
data class TalerThreadAggregate(
  val total: Int,
  val openForMe: Int,
  val openForOther: Int,
  val unverifiable: Int,
  val latest: TalerPaymentRecord?,
  val balances: List<CurrencyBalance>,
  val exchanges: List<String>,
  val lastCheckedAt: Long?,
)

/**
 * Welche Aussage die Kopfzeile trifft. Bewusst ein eigener Typ statt eines
 * fertigen Strings: die Rangfolge ("Handlungsbedarf schlaegt Wissensluecke
 * schlaegt erledigt") ist die eigentliche Logik und bleibt so ohne
 * Android-Context pruefbar - die Zuordnung zu Text, Icon und Farbe passiert
 * erst in der View.
 */
sealed interface TalerHeadline {
  /** Keine Taler-Vorgaenge in diesem Chat. */
  object Empty : TalerHeadline

  /** [count] eingehende Vorgaenge warten auf eine Entscheidung des Nutzers. */
  data class WaitingForMe(val count: Int) : TalerHeadline

  /** [count] eigene Vorgaenge warten auf die Gegenseite. */
  data class WaitingForOther(val count: Int) : TalerHeadline

  /** Auf beiden Seiten offen - beide Zahlen, damit keine davon verschwindet. */
  data class WaitingForBoth(val forMe: Int, val forOther: Int) : TalerHeadline

  /** [count] Vorgaenge konnten nicht geprueft werden (Taler fehlt/nicht verbunden). */
  data class Unverifiable(val count: Int) : TalerHeadline

  /** Nichts offen, nichts unklar - [count] abgeschlossene Vorgaenge. */
  data class AllSettled(val count: Int) : TalerHeadline
}

/**
 * Rechenschicht hinter der Zusammenfassung im Taler-Tab. Bewusst ohne
 * Context/Strings - die Formatierung passiert getrennt, damit genau die
 * Faelle testbar bleiben, die die Kopfzeile bestimmen (wer ist dran, was war
 * zuletzt, wie steht der Saldo). Gleiche Aufteilung wie AmountSplit auf der
 * Taler-Seite.
 */
object TalerThreadSummary {

  /**
   * Zustaende, in denen Signal den Vorgang nicht pruefen konnte - die App
   * fehlt, ist nicht vertrauenswuerdig, ist nicht verbunden, oder der letzte
   * Abruf schlug fehl. Das sind keine Zahlungszustaende, sondern Wissensluecken,
   * und muessen in der Kopfzeile deshalb getrennt von "erledigt" auftauchen.
   */
  private val UNVERIFIABLE_STATUSES = setOf(
    TalerPaymentStatus.UNBEKANNT_OFFLINE,
    TalerPaymentStatus.TALER_NICHT_VERBUNDEN,
    TalerPaymentStatus.NICHT_INSTALLIERT,
    TalerPaymentStatus.NICHT_VERTRAUENSWUERDIG,
  )

  fun aggregate(records: List<TalerPaymentRecord>): TalerThreadAggregate {
    val open = records.filter { it.status == TalerPaymentStatus.OFFEN }

    return TalerThreadAggregate(
      total = records.size,
      // isOwnPayment allein entscheidet, wer am Zug ist - dieselbe Regel, die
      // TalerPaymentCardPresenter.getCompactStatus() fuer die Chat-Karte nutzt.
      openForMe = open.count { !it.isOwnPayment },
      openForOther = open.count { it.isOwnPayment },
      unverifiable = records.count { it.status in UNVERIFIABLE_STATUSES },
      latest = latestOf(records),
      balances = balancesOf(records),
      exchanges = records.mapNotNull { it.exchangeBaseUrl }.distinct().sorted(),
      lastCheckedAt = records.mapNotNull { it.lastCheckedAt }.maxOrNull(),
    )
  }

  /**
   * Die eine Aussage, die ganz oben steht. Rangfolge: was der Nutzer tun muss,
   * schlaegt was Signal nicht weiss, schlaegt was erledigt ist - eine
   * Wissensluecke darf nie als "alles abgeschlossen" durchgehen, und ein
   * offener Vorgang nie hinter einer Wissensluecke verschwinden.
   */
  fun headline(aggregate: TalerThreadAggregate): TalerHeadline = with(aggregate) {
    when {
      openForMe > 0 && openForOther > 0 -> TalerHeadline.WaitingForBoth(openForMe, openForOther)
      openForMe > 0 -> TalerHeadline.WaitingForMe(openForMe)
      openForOther > 0 -> TalerHeadline.WaitingForOther(openForOther)
      unverifiable > 0 -> TalerHeadline.Unverifiable(unverifiable)
      total > 0 -> TalerHeadline.AllSettled(total)
      else -> TalerHeadline.Empty
    }
  }

  /**
   * Der zuletzt erkannte Vorgang. Bei gleichem [TalerPaymentRecord.createdAt]
   * entscheidet die URI, damit das Ergebnis nicht von der Zeilenreihenfolge
   * aus der Datenbank abhaengt.
   */
  private fun latestOf(records: List<TalerPaymentRecord>): TalerPaymentRecord? =
    records.maxWithOrNull(compareBy({ it.createdAt }, { it.uri }))

  /**
   * Nur ANGENOMMEN zaehlt: offene, abgelehnte, abgebrochene und abgelaufene
   * Vorgaenge haben nie Geld bewegt und gehoeren nicht in den Saldo.
   */
  private fun balancesOf(records: List<TalerPaymentRecord>): List<CurrencyBalance> =
    records
      .filter { it.status == TalerPaymentStatus.ANGENOMMEN && it.currency != null }
      .groupBy { it.currency!! }
      .map { (currency, forCurrency) ->
        CurrencyBalance(
          currency = currency,
          inflow = forCurrency.filter { !it.isOwnPayment }.sumAmounts(),
          outflow = forCurrency.filter { it.isOwnPayment }.sumAmounts(),
        )
      }
      .sortedBy { it.currency }

  private fun List<TalerPaymentRecord>.sumAmounts(): BigDecimal =
    fold(BigDecimal.ZERO) { acc, record ->
      acc.add(record.amount?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
    }
}
