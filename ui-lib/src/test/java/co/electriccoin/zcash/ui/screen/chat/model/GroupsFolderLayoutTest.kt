package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1104 (owner design 26 Oct 2026, freeze exception W-79): at bc8efae90 `ConversationsAndGroupsList` lists every group
 * first under a "Groups" header and only then the private chats, so with many groups the user scrolls far to reach a
 * private message.
 *
 * What it must be: ONE compact "Groups" row at the top (the number of groups and a badge with the total unread count
 * of all groups), then the private chats in their usual order. Collapsed by default; expanded, the groups follow the
 * row inline in their existing order, before the chats. No Groups row when there are no groups.
 *
 * The rule lives in `ChatListLayout.rows(conversations, groups, groupUnread, groupsExpanded): List<ChatListRow>` (pure,
 * no Compose runtime), with `ChatListRow.GroupsFolder(count, unread)`, `ChatListRow.Group(group)` and
 * `ChatListRow.Conversation(conversation)`. `groupUnread` maps a group id to its unread count (GroupInfo carries none).
 *
 * Read by reflection so this RED test compiles before the API exists.
 */
class GroupsFolderLayoutTest {
    private val layout: Any by lazy {
        Class.forName("co.electriccoin.zcash.ui.screen.chat.model.ChatListLayout").getField("INSTANCE").get(null)!!
    }

    private fun rows(
        conversations: List<Conversation>,
        groups: List<GroupInfo>,
        groupUnread: Map<String, Int> = emptyMap(),
        expanded: Boolean
    ): List<Any> {
        val method =
            layout.javaClass.getMethod(
                "rows",
                List::class.java,
                List::class.java,
                Map::class.java,
                Boolean::class.javaPrimitiveType
            )
        @Suppress("UNCHECKED_CAST")
        return method.invoke(layout, conversations, groups, groupUnread, expanded) as List<Any>
    }

    private fun get(
        row: Any,
        property: String
    ): Any? = row.javaClass.getMethod("get" + property.replaceFirstChar { it.uppercase() }).invoke(row)

    /** A readable shape: "Folder(count,unread)", "Group(id)", "Chat(address)". */
    private fun shape(row: Any): String =
        when (row.javaClass.simpleName) {
            "GroupsFolder" -> "Folder(${get(row, "count")},${get(row, "unread")})"
            "Group" -> "Group(${(get(row, "group") as GroupInfo).groupId})"
            "Conversation" -> "Chat(${(get(row, "conversation") as Conversation).peerAddress})"
            else -> "?" + row.javaClass.name
        }

    private fun chat(address: String) = Conversation(peerAddress = address, messages = emptyList(), lastMessage = null)

    private fun group(id: String) = GroupInfo(groupId = id, name = "Group $id", creatorAddress = "u1peer", createdAt = Instant.EPOCH)

    private val chats = listOf(chat("u1alice"), chat("u1bob"))
    private val groups = listOf(group("g2"), group("g1"), group("g3"))

    @Test
    fun collapsed_the_list_is_one_groups_row_then_the_private_chats() {
        assertEquals(
            listOf("Folder(3,0)", "Chat(u1alice)", "Chat(u1bob)"),
            rows(chats, groups, expanded = false).map(::shape),
            "collapsed: one Groups row with the number of groups, no group rows, then the chats in their order"
        )
    }

    @Test
    fun expanded_the_groups_follow_their_row_in_their_order_before_the_chats() {
        assertEquals(
            listOf("Folder(3,0)", "Group(g2)", "Group(g1)", "Group(g3)", "Chat(u1alice)", "Chat(u1bob)"),
            rows(chats, groups, expanded = true).map(::shape),
            "expanded: the Groups row, its groups inline in the existing order, then the chats"
        )
    }

    @Test
    fun the_badge_is_the_sum_of_the_listed_groups_unread() {
        val unread = mapOf("g1" to 4, "g2" to 0, "g3" to 7, "gone" to 50)
        val collapsed = rows(chats, groups, unread, expanded = false)
        assertEquals("Folder(3,11)", shape(collapsed.first()), "badge = 4 + 0 + 7; a group not in the list is not counted")
        assertEquals("Folder(3,11)", shape(rows(chats, groups, unread, expanded = true).first()), "same badge expanded")
    }

    @Test
    fun no_groups_means_no_groups_row() {
        assertEquals(
            listOf("Chat(u1alice)", "Chat(u1bob)"),
            rows(chats, emptyList(), expanded = false).map(::shape),
            "no groups: no Groups row"
        )
        assertTrue(rows(emptyList(), emptyList(), expanded = true).isEmpty(), "nothing at all: no rows")
    }

    @Test
    fun groups_only_still_show_their_row() {
        assertEquals(listOf("Folder(1,2)"), rows(emptyList(), listOf(group("g1")), mapOf("g1" to 2), expanded = false).map(::shape))
        assertEquals(
            listOf("Folder(1,2)", "Group(g1)"),
            rows(emptyList(), listOf(group("g1")), mapOf("g1" to 2), expanded = true).map(::shape)
        )
    }
}
