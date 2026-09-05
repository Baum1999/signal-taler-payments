package org.thoughtcrime.securesms.taler

import android.content.Context
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * Welche Taler-Menue-Aktionen fuer eine Nachricht gerade zutreffen, inklusive
 * der konkreten URI(s), auf die sie sich beziehen - fuer den Einzel-URI-Pfad
 * via TalerCardActionGate (dieselbe Bedingung wie zuvor auf der Karte), fuer
 * die Gruppen-Sammelkarte via GroupSplitCard.resolveCancelableUris/
 * resolveRefundableUris/resolveTargetUri (dieselbe Logik wie
 * TalerPaymentCardPresenter.bindGroupCard, nur rollenbewusst statt hart
 * verborgen - siehe Plan "Nebeneffekt").
 *
 * [compute] liest den DB-/Claim-Zustand jedes Mal frisch - sowohl beim Aufbau
 * des Long-Press-Menues als auch (erneut) im Klick-Handler
 * (TalerMenuActions), damit ein zwischenzeitlich veraenderter Zustand (Poll
 * abgeschlossen, waehrend das Menue offen war) nicht auf einem veralteten
 * Snapshot ausgefuehrt wird - dasselbe Muster wie
 * TalerAcceptRejectActions.onRejectClicked es fuer die Karte bereits nutzt.
 */
data class TalerMenuTargets(
  val rejectUri: String?,
  val cancelUris: List<String>,
  val refundUris: List<String>,
) {
  val showReject: Boolean get() = rejectUri != null
  val showCancel: Boolean get() = cancelUris.isNotEmpty()
  val showRefresh: Boolean get() = cancelUris.isNotEmpty()
  val showRefund: Boolean get() = refundUris.isNotEmpty()

  companion object {
    val NONE = TalerMenuTargets(rejectUri = null, cancelUris = emptyList(), refundUris = emptyList())
  }
}

object TalerMenuState {

  fun compute(context: Context, messageRecord: MessageRecord): TalerMenuTargets {
    val uris = urisFromMessageBody(messageRecord.body)
    if (uris.isEmpty()) return TalerMenuTargets.NONE

    return if (uris.size == 1) {
      val uri = uris.single()
      val record = SignalDatabase.talerPayments.getByUri(uri)
      TalerMenuTargets(
        rejectUri = if (TalerCardActionGate.showReject(record)) uri else null,
        cancelUris = if (TalerCardActionGate.showCancel(record)) listOf(uri) else emptyList(),
        refundUris = if (TalerCardActionGate.showRefund(record)) listOf(uri) else emptyList(),
      )
    } else {
      computeGroup(context, messageRecord, uris)
    }
  }

  private fun computeGroup(context: Context, messageRecord: MessageRecord, uris: List<String>): TalerMenuTargets {
    val records = uris.map { SignalDatabase.talerPayments.getByUri(it) }
    val statuses = records.map { it?.status }

    val myAci = SignalStore.account.requireAci().toString()
    val sender = messageRecord.fromRecipient
    val role = if (sender.aci.isPresent) {
      // Lokal aus der Gruppenmitgliedschaft hergeleitet statt aus der
      // Nachricht gelesen (GroupSplitRecipients.kt) - kein Nachrichtenformat
      // (weder das alte JSON-Feld recipientAcis noch TalerPaymentPayload)
      // muss diese Liste transportieren.
      val senderAci = sender.aci.get().toString()
      val groupRecipient = SignalDatabase.threads.getRecipientForThreadId(messageRecord.threadId)
      val recipientAcis = groupRecipient?.let { groupSplitRecipientAcis(it, senderAci) }
      resolveGroupCardRole(recipientAcis, senderAci, myAci)
    } else {
      GroupCardRole.NotAParticipant
    }

    val claimTracker = GroupClaimTracker(context)
    val alreadyClaimed = claimTracker.hasClaimedAny(uris)
    val rejectUri = if (role is GroupCardRole.Recipient && !alreadyClaimed) {
      resolveTargetUri(uris, statuses, role.myIndex)
    } else {
      null
    }

    return TalerMenuTargets(
      rejectUri = rejectUri,
      cancelUris = resolveCancelableUris(uris, records, role),
      refundUris = resolveRefundableUris(uris, records, claimTracker.claimedOf(uris)),
    )
  }
}
