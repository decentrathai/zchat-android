package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertTrue

/**
 * QA2 B-3 (Q2-25, ZC-1512; owner W-137; design FABLE_BEHAVIOUR_DESIGN.md "B-3"): one mis-tap on Reject lost the request
 * because Restore is reachable only while the requests sheet is open and only while another request is pending.
 *
 * The chat list's overflow menu gets a "Declined requests" entry (the existing message_requests_declined_title, no new
 * title) that opens the same sheet. It is drawn only when something is declined, as the guard test
 * DeclinedRequestsAreWiredIntoTheScreensTest requires of every reader of declinedMessageRequests(). The sheet's Declined
 * section carries the hint "Declined — tap Restore to accept later" (message_requests_declined_hint).
 */
class DeclinedRequestsReachableFromTheChatListMenuTest {
    private val chat = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"
    private val listView by lazy { ChatResourceText.source("$chat/view/ChatListView.kt") }
    private val androidChat by lazy { ChatResourceText.source("$chat/AndroidChat.kt") }

    /** The ⋮ menu of the chat list: from its first DropdownMenu to the Settings entry's action. */
    private val menu by lazy {
        val start = listView.indexOf("DropdownMenu(")
        assertTrue(start >= 0, "ChatListView has no DropdownMenu")
        val end = listView.indexOf("onSettingsClick()", start)
        assertTrue(end > start, "the chat list menu has no Settings entry")
        listView.substring(start, end)
    }

    @Test
    fun the_chat_list_menu_has_a_declined_requests_entry() {
        assertTrue("R.string.message_requests_declined_title" in menu, "the ⋮ menu of the chat list has no Declined requests entry")
        val at = menu.indexOf("R.string.message_requests_declined_title")
        val item = menu.substring(menu.lastIndexOf("DropdownMenuItem(", at))
        assertTrue("onDeclinedRequestsClick" in item, "the entry does not call onDeclinedRequestsClick")
    }

    @Test
    fun the_entry_is_drawn_only_when_something_is_declined() {
        val at = menu.indexOf("R.string.message_requests_declined_title")
        assertTrue(at >= 0, "no Declined requests entry")
        val before = menu.substring(maxOf(0, at - 700), at)
        assertTrue(
            Regex("""declinedRequestCount\s*>\s*0""").containsMatchIn(before),
            "an always-visible entry would open an empty sheet; it must be guarded by declinedRequestCount > 0",
        )
        assertTrue(
            Regex("""declinedRequestCount\s*:\s*Int\s*=\s*0""").containsMatchIn(listView),
            "ChatListView has no declinedRequestCount parameter",
        )
        assertTrue(Regex("""onDeclinedRequestsClick\s*:\s*\(\)\s*->\s*Unit""").containsMatchIn(listView))
    }

    @Test
    fun the_screen_passes_the_declined_count_and_opens_the_sheet_without_a_pending_request() {
        assertTrue(
            Regex("""declinedRequestCount\s*=\s*declinedRequests\.size""").containsMatchIn(androidChat),
            "AndroidChat does not pass the declined count to the chat list",
        )
        val handler = Regex("""onDeclinedRequestsClick\s*=\s*\{([^}]*)}""").find(androidChat)
        assertTrue(handler != null, "AndroidChat does not wire onDeclinedRequestsClick")
        val body = handler.groupValues[1]
        assertTrue("showRequestsDialog = true" in body, "the entry must open the requests sheet: $body")
        assertTrue("messageRequests.isNotEmpty()" !in body, "the entry is gated on a pending request, which is the bug: $body")
    }

    @Test
    fun the_declined_section_of_the_sheet_carries_the_restore_hint() {
        val sheet = androidChat.substring(androidChat.indexOf("private fun MessageRequestsDialog("))
        assertTrue("R.string.message_requests_declined_hint" in sheet, "the Declined section does not say Restore accepts later")
    }
}
