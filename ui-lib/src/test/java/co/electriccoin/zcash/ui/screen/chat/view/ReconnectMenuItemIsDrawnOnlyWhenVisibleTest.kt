package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ReconnectMenu
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1473 train wiring (OT213n W1): the chat's ⋮ menu draws Reconnect only when ReconnectMenu.visible(conversationMode)
 * (B1a's pure predicate: hidden in Open, where nothing is re-sent). At the train head e626e879d the item is drawn in every
 * mode and a TODO(OT213n-train-merge ZC-1473) stands in for the condition.
 */
class ReconnectMenuItemIsDrawnOnlyWhenVisibleTest {
    private val src by lazy { codeOnly(ChatResourceText.source(CHAT_DETAIL_VIEW)) }

    @Test
    fun the_predicate_hides_reconnect_in_open_only() {
        assertFalse(ReconnectMenu.visible(ConversationMode.OPEN))
        assertTrue(ReconnectMenu.visible(ConversationMode.TUNNEL))
        assertTrue(ReconnectMenu.visible(ConversationMode.VAULT))
    }

    @Test
    fun the_reconnect_item_sits_inside_an_if_on_reconnect_menu_visible() {
        val title = src.indexOf("R.string.chat_menu_reconnect_title")
        assertTrue(title >= 0, "ChatDetailView no longer draws the Reconnect item (chat_menu_reconnect_title)")
        val click = src.indexOf("onReconnect()", title)
        assertTrue(click >= 0, "the Reconnect item no longer calls onReconnect()")
        val guard =
            assertNotNull(
                GUARD.findAll(src.substring(0, title)).lastOrNull(),
                "the Reconnect item is drawn in every mode: no `if (ReconnectMenu.visible(conversationMode)) {` before it",
            )
        // The guard's block is still open at the item's title and at its onReconnect() call.
        var depth = 1
        for (i in guard.range.last + 1 until click) {
            when (src[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            assertTrue(depth > 0, "the `if (ReconnectMenu.visible(conversationMode))` block closes before the Reconnect item")
        }
    }

    @Test
    fun the_merge_todo_is_gone() {
        assertFalse(
            "TODO(OT213n-train-merge ZC-1473)" in ChatResourceText.source(CHAT_DETAIL_VIEW),
            "the TODO(OT213n-train-merge ZC-1473) comment is still in ChatDetailView",
        )
    }

    private companion object {
        const val CHAT_DETAIL_VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"

        val GUARD =
            Regex(
                """if\s*\(\s*(?:co\.electriccoin\.zcash\.ui\.screen\.chat\.model\.)?""" +
                    """ReconnectMenu\.visible\(\s*conversationMode\s*\)\s*\)\s*\{""",
            )

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
