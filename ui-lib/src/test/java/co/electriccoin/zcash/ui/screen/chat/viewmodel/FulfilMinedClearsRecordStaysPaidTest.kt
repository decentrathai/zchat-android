package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.StoredTransaction
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FulfilStoredIdsHarness.Companion.paidInMemory
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FulfilStoredIdsHarness.Companion.paymentPending
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
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
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 P4; harm 4 of §1.1): a mined payment of a request clears its started
 * record even when this phone cannot read its memo.
 *
 * At 3df251d7f a started record is cleared only when the pass reads a mined `ZREQ_FULFILL|<id>` memo. A mined payment
 * whose memo the SDK has not returned (or cannot) keeps the record for good, and the request can never settle.
 *
 * Wanted: the record holds the payment's transaction id, and a mined transaction with that id clears it; the request
 * stays paid (Pay hidden) and does not say "Payment pending". All ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FulfilMinedClearsRecordStaysPaidTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf5minedunreadpeer01"
        const val AMOUNT = 1_000L
        const val E = H + 40
        const val NUMBER = 0xF5401
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
    fun `a mined payment whose memo is not read clears the record and the request stays paid`() =
        runBlocking {
            val requestId = "synthetic-f5-p4-request-a"
            val h = FulfilStoredIdsHarness(PEER)
            val t = txId(NUMBER)
            val seam = FulfilStoredIdsHarness.IdsSeam(PEER, listOf(StoredTransaction(t, E)), SubmitResult.Success(listOf(t)))
            val vm = h.viewModel(seam, requestId, AMOUNT)
            PaymentHarness.setBlockHeight(vm, E - 40)
            vm.fulfillPaymentRequest(PEER, AMOUNT, requestId)
            awaitIdle(vm)
            assertTrue("fixture: a started record", h.prefs.started.containsKey(requestId))

            val wallet =
                Wallet(scanned = E - 20, txs = listOf(h.fulfilTx(NUMBER, requestId, E - 30, E, AMOUNT, memoRead = false)))
            h.installWallet(vm, wallet)
            h.passAt(vm, wallet, E - 20)
            val row = h.passAt(vm, wallet, E - 19).single { it.id == requestId }

            assertFalse("the record of a payment the chain shows mined is kept", h.prefs.started.containsKey(requestId))
            assertTrue("the request is no longer paid on disk", requestId in h.prefs.paid)
            assertTrue("the request is no longer paid in memory", requestId in paidInMemory(vm))
            assertEquals("Pay is offered again over a mined payment", true, row.paymentRequest?.isPaid)
            assertNotEquals("a mined payment says Payment pending", true, paymentPending(row))
            assertTrue("a note says a mined payment did not go out", h.notes().isEmpty())
        }
}
