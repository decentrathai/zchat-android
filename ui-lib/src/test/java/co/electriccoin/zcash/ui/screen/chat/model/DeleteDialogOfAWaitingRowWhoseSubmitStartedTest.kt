package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

/**
 * ZC-1944 (Fable review of fix/2130-msg 4efd9a54f, 16 Nov 2026; register high; money-path rule 4) — a first message whose
 * key exchange went out waits for its ZEC in the live note wait (ZC-1909): its bubble carries waitsForFunds. When the
 * change confirms, the same run builds the message again and its submit starts (the row now holds a submit time, and the
 * SDK proves and broadcasts it), while the bubble still carries waitsForFunds. The "Delete message?" dialog then said
 * chat_delete_message_body_not_sent ("It was not sent, so no Zcash transaction was made for it"), and the message was paid.
 *
 * Wanted: a waiting row says "not sent" only while no submit of it started and no transaction id is stored; once one did,
 * the dialog keeps the wording of a row that may be on the chain. All ids are synthetic.
 */
class DeleteDialogOfAWaitingRowWhoseSubmitStartedTest {
    private fun waitingRow(
        submitStartedAtMillis: Long? = null,
        submittedTxIds: List<String> = emptyList(),
    ) = ChatMessage(
        id = "pending_zc1944",
        txId = null,
        text = "synthetic first words",
        timestamp = Instant.ofEpochSecond(1_700_000_000L),
        isOutgoing = true,
        peerAddress = "usyntheticzc1944deletebody0001",
        isPending = true,
        status = MessageStatus.SENDING,
        waitsForFunds = true,
        submitStartedAtMillis = submitStartedAtMillis,
        submittedTxIds = submittedTxIds,
    )

    @Test
    fun `a waiting row whose submit started is not told it was not sent`() {
        assertNotEquals(
            "a row being proved and broadcast is told no transaction was made",
            R.string.chat_delete_message_body_not_sent,
            ChatSendCopy.deleteConfirmBodyFor(waitingRow(submitStartedAtMillis = 1_700_000_000_123L)),
        )
    }

    @Test
    fun `a waiting row with a stored transaction id is not told it was not sent`() {
        assertNotEquals(
            "a row with a stored transaction id is told no transaction was made",
            R.string.chat_delete_message_body_not_sent,
            ChatSendCopy.deleteConfirmBodyFor(waitingRow(submittedTxIds = listOf("synthetic-zc1944-txid"))),
        )
    }

    @Test
    fun `control - a waiting row no submit of which started is not sent (ZC-1344)`() {
        assertEquals(R.string.chat_delete_message_body_not_sent, ChatSendCopy.deleteConfirmBodyFor(waitingRow()))
    }
}
