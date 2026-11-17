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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1998 (Fable review of fix/2130-msg at c5d86ba62, finding 1; money-path rule 2) — a first message to a new contact
 * failed in its key exchange's build, so its row is FAILED, still visible, and the KEX is owed for it. A block starts its own
 * owed-KEX attempt (payOwedKex(forSend = false)): the row is not deleted, so the ZC-1992 guard lets it through and the KEX is
 * built. The user deletes the message while that build runs: Delete clears the mark (cancelKexOwedOnlyFor), but the attempt
 * never looks again (rowDeleted is null for a block's attempt; beforeAttempt and beforeSubmit read neither the mark nor the
 * rows), so the KEX is submitted for a contact whose only message is gone.
 *
 * Wanted: 0 submits, then and after later blocks. Control: with a second, live unsent message of the contact the same delete
 * keeps the mark and the in-flight KEX is still paid. Real ChatViewModel send, block tick and delete over
 * [FundsConfirmingChat]; every address, key and txid is synthetic. Not pinned here: the same delete during the attempt's
 * block wait after a transient "funds confirming" failure (the fixture has no synchronizer height to wait on).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockOwedKexInFlightStopsWhenItsOnlyMessageIsDeletedTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1998blockkexinflight01"
        const val TYPED = "synthetic first words failed then deleted mid build"
        const val BLOCKS = 2

        /** Build 1 was the first message's failed key exchange; build 2 is the block's owed key exchange. */
        const val BLOCK_KEX_BUILD = 2
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
    fun `control - a block's key exchange in flight is still paid when another unsent message of the contact stays`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER).apply { plan = "Fundable" }
            val (vm, rowId) = firstMessageFailedInItsKex(world, TYPED)
            val stored = world.chat.storedRow(rowId)!!
            world.chat.prefs.commitPendingMessage(stored.copy(id = "pending_${System.nanoTime()}", text = "synthetic second words"))
            try {
                blockKexInItsBuild(world, vm)
                vm.hideMessage(rowId)
                assertTrue("fixture: Delete took the mark another unsent message still needs", PEER in world.chat.kexOwed)
                world.funds.buildGate.complete(Unit)
                assertTrue(
                    "a block's key exchange was not paid although the contact has a live unsent message " +
                        "(${world.seam.submits.get()} submits)",
                    PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= 1 },
                )
            } finally {
                world.funds.buildGate.complete(Unit)
            }
        }

    @Test
    fun `a block's key exchange in its build pays nothing once the user deletes the contact's only message`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER).apply { plan = "Fundable" }
            val (vm, rowId) = firstMessageFailedInItsKex(world, TYPED)
            try {
                blockKexInItsBuild(world, vm)
                vm.hideMessage(rowId)
                assertFalse("fixture: Delete did not clear the owed-KEX mark", PEER in world.chat.kexOwed)
                world.funds.buildGate.complete(Unit)
                world.paymentsSettled()
                assertEquals(
                    "a block's key exchange already in its build was submitted after the user deleted the contact's only message",
                    0,
                    world.seam.submits.get(),
                )
                repeat(BLOCKS) {
                    world.block(vm)
                    world.paymentsSettled()
                }
                assertEquals("a later block paid for the deleted message", 0, world.seam.submits.get())
            } finally {
                world.funds.buildGate.complete(Unit)
            }
        }

    /** A block starts the owed KEX's attempt; returns once its build ([BLOCK_KEX_BUILD]) is held open. */
    private suspend fun blockKexInItsBuild(
        world: FundsConfirmingChat,
        vm: ChatViewModel,
    ) {
        world.funds.parkBuild = BLOCK_KEX_BUILD
        world.block(vm)
        withTimeout(HandshakeHarness.TIMEOUT_MS) { world.funds.buildEntered.await() }
        assertEquals("fixture: the block paid before its build was held", 0, world.seam.submits.get())
    }
}
