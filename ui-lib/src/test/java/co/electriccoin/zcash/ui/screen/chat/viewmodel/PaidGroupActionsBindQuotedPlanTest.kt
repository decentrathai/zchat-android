package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * R4-2 (round 3 report §4 item 3) — the other group controls still pay on-chain for a member the prompt
 * quoted as free. GPT-6 F2 bound only `createGroup`.
 *
 * `askBeforePaying` counts the members that are NOT reachable free over Nostr right now, quotes that many
 * on-chain messages (none at all, and no prompt, when every member is reachable free) and then hands the
 * whole action back to the same code path that pays. That path calls `sendGroupMemoWithRetry` with the
 * default `mayPayOnChain = { true }`: when the free Nostr delivery then fails — the relay acks nothing,
 * the publisher went away — the member the quote treated as free is paid for on-chain, about 17,000
 * zatoshi that the user was never shown. Resend, add, kick, rotate, key sync and leave all do this.
 *
 * Wanted, exactly as for create: Confirm runs the plan that was quoted. A member quoted as free is never
 * paid on-chain under that quote (the delivery is recorded as failed instead, and the user can retry from
 * a fresh quote), and when the roster changed after the quote nothing is sent and the new plan is quoted
 * again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaidGroupActionsBindQuotedPlanTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()

    // leaveGroup pauses 500 ms between members; virtual time is advanced while a test waits.
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

    /** Every Nostr publish fails to be acked, so the "free" route never delivers. */
    private fun relayAcksNothing() = NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 0, messageId = null) }

    private suspend fun viewModel(f: PaidGroupFixture): GroupViewModel {
        f.scheduler = scheduler
        return f.groupViewModel().also { vms += it }
    }

    private fun quotedMessages(vm: GroupViewModel): Int? = vm.paidActionConfirmation.value?.onChainMessages

    /**
     * Quote, Confirm, and assert the action paid exactly the [quoted] on-chain messages it showed — not one
     * more for a member the quote called free.
     */
    private suspend fun quoteThenConfirm(
        f: PaidGroupFixture,
        quoted: Int,
        action: (GroupViewModel) -> Unit,
    ) {
        val vm = viewModel(f)
        f.awaitNewJobs(vm) { action(vm) }
        assertEquals("fixture: the on-chain messages the prompt quotes", quoted, quotedMessages(vm))
        f.awaitNewJobs(vm) { vm.confirmPaidAction() }
        assertEquals(
            "a member the prompt quoted as free was paid for on-chain when the free delivery failed",
            quoted,
            f.seam.proposalRequests.get(),
        )
    }

    /**
     * Run an action whose every recipient is reachable free, so nothing is quoted and no prompt is shown,
     * and assert it paid nothing when the free delivery failed.
     */
    private suspend fun runUnquoted(
        f: PaidGroupFixture,
        action: (GroupViewModel) -> Unit,
    ) {
        val vm = viewModel(f)
        f.awaitNewJobs(vm) { action(vm) }
        assertNull("fixture: everyone is reachable free, so nothing is quoted", vm.paidActionConfirmation.value)
        assertEquals(
            "a member nothing was quoted for was paid on-chain when the free delivery failed",
            0,
            f.seam.proposalRequests.get(),
        )
    }

    private fun memberStatus(
        f: PaidGroupFixture,
        address: String,
    ): InviteStatus? =
        ZMSGGroupProtocol
            .deserializeGroupMembers(f.rosterJson())
            .firstOrNull { it.address == address }
            ?.inviteStatus

    // ---- item 1: resend invite ------------------------------------------------------------------

    @Test
    fun `re-sending an invite to a member reachable free never falls back to a paid send`() =
        runBlocking {
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_A))
            relayAcksNothing()
            runUnquoted(f) { it.resendInvite(PaidGroupFixture.GROUP_ID, PaidGroupFixture.MEMBER_A) }
            assertEquals(
                "the failed free resend must be recorded as failed, not silently paid",
                InviteStatus.FAILED,
                memberStatus(f, PaidGroupFixture.MEMBER_A),
            )
        }

    // ---- item 2: add member ---------------------------------------------------------------------

    @Test
    fun `adding a member reachable free never falls back to a paid invite`() =
        runBlocking {
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_C))
            relayAcksNothing()
            runUnquoted(f) { it.addMemberToGroup(PaidGroupFixture.GROUP_ID, PaidGroupFixture.MEMBER_C) }
            assertEquals(
                "the failed free invite must be recorded as failed, not silently paid",
                InviteStatus.FAILED,
                memberStatus(f, PaidGroupFixture.MEMBER_C),
            )
        }

    // ---- item 3: kick ---------------------------------------------------------------------------

    @Test
    fun `kicking pays only for the remaining members the prompt quoted`() =
        runBlocking {
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_C))
            f.addActiveMember(PaidGroupFixture.MEMBER_C)
            relayAcksNothing()
            // Remaining key holders after kicking B: A (on-chain) and C (quoted free). Round 8 P6 (PHONE_TRIAGE F5b): B,
            // who has a session and no relay key, is also quoted its on-chain removal notice (no key).
            quoteThenConfirm(f, quoted = 2) { it.kickMember(PaidGroupFixture.GROUP_ID, PaidGroupFixture.MEMBER_B) }
            assertEquals(
                "the kicked member must still be removed locally",
                MemberStatus.LEFT,
                ZMSGGroupProtocol
                    .deserializeGroupMembers(f.rosterJson())
                    .first { it.address == PaidGroupFixture.MEMBER_B }
                    .status,
            )
        }

    // ---- item 4: rotate key ---------------------------------------------------------------------

    @Test
    fun `rotating the key pays only for the members the prompt quoted`() =
        runBlocking {
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_B))
            relayAcksNothing()
            quoteThenConfirm(f, quoted = 1) { it.rotateGroupKey(PaidGroupFixture.GROUP_ID) }
        }

    @Test
    fun `a member rostered after the rotation quote is not paid under it, and Confirm asks again`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)
            f.awaitNewJobs(vm) { vm.rotateGroupKey(PaidGroupFixture.GROUP_ID) }
            assertEquals("fixture: the quote covers A and B", 2, quotedMessages(vm))

            f.addActiveMember(PaidGroupFixture.MEMBER_C)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals(
                "Confirm paid for a member rostered after the two-message quote was shown",
                0,
                f.seam.proposalRequests.get(),
            )
            assertNotNull("the changed plan must be quoted again", vm.paidActionConfirmation.value)
            assertEquals("the new quote covers all three members", 3, quotedMessages(vm))
        }

    // ---- item 5: key sync -----------------------------------------------------------------------

    @Test
    fun `an admin key-sync pays only for the owed members the prompt quoted`() =
        runBlocking {
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_B), owedA = true, owedB = true)
            relayAcksNothing()
            quoteThenConfirm(f, quoted = 1) { it.requestGroupKeySync(PaidGroupFixture.GROUP_ID) }
        }

    // ---- item 6: leave --------------------------------------------------------------------------

    @Test
    fun `leaving pays only for the members the prompt quoted`() =
        runBlocking {
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_B))
            relayAcksNothing()
            quoteThenConfirm(f, quoted = 1) { it.leaveGroup(PaidGroupFixture.GROUP_ID) }
        }

    // ---- controls (pass before and after) -------------------------------------------------------

    @Test
    fun `control - a working free relay costs nothing and the quoted on-chain messages are still paid`() =
        runBlocking {
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_B))
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic") }
            quoteThenConfirm(f, quoted = 1) { it.rotateGroupKey(PaidGroupFixture.GROUP_ID) }
        }

    @Test
    fun `control - with no free route at all every quoted member is still paid`() =
        runBlocking {
            val f = PaidGroupFixture()
            quoteThenConfirm(f, quoted = 2) { it.leaveGroup(PaidGroupFixture.GROUP_ID) }
        }
}
