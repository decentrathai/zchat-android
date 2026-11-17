package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Phone matrix 1 Nov.
 * - ZC-1354: a Sending row waiting for a secure connection has no transaction, and its Delete dialog said one stays on the chain.
 * - ZC-1356: the bubble says "Payment received" with its amount; the chat list still read "Payment from ZCHAT".
 */
class DeleteAndPreviewSayWhatTheRowIsTest {
    private fun row(
        id: String,
        text: String = "synthetic row",
        status: MessageStatus = MessageStatus.SENDING,
        isOutgoing: Boolean = true,
        isPending: Boolean = true,
    ) = ChatMessage(
        id = id,
        txId = null,
        text = text,
        timestamp = Instant.EPOCH,
        isOutgoing = isOutgoing,
        peerAddress = "usyntheticzc1354",
        isPending = isPending,
        status = status,
    )

    @Test
    fun `deleting a row waiting for a secure connection says it was not sent`() {
        assertEquals(
            "the dialog of a row with no transaction says its transaction stays on the blockchain",
            R.string.chat_delete_message_body_not_sent,
            ChatSendCopy.deleteConfirmBodyFor(row("tunnel-wait-1354")),
        )
    }

    @Test
    fun `control - a payment with a known amount has a bubble line, and with none it keeps its text`() {
        val paid = row("nmsg-1356", text = "Payment from ZCHAT", status = MessageStatus.SENT, isOutgoing = false, isPending = false)

        assertNotNull(PaymentBubble.of(paid, AMOUNT))
        assertNull(PaymentBubble.of(paid, null))
    }

    /** By source, as the view tests here: the preview is drawn inside a composable that no unit test can run. */
    @Test
    fun `the chat list preview says what the bubble says`() {
        val view = File(LIST_VIEW).readText()
        val preview = view.substring(view.indexOf("// Message preview"), view.indexOf("// Peer status"))

        assertTrue(
            "the chat list preview draws the row's raw text instead of the payment bubble's line and amount",
            "PaymentBubble.of(" in preview,
        )
    }

    private companion object {
        const val AMOUNT = 16_000L
        const val LIST_VIEW = "src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt"
    }
}
