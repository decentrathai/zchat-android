package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertTrue

/**
 * QA2 U5-groups review follow-up (owner W-136/W-137: fix every QA2 finding in 2.13.0). Two source pins, because
 * no Compose test seam exists for these two screens (pure layout is [no-red]).
 *
 * - Review finding 3 (ZC-1653): while a waiting invitation forces the Groups row open, a tap on the header must not
 *   flip the saved "expanded" flag. The toggle handed to the list has to know about the forced-open state, not only
 *   about the search query (ZC-1104 review F4, GroupUnreadCountingTest, pins the search half).
 * - Review finding 4 (ZC-1650): `isSendingMessage` is one flow for all groups (GroupViewModel). The new "Sending…
 *   your message stays here until it is sent." must not show in a group whose composer holds nothing to send, or it
 *   is a false statement over an empty box. A per-group sending id needs AndroidChat (wave 2); until then the view
 *   shows the sending line only for a composer that has text.
 */
class GroupsRowToggleAndSendingLineFollowUpTest {
    private val chatRoot = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"

    @Test
    fun `finding 3 a tap on the Groups header while an invitation forces the row open changes nothing`() {
        val source = ChatResourceText.source("$chatRoot/view/ChatListView.kt")
        val call = source.substringAfter("ConversationsAndGroupsList(\n")
        val toggle =
            call.substringAfter("onToggleGroupsFolder =").substringBefore("onConversationClick = onConversationClick")
        assertTrue("searchQuery" in toggle, "the toggle lost its search guard: $toggle")
        assertTrue(
            toggle.contains("groupsAwaitingJoin", ignoreCase = true) || toggle.contains("rowOpen", ignoreCase = true),
            "the toggle handed to the list ignores the forced-open invitation state, so a tap flips the saved " +
                "flag with no change on screen: $toggle"
        )
    }

    @Test
    fun `finding 4 the sending line is not shown over a composer with nothing to send`() {
        val source = ChatResourceText.source("$chatRoot/view/GroupDetailView.kt")
        val usage = source.indexOf("R.string.group_composer_sending")
        assertTrue(usage >= 0, "GroupDetailView never says it is sending")
        val start = source.lastIndexOf("if (inGroup &&", usage)
        assertTrue(start in 0 until usage, "the cost line's condition was not found before the sending text")
        val condition = source.substring(start, usage)
        assertTrue(
            "messageText.isNotBlank()" in condition || "messageText.isNotEmpty()" in condition,
            "the sending line depends on the VM-wide isSendingMessage only; a send in another group shows " +
                "\"Sending…\" over this empty composer: $condition"
        )
    }
}
