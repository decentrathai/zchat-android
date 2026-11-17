package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QA2 Q2-57a (ZC-1653, owner W-137, 10 Nov 2026): a phone put into a group by an invitation it has not answered showed
 * "1 group, 0 unread" on a collapsed Groups row, so nothing told the user that anything was waiting.
 *
 * Wanted (pure rules in ChatListLayout, no Compose; the invitation ids are ChatListState.groupsAwaitingJoin):
 * 1. unreadWithInvitations(groups, groupUnread, groupsAwaitingJoin): a listed group that awaits Join counts at least 1
 *    unread (its own count when that is higher); other groups and ids that are not listed are left as they were.
 * 2. groupsRowOpen(saved, searching, groups, groupsAwaitingJoin): the Groups row starts open while a listed group awaits
 *    Join, as it is open for a search or a saved choice.
 * 3. ChatListView feeds both rules into ChatListLayout.rows, so the folder badge reads 1.
 *
 * The rules are read by reflection so this file compiles before they exist. Not in scope here: the Join/Decline
 * choice itself (B-4b, wave 2).
 */
class GroupsRowCountsAnInvitationTest {
    private val layout: Any by lazy {
        Class.forName("co.electriccoin.zcash.ui.screen.chat.model.ChatListLayout").getField("INSTANCE").get(null)!!
    }

    private fun group(id: String) = GroupInfo(groupId = id, name = "Group $id", creatorAddress = "u1peer", createdAt = Instant.EPOCH)

    private val groups = listOf(group("g1"), group("g2"), group("g3"))

    @Suppress("UNCHECKED_CAST")
    private fun unreadWithInvitations(
        unread: Map<String, Int>,
        awaiting: Set<String>
    ): Map<String, Int> {
        val method =
            layout.javaClass.methods.firstOrNull { it.name == "unreadWithInvitations" && it.parameterCount == 3 }
                ?: throw AssertionError("ChatListLayout.unreadWithInvitations(groups, groupUnread, groupsAwaitingJoin) does not exist")
        return method.invoke(layout, groups, unread, awaiting) as Map<String, Int>
    }

    private fun groupsRowOpen(
        saved: Boolean,
        searching: Boolean,
        awaiting: Set<String>,
        listed: List<GroupInfo> = groups
    ): Boolean {
        val method =
            layout.javaClass.methods.firstOrNull { it.name == "groupsRowOpen" && it.parameterCount == 4 }
                ?: throw AssertionError("ChatListLayout.groupsRowOpen(saved, searching, groups, groupsAwaitingJoin) does not exist")
        return method.invoke(layout, saved, searching, listed, awaiting) as Boolean
    }

    @Test
    fun `an invitation counts as one unread`() {
        val counted = unreadWithInvitations(mapOf("g1" to 0, "g2" to 0, "g3" to 4), setOf("g1"))
        assertEquals(1, counted["g1"], "a group awaiting Join with nothing stored counts 1 unread")
        assertEquals(0, counted["g2"], "a group that awaits nothing keeps its count")
        assertEquals(4, counted["g3"], "a group that awaits nothing keeps its count")
        assertEquals(1, unreadWithInvitations(emptyMap(), setOf("g2"))["g2"], "no count at all still counts 1")
    }

    @Test
    fun `an invitation never lowers a higher count and an unlisted id is not counted`() {
        val counted = unreadWithInvitations(mapOf("g1" to 5), setOf("g1", "gone"))
        assertEquals(5, counted["g1"], "the group's own higher count stays")
        assertFalse("gone" in counted, "an id that is not a listed group is not counted")
    }

    @Test
    fun `the folder badge reads one for a lone invitation`() {
        val unread = unreadWithInvitations(emptyMap(), setOf("g1"))
        val rows = ChatListLayout.rows(emptyList(), listOf(group("g1")), unread, groupsExpanded = false)
        assertEquals(ChatListRow.GroupsFolder(count = 1, unread = 1), rows.first(), "1 group, 1 unread")
    }

    @Test
    fun `the groups row starts open while a listed group awaits Join`() {
        assertTrue(groupsRowOpen(saved = false, searching = false, awaiting = setOf("g2")), "an invitation row starts expanded")
        assertFalse(groupsRowOpen(saved = false, searching = false, awaiting = emptySet()), "nothing waits: collapsed as before")
        assertFalse(
            groupsRowOpen(saved = false, searching = false, awaiting = setOf("gone")),
            "an invitation for a group that is not listed does not open the row"
        )
        assertTrue(groupsRowOpen(saved = true, searching = false, awaiting = emptySet()), "a saved open choice stays open")
        assertTrue(groupsRowOpen(saved = false, searching = true, awaiting = emptySet()), "a search shows the row open")
    }

    @Test
    fun `the chat list feeds both rules to the folder`() {
        val view = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt")
        assertTrue("ChatListLayout.unreadWithInvitations(" in view, "ChatListView does not count an invitation as unread")
        assertTrue("ChatListLayout.groupsRowOpen(" in view, "ChatListView does not open the row for an invitation")
    }
}
