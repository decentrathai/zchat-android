package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.CreateGroupState
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.groupMessageCostFloor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * R6-2 (round 5 report §5 item 3) — two money numbers on the New Group screen contradict each other.
 *
 * The cost note under Create Group is built from `CreateGroupState.memberCount`, which is
 * `selectedMembers.size + 1` — the creator counted as someone who is paid for. With one invitee the note says
 * "at least 0.00034 ZEC for 2 members" while the consent prompt on the same screen correctly says "1 on-chain
 * transaction: at least 0.00017 ZEC". Nobody pays the creator: every invite and every message goes to the
 * members chosen, never to yourself.
 *
 * Wanted: the note quotes exactly the member count and the total the prompt (and the spend) will show. The
 * note's count is read from whichever `CreateGroupState` property CreateGroupView passes to the plural, so
 * this follows the screen's real code rather than a copy of it. The button's own "Create Group (N members)"
 * is the size of the group and keeps counting the creator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateGroupCostNoteMatchesPromptTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        vms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res").isDirectory) dir = dir.parentFile
        assertNotNull("repo root not found", dir)
        dir!!
    }

    /** The count the Create Group cost note is built from, evaluated the way CreateGroupView evaluates it. */
    private fun noteMembers(state: CreateGroupState): Int {
        val view =
            File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/CreateGroupView.kt").readText()
        val match = Regex("""val costMembers = state\.(\w+)(\.coerceAtLeast\(1\))?""").find(view)
        assertNotNull("CreateGroupView no longer builds the cost note from `val costMembers = state.<count>`", match)
        val property = match!!.groupValues[1]
        val raw =
            CreateGroupState::class.java
                .getMethod("get" + property.replaceFirstChar { it.uppercase() })
                .invoke(state) as Int
        return if (match.groupValues[2].isNotEmpty()) raw.coerceAtLeast(1) else raw
    }

    private suspend fun quoteAndCompare(vararg invitees: String) {
        val f = PaidGroupFixture()
        f.scheduler = scheduler
        val vm = f.groupViewModel().also { vms += it }
        f.awaitNewJobs(vm) {
            vm.setGroupName("Synthetic cost-note group")
            invitees.forEach { vm.toggleMemberSelection(it) }
            vm.createGroup()
        }
        val prompt = vm.paidActionConfirmation.value
        assertNotNull("fixture: every invitee is on-chain, so Create Group must ask first", prompt)
        assertEquals(PaidAction.GROUP_CREATE, prompt!!.action)
        assertEquals("fixture: one on-chain invite per invitee", invitees.size, prompt.onChainMessages)

        val state = vm.createGroupState.value
        val members = noteMembers(state)
        assertEquals(
            "the cost note counts ${members - prompt.onChainMessages} member(s) the prompt does not " +
                "(the creator is never paid for)",
            prompt.onChainMessages,
            members,
        )
        assertEquals(
            "the cost note's total disagrees with the consent prompt on the same screen",
            prompt.totalCost.value,
            groupMessageCostFloor(members).value,
        )
        assertEquals("nothing is paid before Confirm", 0, f.seam.proposalRequests.get())
    }

    @Test
    fun `one invitee - the note quotes the same one transaction and total as the prompt`() =
        runBlocking { quoteAndCompare(PaidGroupFixture.MEMBER_A) }

    @Test
    fun `three invitees - the note quotes the same three transactions and total as the prompt`() =
        runBlocking { quoteAndCompare(PaidGroupFixture.MEMBER_A, PaidGroupFixture.MEMBER_B, PaidGroupFixture.MEMBER_C) }

    /** Pin (passes before and after): the button names the size of the group, which includes its creator. */
    @Test
    fun `the Create Group button still counts the creator as a member of the group`() {
        val state = CreateGroupState(groupName = "Synthetic", selectedMembers = listOf(PaidGroupFixture.MEMBER_A))
        assertEquals(2, state.memberCount)
        val view =
            File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/CreateGroupView.kt").readText()
        assertTrue(
            "the button no longer shows the group's size",
            "R.plurals.group_create_button, state.memberCount, state.memberCount" in view
        )
    }
}
