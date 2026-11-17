package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptProcess
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.ChainTx
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
 * F4 (fix-all 2.13.0, train T4; maybe-sent review point 1; SPECS §1.2 "releaseAnchor", §1.8 item 5): a row with no id
 * is judged against its release anchor, the first network height a pass reads AFTER the row left the submit path. A
 * height read while the send still runs in this process is not an upper bound (create runs after the marker), so it is
 * never written; the anchor is written once, and "not sent" comes only at anchor + 52, over two rising passes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseAnchorIsFirstNetworkHeightAfterSettleTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4anchorpeer000001"
        const val OTHER = "usyntheticf4anchorother00001"
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
    fun `the anchor is the first network height after the send left this process`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val rowId = SendAttemptHarness.newRowId()
            prefs.pending[rowId] = SendAttemptHarness.possiblySentRow(rowId, PEER, "synthetic anchor hello", emptyList(), H)
            val unrelated = ChainTx(21, OTHER, memo = null, minedHeight = H - 500, expiryHeight = H - 460)
            val wallet = Wallet(scanned = H, txs = listOf(unrelated))
            val vm = SendAttemptHarness.openChat(prefs, seam, wallet)

            SendAttemptProcess.enter(rowId)
            try {
                pass(vm, wallet, H)
                pass(vm, wallet, H + 10)
                assertNull("an anchor was written while the send still runs here", prefs.pending.getValue(rowId).releaseAnchorHeight)
            } finally {
                SendAttemptProcess.leave(rowId)
            }

            pass(vm, wallet, H + 20)
            assertEquals(
                "the anchor is the first network height after the send left",
                H + 20,
                prefs.pending.getValue(rowId).releaseAnchorHeight
            )
            wallet.network = H + 15
            SendAttemptHarness.runPass(vm, wallet)
            assertEquals("the anchor moved", H + 20, prefs.pending.getValue(rowId).releaseAnchorHeight)

            for (tip in listOf(H + 71, H + 71)) {
                pass(vm, wallet, tip)
                assertFalse("Retry was accepted before anchor + 52 (tip $tip)", vm.retryMessage(PEER, rowId))
                awaitIdle(vm)
            }
            pass(vm, wallet, H + 72)
            pass(vm, wallet, H + 73)
            assertTrue("Retry stayed refused after anchor + 52 on two passes", vm.retryMessage(PEER, rowId))
            awaitIdle(vm)
            assertEquals("exactly one new attempt", 1, seam.proposalUris.size)
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
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
