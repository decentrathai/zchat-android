package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-1140 (high): Chats -> Wallet tab -> Chats builds a new ChatViewModel. Its first frame is the kept pass, which masks
 * groups on purpose (CachedSnapshotMask; ChatOpensFromTheLastPassTest), so the list starts at the first private chat.
 * The live pass then inserts the Groups row (unfolded: the groups and the "Chats" header) ABOVE that chat, and the keyed
 * LazyColumn in `ConversationsAndGroupsList` keeps its first visible item by key: the Groups row stays scrolled out of
 * view until a cold start.
 *
 * Wanted (orchestrator decision, view fix): when the list was at the top (first visible index 0, offset 0) and new rows
 * appear above the row that was first, the list scrolls back to the top. The rule is the pure
 * `ChatListLayout.shouldScrollToTop(previousFirstKey: Any?, wasAtTop: Boolean, newRowKeys: List<Any>): Boolean`
 * (true only when wasAtTop, previousFirstKey != null and newRowKeys.indexOf(previousFirstKey) > 0), and
 * `ConversationsAndGroupsList` calls it and scrolls its LazyListState to item 0.
 *
 * Read by reflection so this RED test compiles before the API exists. JVM unit tests have no Compose layout, so the
 * wiring is pinned at source level; the scroll itself is a phone check.
 */
class GroupsRowStaysInViewAfterTheWalletTabTest {
    // The keys ConversationsAndGroupsList gives its rows: Int for the Groups row and the "Chats" header, String otherwise.
    private val groupsFolder: Any = 1
    private val chatsHeader: Any = 2

    private fun shouldScrollToTop(
        previousFirstKey: Any?,
        wasAtTop: Boolean,
        newRowKeys: List<Any>
    ): Boolean {
        val cls = Class.forName("co.electriccoin.zcash.ui.screen.chat.model.ChatListLayout")
        val method =
            try {
                cls.getMethod("shouldScrollToTop", Any::class.java, Boolean::class.javaPrimitiveType, List::class.java)
            } catch (e: NoSuchMethodException) {
                throw AssertionError(
                    "ChatListLayout.shouldScrollToTop(previousFirstKey: Any?, wasAtTop: Boolean, newRowKeys: List<Any>)" +
                        " does not exist: nothing scrolls the list back to the Groups row inserted above the first chat",
                    e
                )
            }
        return method.invoke(cls.getField("INSTANCE").get(null), previousFirstKey, wasAtTop, newRowKeys) as Boolean
    }

    @Test
    fun folded_groups_row_inserted_above_the_first_chat_while_at_the_top_scrolls_to_the_top() {
        assertTrue(
            shouldScrollToTop("peerA", wasAtTop = true, newRowKeys = listOf(groupsFolder, "peerA", "peerB")),
            "the Groups row appeared above the first chat while the list was at the top, but the list does not scroll to it"
        )
    }

    @Test
    fun unfolded_groups_and_chats_header_inserted_above_while_at_the_top_scrolls_to_the_top() {
        assertTrue(
            shouldScrollToTop(
                "peerA",
                wasAtTop = true,
                newRowKeys = listOf(groupsFolder, "group_g1", "group_g2", chatsHeader, "peerA", "peerB")
            ),
            "the unfolded groups appeared above the first chat while the list was at the top, but the list does not scroll to them"
        )
    }

    @Test
    fun no_scroll_when_the_user_had_scrolled_down() {
        assertFalse(
            shouldScrollToTop("peerA", wasAtTop = false, newRowKeys = listOf(groupsFolder, "peerA", "peerB")),
            "the list was scrolled down by the user, it must not jump to the top"
        )
    }

    @Test
    fun no_scroll_when_the_first_row_is_still_first() {
        assertFalse(shouldScrollToTop(groupsFolder, wasAtTop = true, newRowKeys = listOf(groupsFolder, "peerA", "peerB")))
        assertFalse(shouldScrollToTop("peerA", wasAtTop = true, newRowKeys = listOf("peerA", "peerB", "peerC")))
    }

    @Test
    fun no_scroll_when_the_first_row_is_gone_or_unknown() {
        assertFalse(shouldScrollToTop("peerGone", wasAtTop = true, newRowKeys = listOf(groupsFolder, "peerA")))
        assertFalse(shouldScrollToTop(null, wasAtTop = true, newRowKeys = listOf(groupsFolder, "peerA")))
        assertFalse(shouldScrollToTop("peerA", wasAtTop = true, newRowKeys = emptyList()))
    }

    @Test
    fun the_list_uses_the_rule_and_scrolls_to_item_zero() {
        val lexed =
            KotlinUiLiterals.lex(
                ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt")
            )
        val r = KotlinUiLiterals.block(lexed, "private fun ConversationsAndGroupsList(")
        val list = lexed.blanked.substring(r.first, r.last + 1)
        assertTrue(
            Regex("""\bChatListLayout\.shouldScrollToTop\(""").containsMatchIn(list),
            "ConversationsAndGroupsList does not call ChatListLayout.shouldScrollToTop(…): rows inserted above keep the Groups row out of view"
        )
        assertTrue(
            Regex("""\b(?:requestScrollToItem|scrollToItem)\(\s*(?:index\s*=\s*)?0\b""").containsMatchIn(list),
            "ConversationsAndGroupsList never scrolls its list state to item 0"
        )
        assertTrue(
            Regex("""\bLazyColumn\([^{]*\bstate\s*=""").containsMatchIn(list),
            "the LazyColumn in ConversationsAndGroupsList is not given the list state it scrolls"
        )
    }
}
