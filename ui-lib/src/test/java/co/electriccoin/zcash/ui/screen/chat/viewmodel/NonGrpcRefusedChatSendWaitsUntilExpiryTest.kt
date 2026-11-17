package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.AMOUNT
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F4 (fix-all 2.13.0, train T4; T2 review C-F2-3, single leg; SPECS §1.3 R12): a chat message the server refused after
 * the SDK stored it (a non-gRPC Failure that carries its txid, code -26) waits for its expiry, then gets one Retry.
 *
 * The SDK keeps a stored transaction and may broadcast it again, so the row is rightly "may already have been sent"
 * (StoredRejectionKeepsTransactionIdsTest). At HEAD nothing ever settles it: Retry is refused for good, even after
 * the transaction expired unmined. Wanted: refused while it can be mined; once it is expired unmined at expiry + 10
 * over two rising passes, one Retry. Its reason is never the plain "Send failed, try again" while it can be mined.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NonGrpcRefusedChatSendWaitsUntilExpiryTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4refusedpeer00001"
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
    fun `a stored send the server refused waits for its expiry and then allows one Retry`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val refused = SubmitResult.Failure(txIds = listOf(txId(1)), code = -26, description = "synthetic refused after store")
            val seam = PaymentSeam(PEER, firstSubmitOutcome = refused)
            val sender = PaymentHarness.paymentViewModel(prefs, seam)
            PaymentHarness.setBlockHeight(sender, H)
            assertTrue(sender.sendMessage(PEER, "synthetic refused hello", AMOUNT))
            awaitIdle(sender)
            val rowId =
                prefs.pending.values
                    .single()
                    .id
            assertEquals("fixture: the stored rejection is held", listOf(txId(1)), prefs.pending.getValue(rowId).submittedTxIds)

            val wallet = Wallet(scanned = H, txs = listOf(ChainTx(1, PEER, null, minedHeight = null, expiryHeight = EXPIRY)))
            val vm = SendAttemptHarness.openChat(prefs, seam, wallet)
            for (tip in listOf(EXPIRY - 30, EXPIRY + 5, EXPIRY + 9)) {
                pass(vm, wallet, tip)
                val row = PaymentHarness.pendingMessages(vm).single { it.id == rowId }
                assertNotEquals(
                    "a send that can still be mined reads as a plain failure",
                    ChatSendCopy.MESSAGE_FAILED,
                    ChatSendCopy.failureReasonFor(row),
                )
                assertFalse("Retry was accepted while the refused send can still be mined (tip $tip)", vm.retryMessage(PEER, rowId))
                awaitIdle(vm)
            }
            assertEquals("only the first attempt was proposed", 1, seam.proposalUris.size)

            pass(vm, wallet, EXPIRY + 10)
            pass(vm, wallet, EXPIRY + 11)
            assertEquals(MessageStatus.FAILED, PaymentHarness.pendingMessages(vm).single { it.id == rowId }.status)
            assertTrue("Retry stayed refused after the refused send expired unmined", vm.retryMessage(PEER, rowId))
            awaitIdle(vm)
            assertEquals("exactly one new attempt", 2, seam.proposalUris.size)
            assertFalse("a second Retry was accepted", vm.retryMessage(PEER, rowId))
            awaitIdle(vm)
            assertEquals("exactly one new attempt", 2, seam.proposalUris.size)
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
