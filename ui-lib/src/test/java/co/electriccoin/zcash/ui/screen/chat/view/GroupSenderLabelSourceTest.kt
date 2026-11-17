package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GROUP-LABEL (fix-all 2.13.0, train T4b) — the group screens must use the names GroupViewModel resolves.
 *
 * At HEAD the group bubble header is `senderAddress.take(8) + "..."`, the member row, its avatar and the remove dialog
 * read only the roster row's snapshot (`member.displayName` / `member.nickname`), and GroupViewModel.memberName puts the
 * roster snapshot before the 1-to-1 nickname and the saved contact name.
 *
 * Wanted: the bubble shows `conversation.senderNames[message.senderAddress]`, else PeerName.shortAddress; the member
 * row, the avatar and the remove dialog read the settings names map first; memberName resolves through PeerName.label
 * (the order of the 1-to-1 chat header).
 */
class GroupSenderLabelSourceTest {
    private val chatRoot = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"

    /** The text of the function [name] in [source]: from its declaration to the next top-level or member `fun`. */
    private fun functionBody(
        source: String,
        name: String
    ): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val next = Regex("""\n\s*(private |internal |public )?(suspend )?fun """).find(source, start + 1)
        return source.substring(start, next?.range?.first ?: source.length)
    }

    @Test
    fun `S1 the group bubble names the sender from senderNames, else the short address`() {
        val view = ChatResourceText.source("$chatRoot/view/GroupDetailView.kt")
        assertFalse("GroupDetailView still cuts the sender address by hand", "senderAddress.take(" in view)
        assertTrue(
            "GroupDetailView does not pass the resolved sender name to the bubble",
            "conversation.senderNames[message.senderAddress]" in view
        )
        assertTrue(
            "the bubble does not fall back to PeerName.shortAddress",
            "PeerName.shortAddress(" in functionBody(view, "GroupMessageBubble")
        )
    }

    @Test
    fun `S2 the member list, its avatar and the remove dialog use the settings names`() {
        val view = ChatResourceText.source("$chatRoot/view/GroupSettingsView.kt")
        assertTrue("GroupSettingsView does not pass the resolved name to the member row", "names[member.address]" in view)
        val item = functionBody(view, "MemberItem")
        assertTrue("the member row does not show the resolved name first", "name ?: member.displayName" in item)
        assertTrue("the member avatar does not use the resolved name first", "name ?: member.nickname" in item)
        assertFalse("the member row still shows only the roster snapshot", "text = member.displayName" in item)
        assertFalse(
            "the remove dialog still names only the roster snapshot",
            "group_remove_member_body, member.displayName" in item
        )
    }

    @Test
    fun `S3 memberName resolves in the order of the 1-to-1 chat header`() {
        val vm = ChatResourceText.source("$chatRoot/viewmodel/GroupViewModel.kt")
        val body = functionBody(vm, "memberName")
        assertTrue("memberName does not resolve through PeerName.label", "PeerName.label(" in body)
        assertFalse("memberName still puts the roster snapshot first", "member.nickname ?: contactBook" in body)
    }
}
