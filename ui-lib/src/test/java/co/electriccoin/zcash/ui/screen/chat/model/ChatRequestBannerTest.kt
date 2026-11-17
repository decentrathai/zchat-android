package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TX-2 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §5.2): the chat-request banner says "in this chat" only when
 * we have written to this address; local-only rows (system notes, /ai prompts, call logs) do not count.
 */
class ChatRequestBannerTest {
    private fun message(
        outgoing: Boolean,
        systemNote: Boolean = false,
        ai: Boolean = false,
        callLog: CallLogInfo? = null,
    ) = ChatMessage(
        id = "m-$outgoing-$systemNote-$ai-${callLog != null}",
        txId = null,
        text = "hi",
        timestamp = Instant.ofEpochSecond(1_759_000_000),
        isOutgoing = outgoing,
        peerAddress = "u1peer",
        isSystemNote = systemNote,
        isAiMessage = ai,
        callLog = callLog,
    )

    @Test
    fun an_outgoing_message_makes_a_known_chat() {
        assertTrue(ChatRequestBanner.isKnownChat(listOf(message(outgoing = false), message(outgoing = true))))
    }

    @Test
    fun only_incoming_messages_are_not_a_known_chat() {
        assertFalse(ChatRequestBanner.isKnownChat(listOf(message(outgoing = false), message(outgoing = false))))
    }

    @Test
    fun only_outgoing_system_notes_are_not_a_known_chat() {
        assertFalse(ChatRequestBanner.isKnownChat(listOf(message(outgoing = true, systemNote = true), message(outgoing = false))))
    }

    @Test
    fun local_only_ai_and_call_log_rows_are_not_a_known_chat() {
        val call = CallLogInfo(type = CallLogType.OUTGOING, isVideo = false, durationSec = 12)
        assertFalse(ChatRequestBanner.isKnownChat(listOf(message(outgoing = true, ai = true), message(outgoing = true, callLog = call))))
    }

    @Test
    fun an_empty_chat_is_not_a_known_chat() {
        assertFalse(ChatRequestBanner.isKnownChat(emptyList()))
    }
}
