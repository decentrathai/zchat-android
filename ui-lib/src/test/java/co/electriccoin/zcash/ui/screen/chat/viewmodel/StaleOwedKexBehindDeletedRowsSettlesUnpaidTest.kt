package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1999 (Fable review of fix/2130-msg at c5d86ba62, finding 2; money-path rule 2) — an owed-KEX mark outlived the delete
 * of its contact's only first message (Delete could not read the hidden list, so cancelKexOwedOnlyFor kept it). The KEX now
 * needs no payment (owedKexWouldPay is false): the contact's own key exchange completed the exchange, or a KEX carrying our
 * key was already submitted (ZC-626, its paid record on disk). Before ZC-1992 the next block settled such a mark unpaid and
 * cleared it; now the guard (owedKexOnlyForDeletedRows) returns Busy ahead of the settle, every block, so the mark stands for
 * good: the contact takes the one-per-block owed-KEX slot each rotation, and a quoted send to it is refused as changed.
 *
 * Wanted: after the blocks nothing is paid and the mark is cleared. Real ChatViewModel send, delete and block tick over
 * [FundsConfirmingChat]; every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaleOwedKexBehindDeletedRowsSettlesUnpaidTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1999stalemarkdeleted01"
        const val TYPED = "synthetic first words failed then deleted"
        const val BLOCKS = 2
        const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L
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
    fun `a stale mark behind deleted rows is cleared unpaid once the contact's key exchange completed the exchange`() =
        runBlocking {
            val (world, vm, faults) = staleMarkBehindDeletedRow()
            faults.exchangeComplete = true
            assertClearedUnpaid(world, vm, "the exchange is complete")
        }

    @Test
    fun `a stale mark behind deleted rows is cleared unpaid once a key exchange of our key was already submitted`() =
        runBlocking {
            val (world, vm, _) = staleMarkBehindDeletedRow()
            val ourKey = world.chat.prefs.getE2EOurPublicKey(PEER)
            assertNotNull("fixture: the first message made no key of ours", ourKey)
            assertTrue(
                "fixture: the paid record was not saved",
                storeOf(vm).setHandshakePaidRecord(
                    HandshakePaidRecord.slot(AutomaticSetupKind.KEX, PEER),
                    HandshakePaidRecord.encode(ourKey!!, System.currentTimeMillis() - TWO_HOURS_MS),
                ),
            )
            assertClearedUnpaid(world, vm, "a key exchange of our key was already submitted (ZC-626)")
        }

    /** A first message failed in its KEX build and deleted while the hidden list could not be read: the mark stays. */
    private suspend fun staleMarkBehindDeletedRow(): Triple<FundsConfirmingChat, ChatViewModel, StoreFaults> {
        val world = FundsConfirmingChat(PEER).apply { plan = "Fundable" }
        val (vm, rowId) = firstMessageFailedInItsKex(world, TYPED)
        val faults = StoreFaults()
        vm.overStore(faults)
        faults.failing += GET_HIDDEN
        vm.hideMessage(rowId)
        faults.failing.clear()
        assertTrue("fixture: the delete did not reach the store", rowId in faults.hidden)
        assertTrue("fixture: Delete cleared the mark (no stale mark to settle)", PEER in world.chat.kexOwed)
        return Triple(world, vm, faults)
    }

    private suspend fun assertClearedUnpaid(
        world: FundsConfirmingChat,
        vm: ChatViewModel,
        why: String,
    ) {
        repeat(BLOCKS) {
            world.block(vm)
            world.paymentsSettled()
        }
        assertEquals("a block paid an owed key exchange that needs no payment ($why)", 0, world.seam.submits.get())
        assertFalse(
            "a stale owed-KEX mark behind deleted rows was never cleared although $why " +
                "(writes ${world.chat.kexOwedWrites})",
            PEER in world.chat.kexOwed,
        )
    }
}
