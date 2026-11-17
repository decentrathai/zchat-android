package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.FirstMessagePlan
import co.electriccoin.zcash.ui.screen.chat.model.FirstMessagePlanCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
 * ZC-1959 (Fable review of fix/2130-msg, 16 Nov 2026; register high; money-path rule 2) — a first message waiting for its
 * funds (no key of ours yet) is resumed at a block: doSendMessage(existingPendingId) suspends in its first-message plan
 * check, and meanwhile the user deletes the row (the row and its marks go). The run then wakes up, writes the owed-KEX
 * mark, sets kexFirst and pays one key exchange for a message that no longer exists.
 *
 * Wanted: a run whose row was deleted while its plan check ran pays nothing: no proposal, no submit, now or at a later
 * block. The control shows the same resume with no delete pays the KEX (the fixture reaches the paid path). Real
 * ChatViewModel send, block tick and delete over [FundsConfirmingChat]; every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteDuringTheResumedProbePaysNoKexTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1959deleteprobe01"
        const val TYPED = "synthetic first words deleted while probed"
        const val BLOCKS = 2
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
    }

    @Test
    fun `control - with no delete the resumed row pays its key exchange`() =
        runBlocking {
            val (world, vm, probe) = resumedRowHeldInItsProbe()
            probe.release.complete(Unit)
            assertTrue(
                "fixture: the resumed row paid nothing (${HandshakeHarness.sendMessageState(vm)})",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= 1 },
            )
        }

    @Test
    fun `a row deleted while its resumed send probes the plan pays no key exchange`() =
        runBlocking {
            val (world, vm, probe) = resumedRowHeldInItsProbe()
            val proposalsBefore = world.seam.proposalRequests.get()

            vm.hideMessage(probe.rowId)
            assertNull("fixture: the deleted row is still on disk", world.row())
            probe.release.complete(Unit)
            world.paymentsSettled()

            assertEquals("a key exchange was paid for a message the user deleted", 0, world.seam.submits.get())
            assertEquals(
                "a proposal was built for a message the user deleted",
                proposalsBefore,
                world.seam.proposalRequests.get(),
            )
            repeat(BLOCKS) {
                world.block(vm)
                world.paymentsSettled()
            }
            assertEquals("a later block paid for the deleted message", 0, world.seam.submits.get())
        }

    /** Pays a first message while its funds confirm (a waiting row), then a block resumes it and holds its send's probe. */
    private suspend fun resumedRowHeldInItsProbe(): Triple<FundsConfirmingChat, ChatViewModel, HeldProbe> {
        val world = FundsConfirmingChat(PEER)
        val vm = world.screen().also(::canHideMessages)
        val row = world.acceptWhileConfirming(vm, TYPED)
        assertEquals("fixture: the Pay paid while the funds confirm", 0, world.seam.submits.get())
        val probe = HeldProbe(row.id, world)
        FirstMessagePlanApi.install(vm, probe.check)

        world.plan = "Fundable"
        world.block(vm)
        assertTrue(
            "fixture: the resumed send never reached its plan check (${HandshakeHarness.sendMessageState(vm)})",
            PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { probe.entered.isCompleted },
        )
        assertEquals("fixture: something was paid before the probe answered", 0, world.seam.submits.get())
        return Triple(world, vm, probe)
    }

    /**
     * The plan check answers [FundsConfirmingChat.plan]; the call made while the row's send is live (doSendMessage's own
     * check, after the resume handed the row to it) waits until [release] completes.
     */
    private class HeldProbe(
        val rowId: String,
        private val world: FundsConfirmingChat,
    ) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val check =
            FirstMessagePlanCheck { _, _, _ ->
                if (LiveSenders.isLive(rowId) && !release.isCompleted) {
                    entered.complete(Unit)
                    release.await()
                }
                FirstMessagePlanApi.answer(world.plan) as FirstMessagePlan
            }
    }
}
