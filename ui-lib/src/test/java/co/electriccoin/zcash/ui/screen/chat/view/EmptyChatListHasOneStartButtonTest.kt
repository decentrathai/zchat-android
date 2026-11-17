package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OF-1 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §6, owner-reported on the Fold, same code in 2.12.4): the chat
 * list's Scaffold always draws the centred "New Chat" ExtendedFloatingActionButton, and Material3 does not subtract
 * it from the content padding. The empty state (`EmptyConversationsView`) is a centred, non-scrolling column whose
 * last item is its own "Start a Chat" button; on the Fold's inner screen, with the sync and requests banners taking
 * height (and larger display or font sizes), that button falls under the FAB. The lists end 8 dp below their last
 * row, so the last row can also sit under the FAB.
 *
 * What it must be: the FAB is drawn only when `ChatListLayout.showNewChatFab(state)` holds (not on the empty list,
 * whose "Start a Chat" is the one call to action); the empty state scrolls when it is cut and stays centred when it
 * fits; both lists leave `ChatListLayout.FAB_CLEARANCE` below the last row. JVM unit tests here have no Compose
 * layout, so this pins the structure; the bounds are phone checks (O1-O3).
 */
class EmptyChatListHasOneStartButtonTest {
    private val lexed =
        KotlinUiLiterals.lex(
            ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt")
        )

    private fun code(anchor: String): String {
        val r = KotlinUiLiterals.block(lexed, anchor)
        return lexed.blanked.substring(r.first, r.last + 1)
    }

    @Test
    fun the_new_chat_fab_is_drawn_only_when_the_layout_rule_allows_it() {
        assertEquals(
            1,
            Regex("""\bExtendedFloatingActionButton\s*\(""").findAll(lexed.blanked).count(),
            "one New Chat FAB"
        )
        assertTrue(
            Regex(
                """^floatingActionButton\s*=\s*\{\s*if\s*\(\s*(?:[\w.]+\.)?ChatListLayout\.showNewChatFab\(\s*state\s*\)\s*\)\s*\{\s*""" +
                    """ExtendedFloatingActionButton\s*\("""
            ).containsMatchIn(code("floatingActionButton = {")),
            "the FAB is always drawn: on the empty list it covers \"Start a Chat\"; wrap it in ChatListLayout.showNewChatFab(state)",
        )
    }

    @Test
    fun the_empty_state_scrolls_when_cut_and_keeps_one_start_button() {
        val empty = code("private fun EmptyConversationsView(")
        assertEquals(
            1,
            Regex("""R\.string\.chat_list_empty_start\b""").findAll(empty).count(),
            "one \"Start a Chat\" button"
        )
        assertTrue(
            Regex("""\.verticalScroll\(\s*rememberScrollState\(\)\s*\)""").containsMatchIn(empty),
            "the empty state does not scroll: when the Fold's banners and a large font cut it, the button is unreachable",
        )
        assertTrue(
            Regex("""\.heightIn\(\s*min\s*=""").containsMatchIn(empty) && "Arrangement.Center" in empty,
            "keep it centred when it fits: a scroll column with heightIn(min = the viewport height) and Arrangement.Center",
        )
    }

    @Test
    fun both_lists_leave_the_fab_clearance_below_the_last_row() {
        for (anchor in listOf("private fun ConversationsList(", "private fun ConversationsAndGroupsList(")) {
            val list = code(anchor)
            assertTrue(
                Regex(
                    """contentPadding\s*=\s*PaddingValues\([^)]*\bbottom\s*=\s*(?:[\w.]+\.)?ChatListLayout\.FAB_CLEARANCE\b"""
                ).containsMatchIn(list),
                "$anchor: the last row sits under the FAB; contentPadding bottom = ChatListLayout.FAB_CLEARANCE",
            )
            assertFalse(
                Regex("""PaddingValues\(\s*vertical\s*=\s*8\.dp\s*\)""").containsMatchIn(list),
                "$anchor: 8 dp at the bottom"
            )
        }
    }
}
