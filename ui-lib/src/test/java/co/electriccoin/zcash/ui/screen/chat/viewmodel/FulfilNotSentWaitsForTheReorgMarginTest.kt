package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.StoredTransaction
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FulfilStoredIdsHarness.Companion.paidInMemory
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 P3; harm 3 of §1.1): a stored payment of a request that expired unmined
 * is proven dead by F4's rule, not three blocks after its expiry.
 *
 * At 3df251d7f a started record is settled by the Round 8 P2 rule: once the scanned tip is past attempt + 42 and past
 * the expired payment's expiry + 2, one stable pass withdraws the paid marker, which is below the F4 standard (10
 * blocks past the expiry, seen on two passes at a strictly rising tip). A reorg that brings the payment back after
 * expiry + 3 would find Pay offered again.
 *
 * Wanted: a record that holds its transaction id is settled only by the id: the request stays paid (Pay hidden, no
 * note) at expiry + 3 and + 4, and the marker goes only after passes at expiry + 10 and + 11. All ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FulfilNotSentWaitsForTheReorgMarginTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf5reorgmarginpeer01"
        const val AMOUNT = 1_000L
        const val E = H + 40
        const val NUMBER = 0xF5301
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
    fun `an expired payment of a request is withdrawn only after the reorg margin on two rising passes`() =
        runBlocking {
            val requestId = "synthetic-f5-p3-request-a"
            val h = FulfilStoredIdsHarness(PEER)
            val t = txId(NUMBER)
            val seam = FulfilStoredIdsHarness.IdsSeam(PEER, listOf(StoredTransaction(t, E)), SubmitResult.Success(listOf(t)))
            val vm = h.viewModel(seam, requestId, AMOUNT)
            PaymentHarness.setBlockHeight(vm, E - 40)
            vm.fulfillPaymentRequest(PEER, AMOUNT, requestId)
            awaitIdle(vm)
            val attempt =
                h.prefs.started
                    .getValue(requestId)
                    .attemptHeight
            assertEquals("fixture: the attempt height is known", E - 40, attempt)
            val wallet = Wallet(scanned = E - 1, txs = listOf(h.fulfilTx(NUMBER, requestId, null, E, AMOUNT)))
            h.installWallet(vm, wallet)
            h.passAt(vm, wallet, E - 1)

            fun assertStaysPaid(
                rows: List<ChatMessage>,
                at: String,
            ) {
                assertTrue("the paid marker was withdrawn at $at, inside the reorg margin", requestId in h.prefs.paid)
                assertTrue("the request is no longer paid in memory at $at", requestId in paidInMemory(vm))
                assertEquals("Pay is offered again at $at", true, rows.single { it.id == requestId }.paymentRequest?.isPaid)
                assertTrue("a note says the payment did not go out at $at", h.notes().isEmpty())
            }
            assertStaysPaid(h.passAt(vm, wallet, E + 3), "expiry + 3")
            assertStaysPaid(h.passAt(vm, wallet, E + 4), "expiry + 4")

            h.passAt(vm, wallet, E + 10)
            val rows = h.passAt(vm, wallet, E + 11)
            assertFalse("the payment is proven dead, yet the request stays paid", requestId in h.prefs.paid)
            assertEquals("the bubble does not offer Pay again", false, rows.single { it.id == requestId }.paymentRequest?.isPaid)
            assertEquals("one note", 1, h.notes().size)
        }
}
