package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1975 (Fable review of fix/2130-msg, 16 Nov 2026; register high; money-path rule 2) — a first message waiting for its
 * funds is resumed at a block and its send pays our key exchange first (payOwedKex). ZC-1959's last re-read of the row comes
 * before payOwedKex; inside it the owed mark is read once, and neither the attempt gate nor the gate before the submit reads
 * the row again. So a Delete that lands while the KEX's proposal is being built (the row and the owed mark go) is not seen:
 * the build returns and the KEX is submitted for a message that no longer exists.
 *
 * Wanted: a row deleted while its KEX is built pays nothing: no submit, now or at a later block. The control shows the same
 * held build with no delete pays the KEX (the fixture reaches the build that is held). Real ChatViewModel send, block tick
 * and delete over [FundsConfirmingChat]; every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteDuringTheResumedKexBuildPaysNoKexTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1975deletekexbuild01"
        const val TYPED = "synthetic first words deleted while the key exchange builds"
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
    fun `control - with no delete the held key exchange build pays the key exchange`() =
        runBlocking {
            val (world, vm) = resumedRowHeldInItsKexBuild()
            try {
                world.funds.firstBuildGate.complete(Unit)
                assertTrue(
                    "fixture: the resumed row paid nothing (${HandshakeHarness.sendMessageState(vm)})",
                    PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= 1 },
                )
            } finally {
                world.funds.firstBuildGate.complete(Unit)
            }
        }

    @Test
    fun `a row deleted while its resumed key exchange is built pays no key exchange`() =
        runBlocking {
            val (world, vm) = resumedRowHeldInItsKexBuild()
            try {
                val rowId = world.row()!!.id
                vm.hideMessage(rowId)
                assertNull("fixture: the deleted row is still on disk", world.row())
                world.funds.firstBuildGate.complete(Unit)
                world.paymentsSettled()

                assertEquals(
                    "a key exchange was paid for a message the user deleted while it was built",
                    0,
                    world.seam.submits.get(),
                )
                repeat(BLOCKS) {
                    world.block(vm)
                    world.paymentsSettled()
                }
                assertEquals("a later block paid for the deleted message", 0, world.seam.submits.get())
            } finally {
                world.funds.firstBuildGate.complete(Unit)
            }
        }

    /**
     * Pays a first message while its funds confirm (a waiting row, nothing built), then a block resumes it with the plan
     * Fundable: its send passes every ZC-1959 re-read and parks in the first proposal build, its key exchange's.
     */
    private suspend fun resumedRowHeldInItsKexBuild(): Pair<FundsConfirmingChat, ChatViewModel> {
        val world = FundsConfirmingChat(PEER)
        val vm = world.screen().also(::canHideMessages)
        val row = world.acceptWhileConfirming(vm, TYPED)
        assertEquals("fixture: the Pay paid while the funds confirm", 0, world.seam.submits.get())
        assertEquals("fixture: the waiting Pay built a proposal", 0, world.seam.proposalRequests.get())
        world.funds.parkFirstBuild = true

        world.plan = "Fundable"
        world.block(vm)
        withTimeout(HandshakeHarness.TIMEOUT_MS) { world.funds.firstBuildEntered.await() }
        assertTrue("fixture: the parked build is not the resumed row's send", LiveSenders.isLive(row.id))
        assertTrue("fixture: the parked build is not the key exchange (no owed mark)", PEER in world.chat.kexOwed)
        assertEquals("fixture: something was paid before the build answered", 0, world.seam.submits.get())
        return world to vm
    }
}
