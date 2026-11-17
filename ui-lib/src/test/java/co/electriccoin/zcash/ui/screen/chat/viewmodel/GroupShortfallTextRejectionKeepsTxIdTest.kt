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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C4 (round 7 adversarial review of B1, item 4), the group side — a key memo the SDK stored and the server
 * then rejected with a shortfall-looking text reaches the group code as a cause-less InsufficientFundsException.
 *
 * C1 already keeps such a memo MAYBE_SENT (an insufficient-funds error after the submit started proves nothing),
 * but the stored transaction id is lost on the way: the use case rewraps the rejection, so `storedTxIdsOf` finds
 * no SubmitRejectedException and the member's roster row gets no id to reconcile against later. With a plain
 * rejection text the id is kept (GroupMemoPreSubmitRefusalIsNothingSentTest); it must be kept whatever the
 * server's text says.
 *
 * All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupShortfallTextRejectionKeepsTxIdTest {
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

    @Test
    fun `a rotation whose key memo was stored then rejected with a shortfall text keeps the txid and pays nothing more`() =
        runBlocking {
            val f = GroupPaidOnceFixture(firstSubmitOutcome = shortfallTextRejection(), consent = true)
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.rotateGroupKey(GroupPaidOnceFixture.GROUP_ID) }
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("fixture: the rotation committed epoch 2", 2, f.epoch())
            assertEquals("a stored transaction must never be paid again", 1, f.submits())
            assertNull("a stored transaction must not be written as owed", f.memberRow().pendingKeyEpoch)
            assertTrue(
                "the stored transaction id was lost because the rejection was rewrapped as a shortfall: ${f.rosterJson()}",
                f.rosterJson()!!.contains(STORED_T),
            )
        }

    @Test
    fun `an owed-key repair stored then rejected with a shortfall text keeps the txid on the member's row`() =
        runBlocking {
            val f =
                GroupPaidOnceFixture(
                    memberPendingKeyEpoch = 1,
                    firstSubmitOutcome = shortfallTextRejection(),
                    consent = true,
                )
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }

            assertEquals(1, f.submits())
            assertNull(f.memberRow().pendingKeyEpoch)
            assertTrue(
                "the stored transaction id was lost because the rejection was rewrapped as a shortfall: ${f.rosterJson()}",
                f.rosterJson()!!.contains(STORED_T),
            )
        }

    private fun shortfallTextRejection(): SubmitResult =
        SubmitResult.Failure(
            txIds = listOf(STORED_T),
            code = -26,
            description = "Insufficient balance (synthetic rejection text)",
        )

    private companion object {
        const val STORED_T = "synthetic-stored-T"
    }
}
