package org.thoughtcrime.securesms.taler

import org.signal.core.util.AppForegroundObserver
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.TalerUriRefreshJob
import org.thoughtcrime.securesms.jobs.TalerUriRefreshJob.TriggerType
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
  private var fastPollTimer: Timer? = null
  private var fastPollTimerActive = false

  // Bug 3 Fix: Fast-Polling nach Accept/Return - verkuerztes Intervall
  // fuer kurz zuvor geaenderte URIs, um UI-Latenz zu reduzieren.
  private const val FAST_POLL_INTERVAL_MS = 2_000L  // 2 Sekunden
  private val FAST_POLL_DURATION_MS = TimeUnit.MINUTES.toMillis(2)  // 2 Minuten

  // URIs, die sich im Fast-Poll-Modus befinden. Thread-safe via synchronized.
  private val fastPollUris = mutableSetOf<String>()

  // Cleanup-Timer pro URI, um Memory-Leaks zu vermeiden.
  // Thread-safe via synchronized (zugriff nur innerhalb von synchronized(fastPollUris)).
  private val fastPollCleanupTimers = mutableMapOf<String, Timer>()

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

        // Bug 3 Fix: Starte Fast-Poll-Timer wenn es aktive URIs gibt
        startFastPollTimerIfNeeded()
      }

      override fun onBackground() {
        Log.d(TAG, "App im Hintergrund - Poll-Intervall gestoppt")
        timer?.cancel()
        timer = null

        // Bug 3 Fix: Stoppe Fast-Poll-Timer und cleanup
        stopFastPollTimer()
        synchronized(fastPollUris) {
          fastPollCleanupTimers.values.forEach { it.cancel() }
          fastPollCleanupTimers.clear()
          fastPollUris.clear()
        }
      }
    })
  }

  /**
   * Startet den Fast-Poll-Timer (2s Intervall) wenn es aktive URIs gibt.
   * Thread-safe: darf von jedem Thread aus aufgerufen werden.
   */
  private fun startFastPollTimerIfNeeded() {
    synchronized(fastPollUris) {
      if (fastPollTimerActive || fastPollUris.isEmpty()) return
      fastPollTimerActive = true
      val t = Timer("TalerFastPolling", true)
      t.scheduleAtFixedRate(
        object : TimerTask() {
          override fun run() = refreshFastPollOnly()
        },
        0,  // Sofortiger erster Lauf
        FAST_POLL_INTERVAL_MS
      )
      fastPollTimer = t
      Log.d(TAG, "Fast-Poll-Timer gestartet (Intervall: ${FAST_POLL_INTERVAL_MS}ms)")
    }
  }

  /**
   * Stoppt den Fast-Poll-Timer.
   */
  private fun stopFastPollTimer() {
    synchronized(fastPollUris) {
      fastPollTimer?.cancel()
      fastPollTimer = null
      fastPollTimerActive = false
      Log.d(TAG, "Fast-Poll-Timer gestoppt")
    }
  }

  private fun refreshPending() {
    val now = System.currentTimeMillis()
    val candidates = SignalDatabase.talerPayments.getPollCandidates(
      limit = POLL_CAP,
      ttlCutoffMillis = now - TTL_MS,
    )
    var enqueued = 0

    // Bug 3 Fix: Fast-Poll-URIs haben Prioritaet - sie werden SOFORT
    // (ohne Backoff-Wartezeit) und mit RETURN-TriggerType gequeued, um
    // die UI-Latenz nach Accept/Return zu minimieren.
    for (candidate in candidates) {
      if (isFastPollUri(candidate.uri)) {
        AppDependencies.jobManager.add(TalerUriRefreshJob(candidate.uri, TriggerType.RETURN))
        enqueued++
      }
    }

    // Regulaere Kandidaten mit Backoff-Check
    for (candidate in candidates) {
      // Ueberspringe URIs, die bereits als Fast-Poll behandelt wurden
      if (isFastPollUri(candidate.uri)) continue

      val dueAt = (candidate.lastCheckedAt ?: 0L) + backoffMs(candidate.consecutiveFailures)
      if (now >= dueAt) {
        AppDependencies.jobManager.add(TalerUriRefreshJob(candidate.uri, TriggerType.ROUTINE))
        enqueued++
      }
    }
    Log.d(TAG, "Poll-Zyklus: $enqueued von ${candidates.size} Kandidaten angefragt (Rest im Backoff)")
  }

  /**
   * Bug 3 Fix: Refresht NUR Fast-Poll-URIs mit 2s-Intervall.
   * Wird vom Fast-Poll-Timer aufgerufen (nicht vom Haupt-Poll-Timer).
   */
  private fun refreshFastPollOnly() {
    synchronized(fastPollUris) {
      if (fastPollUris.isEmpty()) {
        // Keine aktiven Fast-Poll-URIs mehr - Timer stoppen
        stopFastPollTimer()
        return
      }
    }

    val candidates = SignalDatabase.talerPayments.getPollCandidates(
      limit = POLL_CAP,
      ttlCutoffMillis = System.currentTimeMillis() - TTL_MS,
    )
    var enqueued = 0
    for (candidate in candidates) {
      if (isFastPollUri(candidate.uri)) {
        AppDependencies.jobManager.add(TalerUriRefreshJob(candidate.uri, TriggerType.RETURN))
        enqueued++
      }
    }
    Log.d(TAG, "Fast-Poll-Zyklus: $enqueued Fast-Poll-URIs angefragt")
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

  /**
   * Bug 3 Fix: Fuegt eine URI zum Fast-Poll-Modus hinzu. Die URI wird
   * fuer [FAST_POLL_DURATION_MS] mit verkuerztem Intervall ([FAST_POLL_INTERVAL_MS])
   * gepollt und dann automatisch entfernt. Daher kann diese Methode von
   * jeder Thread aus aufgerufen werden (z.B. UI-Thread aus TalerReturnActivity).
   *
   * @param uri Die Taler-URI, die sofort und haeufiger gepollt werden soll.
   */
  fun requestFastPoll(uri: String) {
    var startTimer = false
    synchronized(fastPollUris) {
      if (fastPollUris.add(uri)) {
        Log.d(TAG, "Fast-Poll aktiviert fuer URI: ${TalerCorrelation.shortHash(uri)}")
        // Sofortiger Trigger - kein Warten auf naechsten Timer-Tick
        AppDependencies.jobManager.add(TalerUriRefreshJob(uri, TriggerType.RETURN))

        // Timer zum automatischen Entfernen nach FAST_POLL_DURATION_MS
        val cleanupTimer = Timer("TalerFastPollCleanup-$uri", true)
        cleanupTimer.schedule(object : TimerTask() {
          override fun run() {
            synchronized(fastPollUris) {
              fastPollUris.remove(uri)
              fastPollCleanupTimers.remove(uri)
              Log.d(TAG, "Fast-Poll deaktiviert fuer URI: ${TalerCorrelation.shortHash(uri)}")
              // Wenn keine URIs mehr im Fast-Poll-Modus sind, stoppe den Timer
              if (fastPollUris.isEmpty()) {
                stopFastPollTimer()
              }
            }
            cleanupTimer.cancel()
          }
        }, FAST_POLL_DURATION_MS)

        fastPollCleanupTimers[uri] = cleanupTimer
        // Start Flag - Timer wird aussen gestartet (kein nested synchronized)
        startTimer = true
      }
    }
    // Starte Fast-Poll-Timer wenn noetig (ausserhalb von synchronized, um Deadlock zu vermeiden)
    if (startTimer) {
      startFastPollTimerIfNeeded()
    }
  }

  /**
   * Gibt zurueck, ob die URI sich im Fast-Poll-Modus befindet.
   * Thread-safe via synchronized.
   */
  private fun isFastPollUri(uri: String): Boolean {
    synchronized(fastPollUris) {
      return fastPollUris.contains(uri)
    }
  }
}
