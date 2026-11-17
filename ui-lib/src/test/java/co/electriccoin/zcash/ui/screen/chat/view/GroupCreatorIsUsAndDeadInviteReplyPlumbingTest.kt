package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QA2 P3 polish, U9 review nits (Fable review of the U9 follow-up; FIX plan "U9 review nits").
 *
 * 1. `groupCreatorIsUs` (AndroidChat.kt) decides "the creator of this group is us" with `isSelfAddress(creator)` alone.
 *    Everywhere else the decision is `isGroupSelfCreated(groupId) || isSelfAddress(creator)` (GroupViewModel, GroupAdminCheck,
 *    GroupAnnouncer, ChatViewModel): a group this phone created is ours even when its stored creator address is one the
 *    registry does not list (a restore, a drift). Without the first half, such a group shows "<our address> added you to
 *    this group." The existing pin (GroupAddedNoteTest) accepts either call; this one needs both.
 * 2. Dead reply plumbing: since ZC-922 (W-137 B-4) no invite sends anything while the group lock is held, so
 *    `processGroupInviteLocked` returns null on every path and `processGroupInvite`'s `reply?.invoke()` can never run. The
 *    function types, the `@return` and the KDocs that still describe "the reply to launch once the lock is released"
 *    (ChatViewModel.kt, processGroupInvite, processGroupInviteLocked, finishInvitesReadBeforeTheirKey) are stale.
 *
 * Source checks (Compose screen and a 30,000-line view model; the behaviour is unchanged by both, which the existing
 * invite tests keep pinning).
 */
class GroupCreatorIsUsAndDeadInviteReplyPlumbingTest {
    private val chat = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"
    private val androidChat by lazy { ChatResourceText.source("$chat/AndroidChat.kt") }
    private val chatVm by lazy { ChatResourceText.source("$chat/viewmodel/ChatViewModel.kt") }

    @Test
    fun `the added-you note's creator-is-us decision also asks whether this phone created the group`() {
        val start = androidChat.indexOf("val groupCreatorIsUs =")
        assertTrue(start >= 0, "AndroidChat.kt has no groupCreatorIsUs")
        val block = androidChat.substring(start, androidChat.indexOf("\n\n", start))
        assertTrue("isSelfAddress(" in block, "groupCreatorIsUs no longer asks the self-address registry: $block")
        assertTrue(
            "isGroupSelfCreated(" in block,
            "groupCreatorIsUs ignores isGroupSelfCreated(groupId): a group this phone created, whose stored creator is not " +
                "in the self-address registry, would read \"<our address> added you to this group.\": $block",
        )
    }

    @Test
    fun `processGroupInviteLocked returns nothing and processGroupInvite launches no reply`() {
        val signatureTail = chatVm.substringAfter("private fun processGroupInviteLocked(").substringBefore("{")
        assertFalse(
            "(() -> Unit)?" in signatureTail,
            "processGroupInviteLocked still returns a reply to launch (`(() -> Unit)?`), but every path returns null",
        )
        assertFalse("reply?.invoke()" in chatVm, "processGroupInvite still launches a reply that is never there")
    }

    @Test
    fun `no KDoc still describes a reply launched after the invite lock is released`() {
        // KDoc line breaks and their leading asterisks are folded, so the check does not depend on the wrapping.
        val flat = chatVm.replace(Regex("""\s*\n\s*\*\s*"""), " ")
        val stale =
            listOf(
                "@return the reply to launch once the lock is released",
                "reply it may start is launched after the lock is released",
                "reply launched once the lock is released",
            )
        for (text in stale) {
            assertFalse(text in flat, "ChatViewModel.kt still says: $text")
        }
    }
}
