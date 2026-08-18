package org.thoughtcrime.securesms.taler

import android.app.Activity
import android.os.Bundle
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.TalerUriRefreshJob

/**
 * Ruecksprungziel fuer den Annehmen-Flow (docs/API.md 2.10),
 * signalfuergnu://taler-return. Exportiert, aber die eingehenden Parameter
 * sind ANGREIFERKONTROLLIERT (Regel: jede angezeigte Zahl/jeder Zustand
 * kommt aus dem Taler-AIDL-Aufruf, nie aus einem Intent-Extra) - `status`
 * aus dem Intent wird deshalb NIE angezeigt oder fuer die Kartenanzeige
 * uebernommen, nur als Trigger fuer einen sofortigen Re-Check per
 * TalerUriRefreshJob genutzt. Eine unbekannte/abgelaufene correlationId
 * wird kommentarlos verworfen (finish(), kein Fehlertext, kein Log mit der
 * URI).
 */
class TalerReturnActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Sicherheit (ruled during execution, round 1 - siehe Ledger): exported=true
    // bedeutet, dass der Manifest-<data>-Filter (scheme/host) NUR fuer implizite
    // Intents gilt - ein expliziter Intent (setClassName) jeder anderen App
    // erreicht diese Activity mit BELIEBIGER data-Uri, am Filter vorbei.
    // Uri.getQueryParameter() wirft UnsupportedOperationException auf einer
    // opaken (nicht-hierarchischen) Uri (z. B. "signalfuergnu:foo" ohne "//")
    // - deshalb der isHierarchical-Check VOR dem Zugriff. try/finally macht
    // "immer beenden, nie sichtbar abstuerzen" strukturell garantiert statt
    // von manuell platzierten finish()-Aufrufen abhaengig.
    try {
      val data = intent?.data
      val correlationId = if (data?.isHierarchical == true) data.getQueryParameter("correlationId") else null
      val entry = correlationId?.let { TalerCorrelationStore.take(it) } ?: return

      AppDependencies.jobManager.add(TalerUriRefreshJob(entry.uri))

      val recipientId = SignalDatabase.threads.getRecipientIdForThreadId(entry.threadId)
      if (recipientId != null) {
        ConversationIntents.createBuilderSync(this, recipientId, entry.threadId).build().let { conversationIntent ->
          startActivity(conversationIntent)
        }
      }
    } finally {
      finish()
    }
  }
}
