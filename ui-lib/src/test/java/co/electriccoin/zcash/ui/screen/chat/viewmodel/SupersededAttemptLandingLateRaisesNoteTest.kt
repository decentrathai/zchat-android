package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.ChainTx
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.txId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F4 (fix-all 2.13.0, train T4; maybe-sent design 5; SPECS §1.3 R8): if the attempt a Retry replaced is ever seen
 * mined (a reorg, or a proof that was wrong), the chat says so once: the contact may receive the message twice.
 *
 * After a proven expiry and one Retry, the old transaction id is kept on the row as superseded. When a later pass sees
 * it mined, one system note appears in that chat (its id holds a short hash of the txid, so a second pass does not add
 * another) and nothing is paid. At HEAD the Retry itself is refused, so no note can ever be raised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupersededAttemptLandingLateRaisesNoteTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4latepeer00000001"
        const val EXPIRY = H + 40
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a superseded attempt seen mined raises one note and pays nothing`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val rowId = SendAttemptHarness.newRowId()
            prefs.pending[rowId] = SendAttemptHarness.possiblySentRow(rowId, PEER, "synthetic late hello", listOf(txId(1)), H)
            val wallet = Wallet(scanned = H, txs = listOf(ChainTx(1, PEER, null, minedHeight = null, expiryHeight = EXPIRY)))
            val vm = SendAttemptHarness.openChat(prefs, seam, wallet)
            pass(vm, wallet, EXPIRY + 10)
            pass(vm, wallet, EXPIRY + 11)
            assertTrue("fixture: the proven expiry must allow one Retry", vm.retryMessage(PEER, rowId))
            awaitIdle(vm)
            assertEquals(1, seam.proposalUris.size)

            // The old attempt lands after all.
            wallet.txs = listOf(ChainTx(1, PEER, null, minedHeight = EXPIRY - 1, expiryHeight = EXPIRY))
            pass(vm, wallet, EXPIRY + 12)
            pass(vm, wallet, EXPIRY + 13)

            val notes = prefs.pending.keys.filter { SysNotes.isSystemNoteId(it) }
            assertEquals("the late landing must raise exactly one note: $notes", 1, notes.size)
            assertEquals("the note must be in that chat", PEER, prefs.pending.getValue(notes.single()).peerAddress)
            assertEquals("a late landing must never pay", 1, seam.proposalUris.size)
        }

    private suspend fun pass(
        vm: ChatViewModel,
        wallet: Wallet,
        tip: Long,
    ) {
        wallet.scanned = tip
        wallet.network = tip
        SendAttemptHarness.runPass(vm, wallet)
    }
}
