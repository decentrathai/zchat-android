package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1464 train wiring (OT213n W3). B1a landed the store and ChatViewModel's declinedMessageRequests(),
 * restoreDeclinedRequest(request) and isRequestDeclinedBy(peerAddress); at the train head e626e879d no screen calls them.
 *
 * (a) Receiver side: a "Declined requests" list reachable from the message-requests screen (where Decline lives), its
 *     rows offering Restore (restoreDeclinedRequest); fed by declinedMessageRequests(); an empty list offers no entry.
 * (b) Sender side: while isRequestDeclinedBy(peerAddress) is true the composer is disabled and shows the note
 *     "Request declined" (owner W-122 answer 4: these exact words), a string resource in English and Spanish.
 */
class DeclinedRequestsAreWiredIntoTheScreensTest {
    private val root: File get() = ChatResourceText.repoRoot

    /** Every Kotlin file of the chat screens except the view model (which defines the calls). */
    private val screens: Map<String, String> by lazy {
        File(root, CHAT_PKG)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/viewmodel/" !in it.path }
            .associate { it.relativeTo(root).path to codeOnly(it.readText()) }
    }

    private fun callers(call: String): List<String> = screens.filterValues { call in it }.keys.sorted()

    // ---- (a) the Declined requests list ----

    @Test
    fun a_screen_lists_the_declined_requests() {
        assertTrue(
            callers("declinedMessageRequests()").isNotEmpty(),
            "no chat screen calls ChatViewModel.declinedMessageRequests(): the Declined requests list does not exist",
        )
    }

    @Test
    fun a_declined_row_offers_restore() {
        assertTrue(
            callers("restoreDeclinedRequest(").isNotEmpty(),
            "no chat screen calls ChatViewModel.restoreDeclinedRequest: a declined request cannot be restored",
        )
    }

    @Test
    fun the_declined_list_entry_is_offered_only_when_the_list_is_not_empty() {
        val readers = callers("declinedMessageRequests()")
        assertTrue(readers.isNotEmpty(), "no chat screen reads declinedMessageRequests(), so none can hide an empty list's entry")
        val unguarded = readers.filterNot { EMPTY_GUARD.containsMatchIn(screens.getValue(it)) }
        assertTrue(
            unguarded.isEmpty(),
            "$unguarded read the declined list but never check it for emptiness " +
                "(`<declined…>.isNotEmpty()` / `.isEmpty()`): an empty list would still show its entry",
        )
    }

    // ---- (b) the sender's composer ----

    @Test
    fun request_declined_is_a_string_in_english_and_spanish() {
        val key = declinedKey()
        val es = assertNotNull(ChatResourceText.strings("values-es")[key], "values-es has no $key")
        assertNotEquals(NOTE, es, "$key is English in the Spanish file")
    }

    @Test
    fun a_screen_asks_whether_the_contact_declined_our_request() {
        assertTrue(
            callers("isRequestDeclinedBy(").isNotEmpty(),
            "no chat screen calls ChatViewModel.isRequestDeclinedBy: the sender's composer never learns of the decline",
        )
    }

    @Test
    fun the_composer_is_disabled_with_the_request_declined_note() {
        val key = declinedKey()
        val src = assertNotNull(screens[CHAT_DETAIL_VIEW], "ChatDetailView.kt not found")
        val at = src.indexOf("MessageInput(")
        assertTrue(at >= 0, "ChatDetailView no longer calls MessageInput")
        val args = argumentsAt(src, at)
        val enabled = argument(args, "isEnabled")
        assertTrue(
            DECLINED_WORD.containsMatchIn(enabled),
            "the composer's isEnabled = `$enabled` ignores a declined request",
        )
        val note = argument(args, "disabledMessage")
        assertTrue(
            "R.string.$key" in note,
            "the composer's disabledMessage = `$note` never shows R.string.$key (\"$NOTE\")",
        )
    }

    /** The one string resource whose English text is exactly "Request declined". */
    private fun declinedKey(): String {
        val keys = ChatResourceText.strings("values").filterValues { it == NOTE }.keys
        assertEquals(1, keys.size, "string resources whose English text is exactly \"$NOTE\": $keys")
        return keys.single()
    }

    private companion object {
        const val CHAT_PKG = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"
        const val CHAT_DETAIL_VIEW = "$CHAT_PKG/view/ChatDetailView.kt"
        const val NOTE = "Request declined"

        val DECLINED_WORD = Regex("""[Dd]eclined""")
        val EMPTY_GUARD = Regex("""[Dd]eclined\w*\s*(?:\.\s*value\s*)?\.\s*(?:isNotEmpty|isEmpty)\(\)""")

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

        /** The expression of the named argument [name] in [args] (top-level commas end it), or "" when absent. */
        fun argument(
            args: String,
            name: String,
        ): String {
            val m = Regex("""(?<![\w.])$name\s*=""").find(args) ?: return ""
            var depth = 0
            for (i in m.range.last + 1 until args.length) {
                when (args[i]) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                    ',' -> if (depth == 0) return args.substring(m.range.last + 1, i).trim()
                }
            }
            return args.substring(m.range.last + 1).trim()
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
