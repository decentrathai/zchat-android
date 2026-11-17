package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.ChainTx
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Writes
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.txId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F4 (fix-all 2.13.0, train T4; SPECS §1.2 "Proof", §1.3 R9): the chat list and the open chat run their own history
 * passes, often at the same tip within a second. A proven expiry is applied once in total, and the one new attempt it
 * grants can be taken once in total, whichever screen taps Retry.
 *
 * The list screen and the detail screen run passes in turn at E + 10, E + 11 and E + 12. The row is converted to the
 * "proven nothing sent" FAILED row by exactly one durable write. A Retry from one screen makes one new attempt; a
 * Retry from the other screen afterwards is refused. At HEAD the row is never converted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoViewModelsApplyNotSentOnceTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4twoscreens000001"
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
    fun `two screens apply the proven expiry once and grant one attempt in total`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val rowId = SendAttemptHarness.newRowId()
            prefs.pending[rowId] = SendAttemptHarness.possiblySentRow(rowId, PEER, "synthetic two screens", listOf(txId(1)), H)
            val wallet = Wallet(scanned = H, txs = listOf(ChainTx(1, PEER, null, minedHeight = null, expiryHeight = EXPIRY)))
            val writes = Writes()
            val list = SendAttemptHarness.openChat(prefs, seam, wallet, writes)
            val detail = SendAttemptHarness.openChat(prefs, seam, wallet, writes)

            pass(list, wallet, EXPIRY + 10)
            pass(detail, wallet, EXPIRY + 10)
            pass(detail, wallet, EXPIRY + 11)
            pass(list, wallet, EXPIRY + 11)
            pass(list, wallet, EXPIRY + 12)
            pass(detail, wallet, EXPIRY + 12)

            assertEquals("the proven expiry must be written exactly once", 1, writes.notSentConversions(rowId))

            assertTrue("the list screen's Retry was refused after the proven expiry", list.retryMessage(PEER, rowId))
            awaitIdle(list)
            assertFalse("the detail screen took the same new attempt a second time", detail.retryMessage(PEER, rowId))
            awaitIdle(detail)
            assertEquals("one proven expiry grants one new attempt in total", 1, seam.proposalUris.size)
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
