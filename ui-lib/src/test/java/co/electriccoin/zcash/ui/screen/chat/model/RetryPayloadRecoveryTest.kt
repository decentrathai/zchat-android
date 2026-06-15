package co.electriccoin.zcash.ui.screen.chat.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [ChatMessage.recoverRawSendPayload] — the pure raw-payload recovery used by
 * ChatViewModel.retryMessage (Bug 8b retry affordance). A null result means "not retryable".
 */
class RetryPayloadRecoveryTest {

    private fun msg(
        text: String,
        fileHash: String? = null,
        fileZfileContent: String? = null,
        timeLock: TimeLockInfo? = null,
        paymentRequest: PaymentRequestInfo? = null,
    ) = ChatMessage(
        id = "m1",
        txId = null,
        text = text,
        timestamp = Instant.EPOCH,
        isOutgoing = true,
        peerAddress = "ztest",
        status = MessageStatus.FAILED,
        timeLock = timeLock,
        paymentRequest = paymentRequest,
        fileHash = fileHash,
        fileZfileContent = fileZfileContent,
    )

    @Test
    fun `plain text message recovers its own text`() {
        assertEquals("hello world", msg("hello world").recoverRawSendPayload())
    }

    @Test
    fun `file message recovers the serialized ZFILE memo not the placeholder`() {
        val zfile = "ZFILE|abc123|j|140000|0|0|0||"
        val m = msg(text = "📎 Image · 140 KB", fileHash = "abc123", fileZfileContent = zfile)
        assertEquals(zfile, m.recoverRawSendPayload())
    }

    @Test
    fun `file bubble missing its serialized memo is not retryable`() {
        val m = msg(text = "📎 Image · 140 KB", fileHash = "abc123", fileZfileContent = null)
        assertNull(m.recoverRawSendPayload())
    }

    @Test
    fun `ZBOOT placeholder is not retryable`() {
        assertNull(msg("🔐 Secure connection request sent").recoverRawSendPayload())
    }

    @Test
    fun `locked message is not retryable`() {
        val m = msg(
            text = "secret",
            timeLock = TimeLockInfo(lockType = TimeLockType.SCHEDULED, unlockTimestamp = Long.MAX_VALUE),
        )
        assertNull(m.recoverRawSendPayload())
    }

    @Test
    fun `payment request is not retryable`() {
        val m = msg(text = "pay me", paymentRequest = PaymentRequestInfo(amountZatoshi = 1000, reason = "lunch"))
        assertNull(m.recoverRawSendPayload())
    }

    @Test
    fun `blank text is not retryable`() {
        assertNull(msg("   ").recoverRawSendPayload())
    }
}
