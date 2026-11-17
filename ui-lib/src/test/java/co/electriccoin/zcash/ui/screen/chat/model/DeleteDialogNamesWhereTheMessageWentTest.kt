package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * Round 8 D8 (REVIEW_7C smaller issue 2, the delete dialog) — "Delete message?" says the message's transaction stays
 * on the Zcash blockchain for rows that have none, or may have none.
 *
 * ChatSendCopy.deleteConfirmBodyFor has two bodies: "not sent" for a failed row that never reached the submit, and
 * the blockchain body ("…The transaction remains on the Zcash blockchain and will reappear if you restore from
 * seed.") for every other row. Two kinds of row are not on the chain:
 *
 * - a Tunnel/Open message sent or received over the free relays ("nmsg-<rumor id>", or "nostr-<event id>" from a
 *   peer that sends no rumor id, "nostr-out-<n>" before the relay answered): no Zcash transaction exists for it,
 *   and restoring from the recovery phrase does not bring it back;
 * - a failed row whose submit started (a submit time, or stored transaction ids) with no confirmed transaction: it
 *   may have been sent, and it may not.
 *
 * Wanted: chat_delete_message_body_relay and chat_delete_message_body_maybe_sent for those, the old bodies for the
 * rest (control). All ids and addresses are synthetic.
 */
class DeleteDialogNamesWhereTheMessageWentTest {
    private fun row(
        id: String,
        isOutgoing: Boolean = true,
        status: MessageStatus = MessageStatus.SENT,
    ) = ChatMessage(
        id = id,
        txId = null,
        text = "synthetic",
        timestamp = Instant.EPOCH,
        isOutgoing = isOutgoing,
        peerAddress = PEER,
        status = status,
    )

    private fun bodyName(message: ChatMessage): String? = ChatResourceText.stringName(ChatSendCopy.deleteConfirmBodyFor(message))

    @Test
    fun `a message that went over the free relays is not said to stay on the blockchain`() {
        for ((label, message) in listOf(
            "incoming, rumor id" to row("nmsg-synthetic-rumor", isOutgoing = false),
            "incoming, legacy event id" to row("nostr-synthetic-event", isOutgoing = false),
            "sent, rumor id" to row("nmsg-synthetic-rumor-2"),
            "sent, before the rumor id came back" to row("nostr-out-12345"),
        )) {
            assertEquals("chat_delete_message_body_relay", bodyName(message), label)
        }
    }

    @Test
    fun `a failed row whose submit started is told it may have been sent`() {
        val failed = row("pending_synthetic", status = MessageStatus.FAILED)
        for ((label, message) in listOf(
            "submit time" to failed.copy(submitStartedAtMillis = 1L),
            "stored transaction ids" to failed.copy(submittedTxIds = listOf("synthetic-T")),
            "both" to failed.copy(submitStartedAtMillis = 1L, submittedTxIds = listOf("synthetic-T")),
        )) {
            assertEquals("chat_delete_message_body_maybe_sent", bodyName(message), label)
        }
    }

    @Test
    fun `control - the other rows keep their bodies`() {
        assertEquals(
            "chat_delete_message_body_not_sent",
            bodyName(row("pending_synthetic", status = MessageStatus.FAILED)),
            "a failed row that never reached the submit",
        )
        assertEquals(
            "chat_delete_message_body_not_sent",
            bodyName(row("nostr-out-12345", status = MessageStatus.FAILED)),
            "a relay message no relay accepted was not sent",
        )
        for ((label, message) in listOf(
            "an on-chain message" to row("pending_synthetic"),
            "an on-chain message still sending" to row("pending_synthetic", status = MessageStatus.SENDING),
            "an incoming on-chain message" to row("synthetic-txid-row", isOutgoing = false),
        )) {
            assertEquals("chat_delete_message_body", bodyName(message), label)
        }
    }

    private companion object {
        const val PEER = "usyntheticd8deletedialog0001"
    }
}
