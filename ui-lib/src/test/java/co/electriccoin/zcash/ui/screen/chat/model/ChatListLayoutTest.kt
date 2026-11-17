package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OF-1 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §6, owner-reported on the Fold): on an empty chat list the
 * centred "New Chat" FAB is drawn over the empty state's own "Start a Chat" button. The rule for the FAB lives in
 * `ChatListLayout` (pure, no Compose runtime): the empty list (Success, no chats and no groups) has one call to
 * action, the empty state's button, so the FAB is hidden there; every other state keeps the FAB. The lists leave
 * [ChatListLayout.FAB_CLEARANCE] (FAB 56 dp + 16 dp margin + 16 dp) below the last row so it can scroll clear of
 * the FAB.
 *
 * Read by reflection so this RED test compiles before `ChatListLayout` exists.
 */
class ChatListLayoutTest {
    private val layout: Any by lazy {
        Class.forName("co.electriccoin.zcash.ui.screen.chat.model.ChatListLayout").getField("INSTANCE").get(null)!!
    }

    private fun showNewChatFab(state: ChatListState): Boolean =
        layout.javaClass.getMethod("showNewChatFab", ChatListState::class.java).invoke(layout, state) as Boolean

    private fun success(
        conversations: List<Conversation> = emptyList(),
        groups: List<GroupInfo> = emptyList()
    ) = ChatListState.Success(conversations = conversations, groups = groups, currentUserAddress = "u1self")

    private val chat = Conversation(peerAddress = "u1peer", messages = emptyList(), lastMessage = null)

    private val group =
        GroupInfo(groupId = "g1", name = "Group", creatorAddress = "u1peer", createdAt = Instant.EPOCH)

    @Test
    fun the_empty_list_has_no_fab_its_start_button_is_the_one_call_to_action() {
        assertFalse(showNewChatFab(success()), "empty list: \"Start a Chat\" and the \"New Chat\" FAB overlap")
    }

    @Test
    fun a_list_with_one_chat_keeps_the_fab() {
        assertTrue(showNewChatFab(success(conversations = listOf(chat))))
    }

    @Test
    fun a_list_with_groups_only_keeps_the_fab() {
        assertTrue(showNewChatFab(success(groups = listOf(group))))
    }

    @Test
    fun loading_and_error_keep_the_fab() {
        assertTrue(showNewChatFab(ChatListState.Loading), "Loading")
        assertTrue(showNewChatFab(ChatListState.Error("boom")), "Error has no Start button of its own")
    }

    @Test
    fun the_fab_clearance_is_88_dp() {
        val getter = layout.javaClass.methods.single { it.name.startsWith("getFAB_CLEARANCE") }
        assertEquals(88f, getter.invoke(layout) as Float, "FAB 56 dp + 16 dp margin + 16 dp")
    }
}
