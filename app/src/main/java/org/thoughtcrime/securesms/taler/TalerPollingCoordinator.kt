package org.thoughtcrime.securesms.taler

import org.signal.core.util.AppForegroundObserver
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.TalerUriRefreshJob
import java.util.Timer
import java.util.TimerTask
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
    val uris = SignalDatabase.talerPayments.getNonTerminalUris()
    for (uri in uris) {
      AppDependencies.jobManager.add(TalerUriRefreshJob(uri))
    }
  }
}
