/*
 * PaymentHistoryManagerTest.kt - Unit Tests für PaymentHistoryManager
 * 
 * Dies ist die Implementierung der Zahlungshistorie für Signal-Taler-Integration.
 */

package org.thoughtcrime.securesms.payments.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.recipients.RecipientId
import java.util.Date

class PaymentHistoryManagerTest {

    private lateinit var samplePaymentItem: PaymentHistoryItem
    private lateinit var sampleSender: PaymentParty
    private lateinit var sampleRecipient: PaymentParty

    @Before
    fun setUp() {
        sampleSender = PaymentParty(
            id = RecipientId.from("sender_123"),
            name = "Max Mustermann"
        )
        
        sampleRecipient = PaymentParty(
            id = RecipientId.from("recipient_456"),
            name = "Erika Mustermann"
        )
        
        samplePaymentItem = PaymentHistoryItem(
            id = "1",
            amount = "100.00",
            currency = "EUR",
            direction = PaymentDirection.OUTFLOW,
            sender = sampleSender,
            recipient = sampleRecipient,
            status = PaymentStatus.COMPLETED,
            timestamp = Date(),
            chatId = 123L,
            talerPaymentId = "taler:payment:123",
            refundUri = "taler:refund:123",
            summary = "Testzahlung"
        )
    }

    @Test
    fun testPaymentHistoryItemCreation() {
        assertNotNull(samplePaymentItem)
        assertEquals("1", samplePaymentItem.id)
        assertEquals("100.00", samplePaymentItem.amount)
        assertEquals("EUR", samplePaymentItem.currency)
        assertEquals(PaymentDirection.OUTFLOW, samplePaymentItem.direction)
        assertEquals(sampleSender, samplePaymentItem.sender)
        assertEquals(sampleRecipient, samplePaymentItem.recipient)
        assertEquals(PaymentStatus.COMPLETED, samplePaymentItem.status)
    }

    @Test
    fun testIsOwnPaymentOutflow() {
        val outflowItem = samplePaymentItem.copy(direction = PaymentDirection.OUTFLOW)
        assertTrue(outflowItem.isOwnPayment)
    }

    @Test
    fun testIsOwnPaymentInflow() {
        val inflowItem = samplePaymentItem.copy(direction = PaymentDirection.INFLOW)
        assertTrue(!inflowItem.isOwnPayment)
    }

    @Test
    fun testIsRefundable() {
        val completedOutflowItem = samplePaymentItem.copy(
            status = PaymentStatus.COMPLETED,
            direction = PaymentDirection.OUTFLOW,
            refundUri = "taler:refund:123"
        )
        assertTrue(completedOutflowItem.isRefundable)
    }

    @Test
    fun testIsNotRefundableWhenInflow() {
        val completedInflowItem = samplePaymentItem.copy(
            status = PaymentStatus.COMPLETED,
            direction = PaymentDirection.INFLOW,
            refundUri = "taler:refund:123"
        )
        assertTrue(!completedInflowItem.isRefundable)
    }

    @Test
    fun testIsNotRefundableWhenNoRefundUri() {
        val completedOutflowItem = samplePaymentItem.copy(
            status = PaymentStatus.COMPLETED,
            direction = PaymentDirection.OUTFLOW,
            refundUri = null
        )
        assertTrue(!completedOutflowItem.isRefundable)
    }

    @Test
    fun testIsNotRefundableWhenNotCompleted() {
        val pendingOutflowItem = samplePaymentItem.copy(
            status = PaymentStatus.PENDING,
            direction = PaymentDirection.OUTFLOW,
            refundUri = "taler:refund:123"
        )
        assertTrue(!pendingOutflowItem.isRefundable)
    }

    @Test
    fun testPaymentDirectionValues() {
        assertEquals(2, PaymentDirection.entries.size)
        assertTrue(PaymentDirection.entries.contains(PaymentDirection.INFLOW))
        assertTrue(PaymentDirection.entries.contains(PaymentDirection.OUTFLOW))
    }

    @Test
    fun testPaymentStatusValues() {
        assertTrue(PaymentStatus.entries.size > 5)
        assertTrue(PaymentStatus.entries.contains(PaymentStatus.PENDING))
        assertTrue(PaymentStatus.entries.contains(PaymentStatus.COMPLETED))
        assertTrue(PaymentStatus.entries.contains(PaymentStatus.REFUNDED))
        assertTrue(PaymentStatus.entries.contains(PaymentStatus.FAILED))
    }

    @Test
    fun testTimeRangeValues() {
        assertEquals(5, TimeRange.entries.size)
        assertTrue(TimeRange.entries.contains(TimeRange.ALL))
        assertTrue(TimeRange.entries.contains(TimeRange.LAST_7_DAYS))
        assertTrue(TimeRange.entries.contains(TimeRange.LAST_30_DAYS))
        assertTrue(TimeRange.entries.contains(TimeRange.LAST_90_DAYS))
        assertTrue(TimeRange.entries.contains(TimeRange.CUSTOM))
    }

    @Test
    fun testPaymentHistoryFilterDefaultValues() {
        val filter = PaymentHistoryFilter()
        assertEquals(TimeRange.ALL, filter.timeRange)
        assertEquals(null, filter.direction)
        assertEquals(null, filter.status)
        assertEquals(null, filter.chatId)
    }

    @Test
    fun testPaymentPartyCreation() {
        val party = PaymentParty(
            id = RecipientId.from("test_id"),
            name = "Test Name"
        )
        assertEquals(RecipientId.from("test_id"), party.id)
        assertEquals("Test Name", party.name)
    }
}
