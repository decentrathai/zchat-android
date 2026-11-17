package co.electriccoin.zcash.ui.regression

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ZC-1227: a group row in the chat list kept the time the group was created after new group messages arrived
 * (GroupItem drew rememberChatListTimestamp(group.createdAt)). Wanted: the row time comes from the newest message.
 */
class GroupRowTimeFollowsNewestMessageTest {
    @Test
    fun `GroupItem does not draw the creation time`() {
        val src = PolSourceFiles.main("screen/chat/view/ChatListView.kt").readText()
        val start = src.indexOf("private fun GroupItem(")
        assertTrue(start >= 0, "GroupItem moved; update this pin")
        val end = src.indexOf("\n}\n", start)
        val body = src.substring(start, end)
        assertTrue("rememberChatListTimestamp(group.createdAt)" !in body, "GroupItem time is group.createdAt, not the newest message")
    }
}
