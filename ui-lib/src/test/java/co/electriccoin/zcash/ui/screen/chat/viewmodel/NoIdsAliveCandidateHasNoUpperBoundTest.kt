package co.electriccoin.zcash.ui.screen.chat.viewmodel

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
import org.junit.Before
import org.junit.Test

/**
 * F4 (fix-all 2.13.0, train T4; maybe-sent review point 1; SPECS §1.3 R5), a guard: for a row with no transaction id,
 * a live candidate has no upper bound. The network height known at the marker may be lower than the wallet's chain tip
 * the SDK set the expiry from, so a window "expiry <= attempt + 42" could leave out the real transaction and give a
 * false "not sent" (a double payment). An unmined send of ours to this contact whose expiry is 5,000 blocks past the
 * anchor keeps Retry refused at every tip below its expiry + 10. Green at HEAD and after F4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoIdsAliveCandidateHasNoUpperBoundTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4upperbound000001"
        const val FAR_EXPIRY = H + 5_000
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
    fun `a live candidate far past the anchor keeps Retry refused until its own expiry`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val rowId = SendAttemptHarness.newRowId()
            prefs.pending[rowId] = SendAttemptHarness.possiblySentRow(rowId, PEER, "synthetic far hello", emptyList(), H)
            val wallet = Wallet(scanned = H, txs = listOf(ChainTx(9, PEER, null, minedHeight = null, expiryHeight = FAR_EXPIRY)))
            val vm = SendAttemptHarness.openChat(prefs, seam, wallet)

            for (tip in listOf(H, H + 52, H + 53, H + 1_000, H + 1_001, FAR_EXPIRY + 8, FAR_EXPIRY + 9)) {
                wallet.scanned = tip
                wallet.network = tip
                SendAttemptHarness.runPass(vm, wallet)
                assertFalse("Retry was accepted while a candidate can still be mined (tip $tip)", vm.retryMessage(PEER, rowId))
                awaitIdle(vm)
            }
            assertEquals("a payment was proposed while a candidate can still be mined", 0, seam.proposalUris.size)
        }
}
