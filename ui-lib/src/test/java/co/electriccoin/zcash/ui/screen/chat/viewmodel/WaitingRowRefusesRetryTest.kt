package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F4 (fix-all 2.13.0, train T4; SPECS §1.3 R3b), a guard: a send whose transaction can still be mined is never sent
 * again. Its only transaction is unmined and its expiry height is still ahead of the scanned tip; however many passes
 * run, Retry stays refused with "may already have been sent" and nothing is proposed. Green at HEAD and after F4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitingRowRefusesRetryTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4waitingpeer00001"
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
    fun `a live transaction keeps Retry refused on every pass`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val rowId = SendAttemptHarness.newRowId()
            prefs.pending[rowId] = SendAttemptHarness.possiblySentRow(rowId, PEER, "synthetic waiting hello", listOf(txId(1)), H)
            val wallet = Wallet(scanned = EXPIRY - 5, txs = listOf(ChainTx(1, PEER, null, minedHeight = null, expiryHeight = EXPIRY)))
            val vm = SendAttemptHarness.openChat(prefs, seam, wallet)

            for (tip in listOf(EXPIRY - 5, EXPIRY - 4, EXPIRY - 3, EXPIRY + 9)) {
                wallet.scanned = tip
                wallet.network = tip
                SendAttemptHarness.runPass(vm, wallet)
                assertFalse("Retry was accepted while the transaction can still be mined (tip $tip)", vm.retryMessage(PEER, rowId))
                awaitIdle(vm)
                val state = PaymentHarness.sendMessageState(vm)
                assertTrue(
                    "the refusal did not say the message may already have been sent: $state",
                    state is SendMessageState.Error && state.message.contains("may already have been sent", ignoreCase = true),
                )
            }
            assertEquals("a payment was proposed while the first can still be mined", 0, seam.proposalUris.size)
            assertTrue(prefs.pending.containsKey(rowId))
        }
}
