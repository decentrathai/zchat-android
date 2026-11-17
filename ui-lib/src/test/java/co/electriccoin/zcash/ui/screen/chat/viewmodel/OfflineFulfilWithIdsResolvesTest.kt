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
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 P2; harm 2 of §1.1): a request paid while the phone had no network height
 * is payable again once its stored payment provably expired unmined.
 *
 * At 3df251d7f a fulfil started with no known chain tip records attemptHeight -1, and a record with -1 is never
 * withdrawn: if its transaction expires unmined, the request shows "Paid" with no Pay button for good, with nothing
 * paid.
 *
 * Wanted: the fulfil records its stored transaction id and expiry (F2's onTransactionsStored, before the submit), and a
 * record with ids is settled by F4's rule: its transaction unmined and dead (scanned tip at or past expiry + 10) on two
 * complete passes at a strictly rising tip. Then the marker goes (on disk and in memory), Pay comes back, and the chat
 * gets one "did not go out" note. The controls (one pass, the same tip twice, one block short) change nothing.
 * The fulfil runs through [FulfilStoredIdsHarness] with GrpcFailure([T]); all ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineFulfilWithIdsResolvesTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf5offlinepeer000001"
        const val AMOUNT = 1_000L
        const val E = H + 40
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
    fun `an offline fulfil proven expired on two rising passes offers Pay again with one note`() =
        runBlocking {
            val fx = Fixture("synthetic-f5-p2-request-a", 0xF5201)
            fx.passAt(E + 10)
            val rows = fx.passAt(E + 11)

            assertFalse("the expired payment's paid marker is still on disk", fx.requestId in fx.h.prefs.paid)
            assertFalse("the request is still paid in memory", fx.requestId in paidInMemory(fx.vm))
            assertEquals("the bubble does not offer Pay again", false, fx.row(rows).paymentRequest?.isPaid)
            val noteRes = checkNotNull(FulfilStoredIdsHarness.stringId("chat_note_fulfil_not_sent"))
            assertEquals(
                "the chat must say once that the payment did not go out",
                1,
                fx.h.notes().count { it.text.startsWith("note#$noteRes(") },
            )
            assertEquals("fixture sanity", emptyList<String>(), fx.h.prefs.unexpectedCalls)
        }

    @Test
    fun `control - one pass past the margin keeps the request paid`() =
        runBlocking {
            val fx = Fixture("synthetic-f5-p2-request-c1", 0xF5202)
            fx.assertStaysPaid(fx.passAt(E + 10))
        }

    @Test
    fun `control - the same tip twice keeps the request paid`() =
        runBlocking {
            val fx = Fixture("synthetic-f5-p2-request-c2", 0xF5203)
            fx.passAt(E + 10)
            fx.assertStaysPaid(fx.passAt(E + 10))
        }

    @Test
    fun `control - one block short of the margin keeps the request paid`() =
        runBlocking {
            val fx = Fixture("synthetic-f5-p2-request-c3", 0xF5204)
            fx.passAt(E + 8)
            fx.assertStaysPaid(fx.passAt(E + 9))
        }

    /** The fulfil made with no chain tip known, handed to the SDK and not delivered (GrpcFailure), then one pass. */
    private class Fixture(
        val requestId: String,
        val number: Int,
    ) {
        val h = FulfilStoredIdsHarness(PEER)
        var vm: ChatViewModel
        val wallet = Wallet(scanned = E - 1)

        init {
            runBlocking {
                val t = txId(number)
                val seam = FulfilStoredIdsHarness.IdsSeam(PEER, listOf(StoredTransaction(t, E)), SubmitResult.GrpcFailure(listOf(t)))
                vm = h.viewModel(seam, requestId, AMOUNT)
                vm.fulfillPaymentRequest(PEER, AMOUNT, requestId)
                awaitIdle(vm)
                assertEquals("fixture: the payment was handed over once", 1, seam.submits.get())
                assertTrue("fixture: the request is marked paid", requestId in h.prefs.paid)
                val attempt =
                    h.prefs.started
                        .getValue(requestId)
                        .attemptHeight
                assertEquals("fixture: no chain tip was known", -1L, attempt)
                wallet.txs = listOf(h.fulfilTx(number, requestId, null, E, AMOUNT))
                h.installWallet(vm, wallet)
                // The pass before: it makes the next pass over the same transactions a stable one.
                h.passAt(vm, wallet, E - 1)
            }
        }

        suspend fun passAt(tip: Long): List<ChatMessage> = h.passAt(vm, wallet, tip)

        fun row(rows: List<ChatMessage>): ChatMessage = rows.single { it.id == requestId }

        fun assertStaysPaid(rows: List<ChatMessage>) {
            assertTrue("the paid marker was withdrawn without proof", requestId in h.prefs.paid)
            assertTrue("the request is no longer paid in memory", requestId in paidInMemory(vm))
            assertEquals("the bubble offers Pay again without proof", true, row(rows).paymentRequest?.isPaid)
            assertTrue("a note says the payment did not go out", h.notes().isEmpty())
        }
    }
}
