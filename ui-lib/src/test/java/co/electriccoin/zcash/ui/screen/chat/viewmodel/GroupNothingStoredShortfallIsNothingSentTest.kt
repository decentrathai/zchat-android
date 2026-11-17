package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.model.SubmitResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D1 (REVIEW_7C F1 + F8) — the group side. The SDK reports a shortfall while it builds the transaction, after
 * onSubmitStarting ran: `Failure(txIds = [], code = 0, "Insufficient balance (have 0, need 17000 including fee)")`.
 * That proves nothing was stored. The use case rethrows it as a cause-less InsufficientFundsException, and
 * sendGroupMemoWithRetry, which since C1 trusts only a nothing-stored rejection after the submit, calls it
 * MAYBE_SENT (before C1 a shortfall was nothing sent, so this is a regression):
 *
 * - a group message says "may have been sent", clears the draft and offers no retry;
 * - a rotation writes the member as unconfirmed (not owed), so the key repair never pays the key they never got.
 *
 * Wanted: nothing sent, as it was proven.
 *
 * All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupNothingStoredShortfallIsNothingSentTest {
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

    private suspend fun viewModel(f: GroupPaidOnceFixture): GroupViewModel {
        f.scheduler = scheduler
        return f.groupViewModel().also {
            vms += it
            it.consumeGroupSendEvent()
        }
    }

    private fun shortfallAfterSubmit(): SubmitResult =
        SubmitResult.Failure(txIds = emptyList(), code = 0, description = "Insufficient balance (have 0, need 17000 including fee)")

    @Test
    fun `a group message the SDK fell short on after the submit started failed, keeps its draft and is not may-have-been-sent`() =
        runBlocking {
            val f = GroupPaidOnceFixture(firstSubmitOutcome = shortfallAfterSubmit(), consent = true)
            f.proxy.setGroupDraft(GroupPaidOnceFixture.GROUP_ID, "hello group")
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(GroupPaidOnceFixture.GROUP_ID, "hello group") }
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("fixture: one submit reached the SDK", 1, f.submits())
            val event = vm.groupSendEvent.value
            assertFalse(
                "the SDK stored nothing, but the screen says the message may have been sent: $event",
                event is GroupSendResult.MaybeSent,
            )
            assertTrue("the user must be told the message was not sent: $event", event is GroupSendResult.AllFailed)
            assertEquals("the typed text was deleted although nobody received it", "hello group", f.draft())
        }

    @Test
    fun `a rotation whose key memo the SDK fell short on after the submit started leaves the member owed, not unconfirmed`() =
        runBlocking {
            val f = GroupPaidOnceFixture(firstSubmitOutcome = shortfallAfterSubmit(), consent = true)
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.rotateGroupKey(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals("fixture: the rotation quotes one on-chain message", 1, vm.paidActionConfirmation.value?.onChainMessages)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("fixture: the rotation committed epoch 2", 2, f.epoch())
            val event = vm.groupSendEvent.value
            assertTrue(
                "the admin must be warned that the new key did not reach everyone: $event",
                event is GroupSendResult.RemovalIncomplete,
            )
            event as GroupSendResult.RemovalIncomplete
            assertEquals(
                "the SDK stored nothing, so the member is owed the new key, not counted as unconfirmed: $event",
                1 to 0,
                event.pendingMembers to event.unconfirmedMembers,
            )
            assertEquals(
                "the member never got the new key; the follow-up repair must pay it exactly once",
                2,
                f.submits(),
            )
            assertNull("no stored transaction exists for the member's key memo", f.memberRow().pendingKeyTxIds)
        }
}
