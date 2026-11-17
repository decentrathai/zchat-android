package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.StoredTransaction
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FulfilStoredIdsHarness.Companion.paidInMemory
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FulfilStoredIdsHarness.Companion.paymentPending
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
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 P6, §1.8 item 9): a payment of a request that the wallet no longer holds
 * (a wiped or restored wallet, whose birthday may lie above the payment's block) never re-opens Pay.
 *
 * The started record holds the id of a transaction the SDK stored: the payment existed. If a later read of the wallet
 * does not list it, the wallet lost it, not the chain; the payment may be mined. At 3df251d7f the Round 8 rule does not
 * know the id: once the scanned tip is past the attempt's window and no payment of the request is seen, it withdraws
 * the paid marker and offers Pay again over a payment that may have been made.
 *
 * Wanted: an id missing from the fresh read is never proof. The marker and the record stay, Pay stays hidden, no note
 * is written, and the bubble says "Payment pending", however far the tip moves. All ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FulfilIdMissingFromWalletNeverPaysAgainTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf5missingidpeer0001"
        const val AMOUNT = 1_000L
        const val E = H + 40
        const val NUMBER = 0xF5601
        const val OTHER = 0xF5602
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
    fun `a recorded payment the wallet no longer holds keeps the request paid and pending`() =
        runBlocking {
            val requestId = "synthetic-f5-p6-request-a"
            val h = FulfilStoredIdsHarness(PEER)
            val t = txId(NUMBER)
            val seam = FulfilStoredIdsHarness.IdsSeam(PEER, listOf(StoredTransaction(t, E)), SubmitResult.Success(listOf(t)))
            val vm = h.viewModel(seam, requestId, AMOUNT)
            // The attempt height is known, so the Round 8 window can act on a record without ids.
            PaymentHarness.setBlockHeight(vm, E - 40)
            vm.fulfillPaymentRequest(PEER, AMOUNT, requestId)
            awaitIdle(vm)
            val attempt =
                h.prefs.started
                    .getValue(requestId)
                    .attemptHeight
            assertEquals("fixture: the attempt height is known", E - 40, attempt)

            // The wallet was restored: it holds another payment of this account, and not the request's payment.
            val other =
                ChainTx(
                    OTHER,
                    PEER,
                    memo = SendAttemptHarness.messageMemo("synthetic other hello"),
                    minedHeight = E - 60,
                    expiryHeight = E - 30,
                )
            val wallet = Wallet(scanned = E + 9, txs = listOf(other))
            h.installWallet(vm, wallet)
            h.passAt(vm, wallet, E + 9)

            fun assertStaysPaid(
                rows: List<ChatMessage>,
                at: String,
            ) {
                assertTrue("the paid marker was withdrawn at $at over a payment the wallet lost", requestId in h.prefs.paid)
                assertTrue("the request is no longer paid in memory at $at", requestId in paidInMemory(vm))
                assertTrue("the started record was dropped at $at", h.prefs.started.containsKey(requestId))
                val row = rows.single { it.id == requestId }
                assertEquals("Pay is offered again at $at", true, row.paymentRequest?.isPaid)
                assertEquals("the bubble does not say Payment pending at $at", true, paymentPending(row))
                assertTrue("a note says the payment did not go out at $at", h.notes().isEmpty())
            }
            assertStaysPaid(h.passAt(vm, wallet, E + 10), "expiry + 10")
            assertStaysPaid(h.passAt(vm, wallet, E + 11), "expiry + 11")
            assertStaysPaid(h.passAt(vm, wallet, E + 500), "expiry + 500")
        }
}
