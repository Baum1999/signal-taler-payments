package org.thoughtcrime.securesms.taler

import org.signal.core.util.AppForegroundObserver
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.TalerUriRefreshJob
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polling fuer Taler-Vorgaenge: fester Intervall waehrend die App im
 * Vordergrund ist, plus sofortiger Trigger bei App-Resume (docs/API.md,
 * Abschnitt "Zustaende": "Source of Truth ist Taler ... Polling mit festem
 * Intervall plus Trigger bei App-Resume - nicht dauerhaft im Hintergrund
 * pollen").
 *
 * Wird lazy beim ersten Erkennen einer Taler-URI gestartet ([TalerPaymentTracker]),
 * nicht schon beim App-Start - so entsteht keine Hintergrund-Maschinerie,
 * solange niemand Taler-Links benutzt.
 */
object TalerPollingCoordinator {
  private val TAG = Log.tag(TalerPollingCoordinator::class.java)
  private const val POLL_INTERVAL_MS = 20_000L

  // REVIEW.md B2: Cap, TTL und Backoff, damit ein Chat mit vielen offen
  // liegen gebliebenen Zahlungslinks keine unbegrenzt wachsende Zahl von
  // wallet-core-Aufrufen erzeugt.
  //
  // POLL_CAP ist ein SQL-LIMIT auf getPollCandidates() - eine harte
  // Obergrenze pro Zyklus, unabhaengig vom Backoff-Zustand einzelner URIs.
  // 200 ist grosszuegig ueber jeder plausiblen Zahl gleichzeitig offener
  // Zahlungslinks eines einzelnen Nutzers gewaehlt, begrenzt aber trotzdem
  // den Extremfall (Chat-Flut mit vielen URIs) nach oben.
  private const val POLL_CAP = 200

  // TTL fuer die beiden unsicheren Zustaende UNBEKANNT_OFFLINE/
  // TALER_NICHT_VERBUNDEN (nicht fuer OFFEN, siehe getPollCandidates()).
  // 24 Stunden wie im Review vorgeschlagen: lang genug, dass ein Nutzer, der
  // Taler erst am Folgetag verbindet/installiert, die Karte noch aktuell
  // sieht - kurz genug, dass ein Chat mit vielen nie beantworteten Links
  // nicht auf Dauer mitgepollt wird. Die Karte bleibt sichtbar
  // (Tombstone-Prinzip, docs/API.md 1.4), es wird nur das Polling
  // eingestellt.
  private val TTL_MS = TimeUnit.HOURS.toMillis(24)

  // Exponentieller Backoff pro URI nach Fehlschlaegen (TalerLinkResult.Fehler,
  // siehe TalerUriRefreshJob/TalerPaymentTable.recordFailure) statt eines
  // flachen 20s-Intervalls fuer alle. Wer schon mehrfach hintereinander
  // fehlgeschlagen ist (z.B. Taler-App haengt, Binder-Fehler), wird seltener
  // angefragt - erfolgreiche/verbindungsbezogene Statuswechsel setzen den
  // Zaehler zurueck (TalerPaymentTable.updateFromPreview/updateStatus).
  private val MAX_BACKOFF_MS = TimeUnit.MINUTES.toMillis(20)

  private val started = AtomicBoolean(false)
  private var timer: Timer? = null

  fun ensureStarted() {
    if (!started.compareAndSet(false, true)) return

    AppForegroundObserver.addListener(object : AppForegroundObserver.Listener {
      override fun onForeground() {
        Log.d(TAG, "App im Vordergrund - Trigger + Start Poll-Intervall")
        refreshPending()
        val t = Timer("TalerPolling", true)
        t.scheduleAtFixedRate(
          object : TimerTask() {
            override fun run() = refreshPending()
          },
          POLL_INTERVAL_MS,
          POLL_INTERVAL_MS
        )
        timer = t
      }

      override fun onBackground() {
        Log.d(TAG, "App im Hintergrund - Poll-Intervall gestoppt")
        timer?.cancel()
        timer = null
      }
    })
  }

  private fun refreshPending() {
    val now = System.currentTimeMillis()
    val candidates = SignalDatabase.talerPayments.getPollCandidates(
      limit = POLL_CAP,
      ttlCutoffMillis = now - TTL_MS,
    )
    var enqueued = 0
    for (candidate in candidates) {
      val dueAt = (candidate.lastCheckedAt ?: 0L) + backoffMs(candidate.consecutiveFailures)
      if (now >= dueAt) {
        AppDependencies.jobManager.add(TalerUriRefreshJob(candidate.uri))
        enqueued++
      }
    }
    Log.d(TAG, "Poll-Zyklus: $enqueued von ${candidates.size} Kandidaten angefragt (Rest im Backoff)")
  }

  /**
   * 0 Fehlschlaege -> kein Backoff (normales POLL_INTERVAL_MS greift ueber
   * den naechsten Timer-Tick). Ab dem ersten Fehlschlag verdoppelt sich das
   * Intervall pro weiterem Fehlschlag, gedeckelt bei [MAX_BACKOFF_MS]. Der
   * Exponent ist zusaetzlich hart begrenzt, damit `shl` bei sehr vielen
   * Fehlschlagen nicht ueberlaeuft - bei 2^20 ist MAX_BACKOFF_MS laengst
   * erreicht, das ist reine Ueberlauf-Absicherung.
   */
  private fun backoffMs(consecutiveFailures: Int): Long {
    if (consecutiveFailures <= 0) return 0L
    val exponent = consecutiveFailures.coerceAtMost(20)
    return (POLL_INTERVAL_MS shl exponent).coerceAtMost(MAX_BACKOFF_MS)
  }
}
