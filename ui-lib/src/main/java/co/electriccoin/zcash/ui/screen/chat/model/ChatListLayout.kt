package co.electriccoin.zcash.ui.screen.chat.model

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * OF-1: the chat list's layout rules, kept free of Compose so they are unit-tested.
 *
 * The Scaffold's centred "New Chat" FAB is not subtracted from the content padding. On the empty list the empty
 * state's own "Start a Chat" button is the one call to action, so the FAB is not drawn there (on the Fold it covered
 * that button). The lists leave [FAB_CLEARANCE] below their last row, so it scrolls clear of the FAB.
 */
object ChatListLayout {
    /** The FAB (56 dp) + its 16 dp margin + 16 dp of air. */
    val FAB_CLEARANCE: Dp = 88.dp

    /** False only on a loaded list with no chats and no groups; Loading and Error keep the FAB. */
    fun showNewChatFab(state: ChatListState): Boolean =
        !(state is ChatListState.Success && state.conversations.isEmpty() && state.groups.isEmpty())

    /**
     * ZC-1104 (W-79): the rows of the chat list. One [ChatListRow.GroupsFolder] on top when there are groups (their
     * number and the unread count of the listed groups added up; a [groupUnread] entry for a group not in [groups] is
     * not counted), then, only when [groupsExpanded], the groups in their order, then the private chats in theirs.
     * No groups: no Groups row.
     */
    fun rows(
        conversations: List<Conversation>,
        groups: List<GroupInfo>,
        groupUnread: Map<String, Int>,
        groupsExpanded: Boolean
    ): List<ChatListRow> =
        buildList {
            if (groups.isNotEmpty()) {
                val unread = groups.sumOf { (groupUnread[it.groupId] ?: 0).coerceAtLeast(0) }
                add(ChatListRow.GroupsFolder(count = groups.size, unread = unread))
                if (groupsExpanded) groups.forEach { add(ChatListRow.Group(it)) }
            }
            conversations.forEach { add(ChatListRow.Conversation(it)) }
        }

    /**
     * QA2 Q2-57a (ZC-1653, W-137): [groupUnread] with every listed group (in [groups]) that awaits Join
     * ([groupsAwaitingJoin], ChatListState.groupsAwaitingJoin) counted as at least 1 unread, so a phone put into a group
     * by an unanswered invitation no longer reads "1 group, 0 unread". A higher own count stays; other entries are left
     * as they were, and an awaiting id that is not a listed group is not added.
     */
    fun unreadWithInvitations(
        groups: List<GroupInfo>,
        groupUnread: Map<String, Int>,
        groupsAwaitingJoin: Set<String>
    ): Map<String, Int> {
        if (groupsAwaitingJoin.isEmpty()) return groupUnread
        val out = LinkedHashMap(groupUnread)
        groups.forEach { group ->
            if (group.groupId in groupsAwaitingJoin) {
                out[group.groupId] = (groupUnread[group.groupId] ?: 0).coerceAtLeast(1)
            }
        }
        return out
    }

    /**
     * QA2 Q2-57a (ZC-1653, W-137): the Groups row is shown open when the user left it open ([saved]), during a search
     * ([searching]), or while a listed group (in [groups]) awaits Join ([groupsAwaitingJoin]).
     */
    fun groupsRowOpen(
        saved: Boolean,
        searching: Boolean,
        groups: List<GroupInfo>,
        groupsAwaitingJoin: Set<String>
    ): Boolean = saved || searching || groups.any { it.groupId in groupsAwaitingJoin }

    /**
     * ZC-1140: true when the list was at the top ([wasAtTop]: first visible index 0, offset 0) and new rows now stand
     * above the row that was first ([previousFirstKey] found in [newRowKeys] at an index above 0), so the list scrolls
     * back to the top and shows them (the live pass inserts the Groups row above the kept pass's first chat). False when
     * the user had scrolled down, when the first row is still first, and when it is gone or unknown.
     */
    fun shouldScrollToTop(
        previousFirstKey: Any?,
        wasAtTop: Boolean,
        newRowKeys: List<Any>
    ): Boolean = wasAtTop && previousFirstKey != null && newRowKeys.indexOf(previousFirstKey) > 0
}

/** ZC-1104: one row of the chat list ([ChatListLayout.rows]). */
sealed interface ChatListRow {
    /** The Groups row: [count] groups, [unread] unread messages in all of them. */
    data class GroupsFolder(
        val count: Int,
        val unread: Int
    ) : ChatListRow

    data class Group(
        val group: GroupInfo
    ) : ChatListRow

    data class Conversation(
        val conversation: co.electriccoin.zcash.ui.screen.chat.model.Conversation
    ) : ChatListRow
}
