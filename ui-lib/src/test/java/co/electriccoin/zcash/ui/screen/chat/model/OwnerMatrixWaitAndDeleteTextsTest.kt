package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Phone matrix 1 Nov. Pure rules behind what the rows said.
 * - ZC-1341: "9 of 10" with the 60,000 zat deposit at 6 confirmations. soonestPendingReceipt takes the receipt with the
 *   fewest blocks left, and the phone also held the 1,000 zat a setup memo delivered about 3 blocks earlier. That note can
 *   never pay a 17,000 zat step, so its count is not the wait. Heights are the real ones of tx 1358670e and the tip of that
 *   minute; the older receipt's height is inferred from the 9.
 * - ZC-1344: the delete dialog of a row still waiting for funds said its transaction stays on the blockchain; it has none.
 *   After that Delete a new chat showed only "Conversation not found", with no way back.
 * - ZC-1339: the banner says "not sent using ZCHAT" whatever [UnknownReason] it is given, also for a recognized ZMSG.
 */
class OwnerMatrixWaitAndDeleteTextsTest {
    private companion object {
        const val TIP = 3_537_531L
        const val DEPOSIT_MINED = 3_537_526L
        const val DUST_MINED = 3_537_523L
        const val VIEW = "src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
    }

    private fun incoming(
        minedHeight: Long,
        zat: Long,
    ) = pendingReceiptFrom(
        minedHeight = minedHeight,
        isSentTransaction = false,
        isChange = false,
        isShielding = false,
        isTrusted = false,
        receivedNoteCount = 1,
        totalReceivedZat = zat,
        landsInIronwood = true,
    )

    @Test
    fun `the funds wait counts the deposit, not a memo's dust note`() {
        val receipts = listOfNotNull(incoming(DUST_MINED, 1_000L), incoming(DEPOSIT_MINED, 60_000L))

        assertEquals(
            "the row counts the dust note a setup memo delivered",
            6,
            soonestConfirmationProgress(TIP, receipts)?.confirmations,
        )
    }

    @Test
    fun `control - a lone deposit counts its own confirmations`() {
        assertEquals(6, soonestConfirmationProgress(TIP, listOfNotNull(incoming(DEPOSIT_MINED, 60_000L)))?.confirmations)
    }

    @Test
    fun `deleting a row that waits for funds does not speak of a transaction`() {
        val waiting =
            ChatMessage(
                id = "pending_synthetic_1344",
                txId = null,
                text = "synthetic waiting row",
                timestamp = Instant.EPOCH,
                isOutgoing = true,
                peerAddress = "usyntheticzc1344",
                isPending = true,
                status = MessageStatus.SENDING,
                waitsForFunds = true,
            )

        assertNotEquals(
            "the dialog says the transaction of a row with no transaction stays on the blockchain",
            R.string.chat_delete_message_body,
            ChatSendCopy.deleteConfirmBodyFor(waiting),
        )
    }

    private fun viewBlock(
        start: String,
        lines: Int,
    ): String {
        val source = File(VIEW).readLines()
        val at = source.indexOfFirst { start in it }
        assertTrue("fixture: $start is not in the view", at >= 0)
        return source.drop(at).take(lines).joinToString("\n")
    }

    @Test
    fun `the conversation-not-found screen has a way back`() {
        val errorBranch = viewBlock("is ChatDetailState.Error ->", 11)
        assertTrue("the error screen is a dead end, only text: $errorBranch", "onBack" in errorBranch)
    }

    @Test
    fun `the unknown sender banner uses the reason it is given`() {
        val body = viewBlock("private fun UnknownSenderBanner(", 32).substringAfter("modifier: Modifier = Modifier")
        assertTrue("every reason reads 'not sent using ZCHAT', a recognized ZMSG too", "reason" in body)
    }
}
