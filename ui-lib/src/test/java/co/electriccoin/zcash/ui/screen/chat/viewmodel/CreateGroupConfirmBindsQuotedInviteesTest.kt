package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmation
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

/**
 * GPT-6 F2 (22 Sep) — confirming a new group pays for a plan the user was never quoted.
 *
 * createGroup() quotes the invitees selected when it is tapped and keeps "createGroup(costConfirmed = true)"
 * for the Confirm. That call reads the selection again, so whatever changed between the quote and the
 * Confirm is paid under the old quote: ask for A (one on-chain invite), add B, Confirm, and two invites are
 * paid. The same holds for a member quoted as free over Nostr: when the relay publish fails the invite falls
 * back to a paid on-chain memo that was never quoted.
 *
 * Wanted: Confirm runs exactly the plan that was quoted. When the group name or the invitees changed, Confirm
 * pays nothing and asks again with the new quote. A member quoted as free is never paid on-chain under that
 * quote: a failed free delivery is shown as a failed invite (Resend asks before it pays).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateGroupConfirmBindsQuotedInviteesTest {
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

    private suspend fun viewModel(f: PaidGroupFixture): GroupViewModel {
        f.scheduler = scheduler
        return f.groupViewModel().also { vms += it }
    }

    private fun quotedMessages(vm: GroupViewModel): Int? = vm.paidActionConfirmation.value?.onChainMessages

    private fun onScreen(vm: GroupViewModel): PaidActionConfirmation? = vm.paidActionConfirmation.value

    @Test
    fun `an invitee added after the quote is not paid under it, and Confirm asks again`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)
            f.awaitNewJobs(vm) {
                vm.setGroupName("Synthetic quoted group")
                vm.toggleMemberSelection(PaidGroupFixture.MEMBER_A)
                vm.createGroup()
            }
            assertEquals("fixture: the quote covers member A only", 1, quotedMessages(vm))

            vm.toggleMemberSelection(PaidGroupFixture.MEMBER_B)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals(
                "Confirm paid for an invitee added after the one-invite quote was shown",
                0,
                f.seam.proposalRequests.get(),
            )
            assertNotNull("the changed plan must be quoted again", onScreen(vm))
            assertEquals("the new quote covers both invitees", 2, quotedMessages(vm))

            f.awaitNewJobs(vm) { vm.confirmPaidAction() }
            assertEquals("confirming the new quote pays what it quoted", 2, f.seam.proposalRequests.get())
        }

    @Test
    fun `a group name changed after the quote is not created under it, and Confirm asks again`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)
            f.awaitNewJobs(vm) {
                vm.setGroupName("Synthetic first name")
                vm.toggleMemberSelection(PaidGroupFixture.MEMBER_A)
                vm.createGroup()
            }
            assertEquals(1, quotedMessages(vm))

            vm.setGroupName("Synthetic second name")
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("Confirm ran a plan other than the one quoted", 0, f.seam.proposalRequests.get())
            assertEquals("the changed plan must be quoted again", 1, quotedMessages(vm))
        }

    @Test
    fun `a member quoted as free is not paid on-chain when the free delivery fails`() =
        runBlocking {
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_B))
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 0, messageId = null) }
            val vm = viewModel(f)
            f.awaitNewJobs(vm) {
                vm.setGroupName("Synthetic relay group")
                vm.toggleMemberSelection(PaidGroupFixture.MEMBER_A)
                vm.toggleMemberSelection(PaidGroupFixture.MEMBER_B)
                vm.createGroup()
            }
            assertEquals("fixture: B is quoted as free, A on-chain", 1, quotedMessages(vm))

            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals(
                "the member quoted as free was paid on-chain when the relay publish failed",
                1,
                f.seam.proposalRequests.get(),
            )
            assertTrue(
                "the failed free invite must be shown as failed",
                PaidGroupFixture.MEMBER_B in vm.createGroupState.value.failedInvites,
            )
        }

    /** Control (passes before and after): the free delivery works, so only the quoted on-chain invite is paid. */
    @Test
    fun `control - a member reached free over Nostr costs nothing and the quoted invite is paid`() =
        runBlocking {
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_B))
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic") }
            val vm = viewModel(f)
            f.awaitNewJobs(vm) {
                vm.setGroupName("Synthetic relay group")
                vm.toggleMemberSelection(PaidGroupFixture.MEMBER_A)
                vm.toggleMemberSelection(PaidGroupFixture.MEMBER_B)
                vm.createGroup()
            }
            assertEquals(1, quotedMessages(vm))

            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals(1, f.seam.proposalRequests.get())
            assertEquals(emptyList<String>(), vm.createGroupState.value.failedInvites)
        }
}
