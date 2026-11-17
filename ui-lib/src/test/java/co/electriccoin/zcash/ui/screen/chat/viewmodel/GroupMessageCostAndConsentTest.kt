package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * R5-1 (round 4 report §5B, first bullet) — a group message is the largest unquoted group spend.
 *
 * `sendGroupMessage` fans one on-chain memo out to every non-Nostr ACTIVE member (about 17,000 zatoshi
 * each) with no quote, no prompt and no consent of any kind: the six group *controls* were bound to a
 * quote in R4-2, but the message the user types was left out. The only cost surface is a grey hint in the
 * composer that counts *every* active member — including the ones reachable free over the relays — so it
 * never says what this message costs right now, and it shows no total at all.
 *
 * Wanted:
 *  - the composer has a cost line built from the SAME recipient list and the SAME per-memo sizer as the
 *    send itself (a group memo is a rawMemo: exactly one 512-byte memo output per recipient, so
 *    `onChainSendMinimumCost()` each), counting only the members this message would really pay for;
 *  - one consent per group, remembered — not one per message;
 *  - the consent is asked for again when the paying set grows;
 *  - and, as for every R4-2 control, a member the quote counted as free is never paid for on-chain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMessageCostAndConsentTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()

    // sendGroupMessage pauses 500 ms between members; virtual time is advanced while a test waits.
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
    private fun relayAcksNothing() =
        NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 0, messageId = null) }

    private suspend fun viewModel(f: PaidGroupFixture): GroupViewModel {
        f.scheduler = scheduler
        return f.groupViewModel().also { vms += it }
    }

    private fun quotedMessages(vm: GroupViewModel): Int? = vm.paidActionConfirmation.value?.onChainMessages

    private fun quotedTotal(vm: GroupViewModel): Long? =
        vm.paidActionConfirmation.value
            ?.totalCost
            ?.value

    /**
     * The composer's cost line, reached by name so this test compiles against a build that has none:
     * how many members the message goes to, how many of those are paid on-chain right now, and the total.
     */
    private fun costLine(
        vm: GroupViewModel,
        groupId: String
    ): Triple<Int, Int, Long> {
        val method =
            GroupViewModel::class.java.methods.firstOrNull { it.name == "groupMessageCost" }
                ?: run {
                    fail("the group composer has no cost surface: GroupViewModel exposes no groupMessageCost(groupId)")
                    error("unreachable")
                }
        val value =
            method.invoke(vm, groupId) ?: run {
                fail("groupMessageCost returned nothing for $groupId")
                error("unreachable")
            }
        val recipients = value.javaClass.getMethod("getRecipients").invoke(value) as Int
        val onChain = value.javaClass.getMethod("getOnChainMessages").invoke(value) as Int
        val total = value.javaClass.getMethod("getTotalCost").invoke(value)
        return Triple(recipients, onChain, total.javaClass.getMethod("getValue").invoke(total) as Long)
    }

    // ---- the cost surface -----------------------------------------------------------------------

    @Test
    fun `the composer says what one message to this group costs on-chain right now`() =
        runBlocking {
            // B is reachable free over the relays, A is not: this message pays for ONE member.
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_B))
            relayAcksNothing()
            val vm = viewModel(f)

            val (recipients, onChain, total) = costLine(vm, PaidGroupFixture.GROUP_ID)
            assertEquals("the message goes to both other members", 2, recipients)
            assertEquals("only the member with no free route is paid for", 1, onChain)
            assertEquals(
                "the total must be the paid members times one rawMemo output (17,000 zatoshi each)",
                17_000L,
                total,
            )
        }

    @Test
    fun `the cost line is zero when every member is reachable free`() =
        runBlocking {
            val f =
                PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_A, PaidGroupFixture.MEMBER_B))
            relayAcksNothing()
            val vm = viewModel(f)

            val (recipients, onChain, total) = costLine(vm, PaidGroupFixture.GROUP_ID)
            assertEquals("the message still goes to both members", 2, recipients)
            assertEquals("neither member is paid for on-chain", 0, onChain)
            assertEquals("a message that pays for nobody must not be quoted a cost", 0L, total)
        }

    // ---- consent --------------------------------------------------------------------------------

    @Test
    fun `a group message pays nothing until its cost is confirmed`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "R5-1 first message") }

            assertEquals(
                "the group message fanned out on-chain with no cost prompt on screen",
                0,
                f.seam.proposalRequests.get(),
            )
            assertNotNull("the user must be asked before a group message spends", vm.paidActionConfirmation.value)
            assertEquals("both members are paid for", 2, quotedMessages(vm))
            assertTrue(
                "the quoted total (${quotedTotal(vm)}) is below 2 x 17,000 zatoshi",
                (quotedTotal(vm) ?: 0L) >= 34_000L,
            )

            f.awaitNewJobs(vm) { vm.confirmPaidAction() }
            assertEquals("confirming sends exactly what was quoted", 2, f.seam.proposalRequests.get())
        }

    @Test
    fun `the consent is remembered for the group, not asked again for the next message`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "first") }
            assertNotNull("fixture: the first message is quoted", vm.paidActionConfirmation.value)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }
            assertEquals("fixture: the first message went to both members", 2, f.seam.proposalRequests.get())

            // A SECOND ViewModel, as a fresh screen (or a restart) would be: the consent is remembered
            // for the group, so this message is not quoted again.
            val next = viewModel(f)
            f.awaitNewJobs(next) { next.sendGroupMessage(PaidGroupFixture.GROUP_ID, "second") }

            assertNull(
                "the consent is per group and was already given — the next message must not ask again",
                next.paidActionConfirmation.value,
            )
            assertEquals("the second message must still be sent", 4, f.seam.proposalRequests.get())
        }

    @Test
    fun `the consent is asked for again when the paying set grows`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "first") }
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }
            assertEquals("fixture: the consent covered A and B", 2, f.seam.proposalRequests.get())

            // A third member joins. The user consented to paying for two, never for three.
            f.addActiveMember(PaidGroupFixture.MEMBER_C)
            val next = viewModel(f)
            f.awaitNewJobs(next) { next.sendGroupMessage(PaidGroupFixture.GROUP_ID, "second") }

            assertEquals(
                "a member who joined after the consent was paid for without being quoted",
                2,
                f.seam.proposalRequests.get(),
            )
            assertNotNull("the grown paying set must be quoted again", next.paidActionConfirmation.value)
            assertEquals("the new quote covers all three members", 3, quotedMessages(next))

            f.awaitNewJobs(next) { next.confirmPaidAction() }
            assertEquals("confirming the new quote sends to all three", 5, f.seam.proposalRequests.get())
        }

    // ---- a member quoted as free is never paid for ----------------------------------------------

    @Test
    fun `a member the quote counted as free is never paid for when the relay fails`() =
        runBlocking {
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_B))
            relayAcksNothing()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "R5-1") }
            assertEquals("fixture: only A is quoted on-chain", 1, quotedMessages(vm))

            f.awaitNewJobs(vm) { vm.confirmPaidAction() }
            assertEquals(
                "the member the prompt counted as free was paid for on-chain when the relay failed",
                1,
                f.seam.proposalRequests.get(),
            )
        }

    // ---- the typed text is never only in the composer ---------------------------------------------

    @Test
    fun `the typed text is saved before the cost prompt goes up`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "R5-1 text that must survive") }

            assertNotNull("fixture: the send is waiting on the cost prompt", vm.paidActionConfirmation.value)
            // The composer stops saving drafts the moment Send is tapped and hands the text to the
            // ViewModel. The quote can sit on screen indefinitely, so backing out of it — or the process
            // dying under it — must not take the message with it.
            assertEquals(
                "the typed text was lost while the cost prompt was on screen",
                "R5-1 text that must survive",
                f.draft(PaidGroupFixture.GROUP_ID),
            )
        }

    @Test
    fun `a delivered message still clears the draft`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "delivered") }
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("fixture: the message went out", 2, f.seam.proposalRequests.get())
            assertNull(
                "a message that was delivered must not be left in the composer as a draft",
                f.draft(PaidGroupFixture.GROUP_ID),
            )
        }

    // ---- controls (must pass before and after) ---------------------------------------------------

    @Test
    fun `control - when every member is reachable free nothing is quoted and nothing is paid`() =
        runBlocking {
            val f =
                PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_A, PaidGroupFixture.MEMBER_B))
            // ZC-972: the relays take every copy here, so the message really is free. When they take none, the
            // members they missed are asked about (GroupMessageRelaysMissedAsksTheCostTest).
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 1, messageId = "zc972-control") }
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "free for everyone") }

            assertNull("nothing is paid for, so nothing may be quoted", vm.paidActionConfirmation.value)
            assertEquals("a message nobody is paid for must never spend", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `control - a blank message still sends nothing and asks nothing`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "   ") }

            assertNull("a blank message must not raise a cost prompt", vm.paidActionConfirmation.value)
            assertEquals("a blank message must not spend", 0, f.seam.proposalRequests.get())
        }
}
