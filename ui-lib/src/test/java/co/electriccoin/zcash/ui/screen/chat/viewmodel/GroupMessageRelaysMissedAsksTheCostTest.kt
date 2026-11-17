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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-972 (train T6b phone run, F8-G W1 group copy) — a group message the user sends is refused with no price question
 * when the relays take no copy for a member the quote counted as free, and the screen blames the balance.
 *
 * On the phone (HONOR debug, offline, "Automatic setup payments" Off) the composer said the message was free ("every
 * member can be reached over the relays"): the relay publisher is registered while the phone is offline, so the quote
 * counts a member with a relay key as free. The relay copy then got no ack, the member was not in the quote's paid set,
 * so `sendGroupMemoWithRetry` logged "not paid on-chain (no spend consent)" and returned NOTHING_SENT, and the fan-out
 * ended in `AllFailed` with no refusal: "Message couldn't be delivered to anyone — check your balance and try again",
 * with a balance that was enough. The setting played no part: the group message path never reads it.
 *
 * Wanted: when the relays took no copy and nothing reached anyone, the members they missed are quoted as on-chain
 * messages and the user is asked (the price question of a group message); Cancel pays nothing and keeps the text; Pay
 * sends exactly the quoted copies. Nothing is paid before the tap, and the balance text is never the answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMessageRelaysMissedAsksTheCostTest {
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

    /** The phone is offline: the publisher is registered, but no relay takes a copy. */
    private fun relaysTakeNothing() =
        NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 0, messageId = null, readableAcks = 0) }

    private suspend fun viewModel(f: PaidGroupFixture): GroupViewModel {
        f.scheduler = scheduler
        return f.groupViewModel().also { vms += it }
    }

    /** Both members have a relay key, so the composer and the quote count them as free; automatic setup payments Off. */
    private fun freeLookingGroup() =
        PaidGroupFixture(
            nostrPeers = setOf(PaidGroupFixture.MEMBER_A, PaidGroupFixture.MEMBER_B),
            handshakeSpendConsent = false,
        )

    private fun assertNoBalanceText(vm: GroupViewModel) {
        val event = vm.groupSendEvent.value
        assertFalse(
            "the screen blames the balance (AllFailed with no refusal: \"check your balance and try again\") although " +
                "nothing was refused for money: the relays took no copy and the user was never asked to pay",
            event is GroupSendResult.AllFailed && event.refusal == null,
        )
    }

    @Test
    fun `a message the relays could not deliver to anyone asks the on-chain price instead of blaming the balance`() =
        runBlocking {
            val f = freeLookingGroup()
            relaysTakeNothing()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-972 offline group message") }

            assertEquals("nothing may be paid before the user answers a price question", 0, f.seam.proposalRequests.get())
            assertNoBalanceText(vm)
            val asked = vm.paidActionConfirmation.value
            assertNotNull(
                "the relays took no copy and the message reached nobody, but no price question was asked for the on-chain copies",
                asked,
            )
            assertEquals("the question counts the two members the relays missed", 2, asked!!.onChainMessages)
            assertTrue(
                "the quoted total (${asked.totalCost.value}) is below 2 x 17,000 zatoshi",
                asked.totalCost.value >= 34_000L,
            )
            assertEquals(
                "the typed text is kept while the question is open",
                "ZC-972 offline group message",
                f.draft(PaidGroupFixture.GROUP_ID)
            )
        }

    @Test
    fun `Cancel on that question pays nothing and keeps the text`() =
        runBlocking {
            val f = freeLookingGroup()
            relaysTakeNothing()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-972 cancel") }
            assertNotNull("no price question was asked after the relays took no copy", vm.paidActionConfirmation.value)

            f.awaitNewJobs(vm) { vm.dismissPaidAction() }

            assertEquals("Cancel must pay nothing", 0, f.seam.proposalRequests.get())
            assertNull("the question is gone after Cancel", vm.paidActionConfirmation.value)
            assertTrue(
                "Cancel answers \"not approved\", never a failure that blames the balance (was ${vm.groupSendEvent.value})",
                vm.groupSendEvent.value is GroupSendResult.CostDeclined,
            )
            assertEquals("the typed text is kept after Cancel", "ZC-972 cancel", f.draft(PaidGroupFixture.GROUP_ID))
        }

    @Test
    fun `Pay sends exactly the quoted on-chain copies`() =
        runBlocking {
            val f = freeLookingGroup()
            relaysTakeNothing()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-972 pay") }
            assertEquals("no price question was asked after the relays took no copy", 2, vm.paidActionConfirmation.value?.onChainMessages)

            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("Pay sends one on-chain copy to each member the question counted", 2, f.seam.proposalRequests.get())
            assertNull("one tap answers the question; nothing asks again", vm.paidActionConfirmation.value)
            assertNoBalanceText(vm)
        }

    @Test
    fun `control - with the relays taking every copy nothing is asked and nothing is paid`() =
        runBlocking {
            val f = freeLookingGroup()
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 1, messageId = "zc972") }
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-972 free") }

            assertNull("a message the relays delivered asks nothing", vm.paidActionConfirmation.value)
            assertEquals("a message the relays delivered pays nothing", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `control - a member quoted as paid next to one the relays missed is still never paid for the missed one`() =
        runBlocking {
            // A has no relay key (quoted as paid), B looks free and the relays miss it: A's copy goes on-chain after
            // Pay, B's does not, and no second question follows (the message reached someone).
            val f = PaidGroupFixture(nostrPeers = setOf(PaidGroupFixture.MEMBER_B), handshakeSpendConsent = false)
            relaysTakeNothing()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-972 mixed") }
            assertEquals("fixture: only A is quoted on-chain", 1, vm.paidActionConfirmation.value?.onChainMessages)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("only the quoted member is paid for", 1, f.seam.proposalRequests.get())
            assertNull("no second question after a message that reached a member", vm.paidActionConfirmation.value)
        }
}
