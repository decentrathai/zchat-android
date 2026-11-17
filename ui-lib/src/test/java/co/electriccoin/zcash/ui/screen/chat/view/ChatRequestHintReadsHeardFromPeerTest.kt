package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.UpgradeReminders
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1465 train wiring (OT213n W2): the Open chat-request hint in ChatDetailView passes
 * heardFromPeer = ChatViewModel.wasHeardFromOverRelays(peerAddress), read off the main composition path (inside a
 * remember / LaunchedEffect / produceState, re-read when the messages change). At the train head e626e879d the call
 * passes only (conversationMode, conversation.messages), so heardFromPeer is always its default false and the hint
 * still claims a request to a contact who already wrote back over the relays.
 */
class ChatRequestHintReadsHeardFromPeerTest {
    private val chatDetail by lazy { codeOnly(ChatResourceText.source(CHAT_DETAIL_VIEW)) }
    private val screens by lazy { listOf(ANDROID_CHAT, CHAT_DETAIL_VIEW).associateWith { codeOnly(ChatResourceText.source(it)) } }

    @Test
    fun the_predicate_hides_the_hint_once_the_peer_was_heard_from() {
        assertTrue(UpgradeReminders.showsChatRequestHint(ConversationMode.OPEN, emptyList(), heardFromPeer = false))
        assertFalse(UpgradeReminders.showsChatRequestHint(ConversationMode.OPEN, emptyList(), heardFromPeer = true))
    }

    @Test
    fun the_hint_call_passes_a_heard_from_peer_flag() {
        val at = chatDetail.indexOf("UpgradeReminders.showsChatRequestHint(")
        assertTrue(at >= 0, "ChatDetailView no longer calls UpgradeReminders.showsChatRequestHint")
        val args = argumentsAt(chatDetail, at + "UpgradeReminders.showsChatRequestHint".length)
        val parts = splitTopLevel(args)
        assertTrue(
            parts.size >= 3,
            "showsChatRequestHint is called with (${parts.joinToString()}): heardFromPeer is left at its default false",
        )
        val flag = parts[2].substringAfter("heardFromPeer =").trim()
        assertFalse(flag == "false", "showsChatRequestHint is called with heardFromPeer = false")
    }

    @Test
    fun was_heard_from_over_relays_is_read_off_the_composition_path() {
        val hits =
            screens.flatMap { (path, src) ->
                Regex("""wasHeardFromOverRelays\(""").findAll(src).map { path to it.range.first }.toList()
            }
        val (path, at) =
            assertNotNull(
                hits.firstOrNull(),
                "neither AndroidChat nor ChatDetailView calls ChatViewModel.wasHeardFromOverRelays",
            )
        val before = screens.getValue(path).substring(maxOf(0, at - WINDOW), at)
        assertTrue(
            HOLDERS.any { it in before },
            "$path reads wasHeardFromOverRelays outside a remember / LaunchedEffect / produceState (a store read on every recomposition)",
        )
    }

    private companion object {
        const val CHAT_DETAIL_VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
        const val ANDROID_CHAT = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"
        const val WINDOW = 400
        val HOLDERS = listOf("remember(", "remember {", "LaunchedEffect(", "produceState(")

        /** The text between the `(` at or after [from] and its matching `)`. */
        fun argumentsAt(
            src: String,
            from: Int,
        ): String {
            val open = src.indexOf('(', from)
            var depth = 0
            for (i in open until src.length) {
                when (src[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return src.substring(open + 1, i)
                }
            }
            error("unbalanced call at $from")
        }

        /** [args] split at commas outside any bracket. */
        fun splitTopLevel(args: String): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            var start = 0
            for ((i, c) in args.withIndex()) {
                when (c) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                    ',' ->
                        if (depth == 0) {
                            out += args.substring(start, i).trim()
                            start = i + 1
                        }
                }
            }
            val last = args.substring(start).trim()
            if (last.isNotEmpty()) out += last
            return out
        }

        // The source without whole-line comments: lines starting with a line-comment, a block-comment opener or a
        // KDoc star. Line-based on purpose: a regex over block comments would also eat code after a MIME literal
        // such as "image/" + "*" in AndroidChat.
        fun codeOnly(text: String): String =
            text
                .lines()
                .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("/*") || it.startsWith("*") } }
                .joinToString("\n")
    }
}
