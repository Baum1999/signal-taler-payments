package org.thoughtcrime.securesms.taler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reine Entscheidungslogik fuer die Gruppen-Split-Sammelkarte (ein Karte pro
 * Nachricht statt eine pro URI): welche Rolle habe ich (Ersteller/Empfaenger),
 * welche der N URIs ist ab meiner sortierten Position die naechste noch
 * offene ("mein Anteil" ist ein Startpunkt fuer eine Suche, kein festes
 * Slot-Assignment - ein Mitglied kann bewusst mehrere Anteile beanspruchen,
 * z.B. um jemanden ohne Taler-Wallet bar auszuzahlen), und wie viele der N
 * bereits angenommen wurden.
 */
class GroupSplitCardTest {

  // ---- resolveGroupCardRole ----

  @Test
  fun `sender is the creator regardless of recipient list contents`() {
    val role = resolveGroupCardRole(
      recipientAcis = listOf("aci-b", "aci-c"),
      senderAci = "aci-a",
      myAci = "aci-a",
    )
    assertEquals(GroupCardRole.Creator, role)
  }

  @Test
  fun `member found in recipient list is a recipient at their sorted index`() {
    val role = resolveGroupCardRole(
      recipientAcis = listOf("aci-b", "aci-c", "aci-d"),
      senderAci = "aci-a",
      myAci = "aci-c",
    )
    assertEquals(GroupCardRole.Recipient(myIndex = 1), role)
  }

  @Test
  fun `member not in recipient list and not the sender is not a participant`() {
    val role = resolveGroupCardRole(
      recipientAcis = listOf("aci-b", "aci-c"),
      senderAci = "aci-a",
      myAci = "aci-joined-later",
    )
    assertEquals(GroupCardRole.NotAParticipant, role)
  }

  @Test
  fun `null recipient snapshot means not a participant`() {
    val role = resolveGroupCardRole(
      recipientAcis = null,
      senderAci = "aci-a",
      myAci = "aci-b",
    )
    assertEquals(GroupCardRole.NotAParticipant, role)
  }

  // ---- resolveTargetUri ----

  @Test
  fun `all unknown status returns the uri at the start index`() {
    val target = resolveTargetUri(
      uris = listOf("u0", "u1", "u2"),
      statuses = listOf(null, null, null),
      startIndex = 1,
    )
    assertEquals("u1", target)
  }

  @Test
  fun `skips already accepted uri at start index and returns next open one`() {
    val target = resolveTargetUri(
      uris = listOf("u0", "u1", "u2"),
      statuses = listOf(TalerPaymentStatus.OFFEN, TalerPaymentStatus.ANGENOMMEN, TalerPaymentStatus.OFFEN),
      startIndex = 1,
    )
    assertEquals("u2", target)
  }

  @Test
  fun `wraps around to the beginning of the list when needed`() {
    val target = resolveTargetUri(
      uris = listOf("u0", "u1", "u2"),
      statuses = listOf(TalerPaymentStatus.OFFEN, TalerPaymentStatus.ANGENOMMEN, TalerPaymentStatus.ANGENOMMEN),
      startIndex = 1,
    )
    assertEquals("u0", target)
  }

  @Test
  fun `locally declined uri is treated as unavailable, not re-offered`() {
    val target = resolveTargetUri(
      uris = listOf("u0", "u1"),
      statuses = listOf(TalerPaymentStatus.LOKAL_ABGELEHNT, TalerPaymentStatus.OFFEN),
      startIndex = 0,
    )
    assertEquals("u1", target)
  }

  @Test
  fun `returns null when every uri is already resolved`() {
    val target = resolveTargetUri(
      uris = listOf("u0", "u1"),
      statuses = listOf(TalerPaymentStatus.ANGENOMMEN, TalerPaymentStatus.ANGENOMMEN),
      startIndex = 0,
    )
    assertNull(target)
  }

  @Test
  fun `returns null for an empty uri list`() {
    val target = resolveTargetUri(uris = emptyList(), statuses = emptyList(), startIndex = 0)
    assertNull(target)
  }

  // ---- countAccepted ----

  @Test
  fun `countAccepted counts only ANGENOMMEN entries`() {
    val count = countAccepted(
      listOf(
        TalerPaymentStatus.ANGENOMMEN,
        TalerPaymentStatus.OFFEN,
        null,
        TalerPaymentStatus.ANGENOMMEN,
        TalerPaymentStatus.LOKAL_ABGELEHNT,
      )
    )
    assertEquals(2, count)
  }

  @Test
  fun `countAccepted is zero for an empty list`() {
    assertEquals(0, countAccepted(emptyList()))
  }
}
