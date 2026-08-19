package org.thoughtcrime.securesms.taler

import android.content.Context
import net.taler.wallet.link.TalerUriKind
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.TalerUriRefreshJob
import org.thoughtcrime.securesms.jobs.TalerUriRefreshJob.TriggerType

/**
 * Gegenstueck zu TalerPaymentTracker fuer eingehende Bestaetigungsnachrichten
 * ("Zahlung fuer [Kind] akzeptiert (uri)", TalerConfirmationDetector). Die im
 * Text mitgeschickte URI ist nur Uebergangsloesung, solange das Chat-Protokoll
 * kein eigenes Referenzfeld erlaubt (siehe TalerUriRefreshJob.maybeSendAcceptConfirmation)
 * - sie dient hier ausschliesslich als DB-Schluessel, um die eigene Zahlung
 * wiederzufinden, nicht als vertrauenswuerdige Quelle. Ein geparster Match wird
 * deshalb nur uebernommen, wenn zur URI bereits eine bekannte eigene
 * (isOwnPayment=true) Zahlung passenden Kinds existiert - sonst bleibt die
 * Nachricht ganz normaler Text (kein Icon, kein Verwerfen), z.B. wenn jemand
 * die Phrase manuell tippt oder eine fremde URI hineinkopiert.
 */
object TalerConfirmationTracker {
  fun trackConfirmationInBody(context: Context, body: String?, threadId: Long, messageId: Long) {
    if (body.isNullOrBlank()) return
    val match = TalerConfirmationDetector.match(context, body) ?: return

    val record = SignalDatabase.talerPayments.getByUri(match.uri) ?: return
    if (record.threadId != threadId || record.uriKind != match.kind.name || !record.isOwnPayment) return

    SignalDatabase.talerConfirmationMessages.insert(messageId, match.uri)
    AppDependencies.jobManager.add(TalerUriRefreshJob(match.uri, TriggerType.MANUAL))
  }
}
