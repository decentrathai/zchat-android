package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.StoredTransaction
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FulfilStoredIdsHarness.Companion.paymentPending
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.txId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * T5 fixup F5-X1 (fix-all 2.13.0, train T5; REVIEW_CROSS.md finding 1, medium; SPECS §1.2 "what the bubble says").
 *
 * At 0c9146395 `ChatViewModel.fulfilPendingThisPass` answers an incomplete history pass with the process-wide
 * `fulfilPendingIds` plus this pass's live sightings, and reads the started records only on a complete pass. After a
 * restart that set is empty, while the paid marker comes back from disk. The first pass after a restart is never
 * complete (it is not stable yet), and a pass that cannot read a memo is never complete, so a paid request whose own
 * payment is not mined, and whose memo this pass did not read (no memo returned yet, a failed read, or a hidden row),
 * says "Paid" for as long as the passes stay incomplete. Pay stays hidden: a false money status, not a second payment.
 *
 * Wanted: every pass, complete or not, counts an open started record as "Payment pending" unless the chain shows that
 * payment mined; an incomplete pass never says "Paid" for less than the last complete one knew. Once the payment is
 * mined and its memo read, the bubble says "Paid" (control). The real fulfil and the real history pass over
 * [FulfilStoredIdsHarness]; the restart is a new view model with the process-wide pending set emptied. Synthetic ids.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FulfilPendingSurvivesARestartTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf5x1restartpeer0001"
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
    fun `after a restart a payment whose memo was not returned yet says Payment pending, not Paid`() =
        runBlocking {
            val requestId = "synthetic-f5x1-request-a"
            val number = 0xF5A01
            val (h, _) = paid(requestId, number)
            val wallet = Wallet(scanned = E - 39, txs = listOf(h.fulfilTx(number, requestId, null, E, AMOUNT, memoRead = false)))
            try {
                val vm = restarted(h, requestId, wallet)
                val first = h.passAt(vm, wallet, E - 38).single { it.id == requestId }
                assertEquals("fixture: Pay stays hidden after the restart", true, first.paymentRequest?.isPaid)
                assertEquals(
                    "after a restart, the first pass says Paid for a payment the chain has not mined (its memo was not read)",
                    true,
                    paymentPending(first),
                )
            } finally {
                pendingIds().remove(requestId)
            }
        }

    @Test
    fun `after a restart every pass that cannot read the payment's memo says Payment pending, and Paid once it is mined`() =
        runBlocking {
            val requestId = "synthetic-f5x1-request-b"
            val number = 0xF5A02
            val (h, _) = paid(requestId, number)
            val wallet = Wallet(scanned = E - 39, txs = listOf(h.fulfilTx(number, requestId, null, E, AMOUNT)))
            try {
                val vm = restarted(h, requestId, wallet)
                val failing = FailingMemos(vm, txId(number))
                for (tip in (E - 38)..(E - 34)) {
                    val row = h.passAt(vm, wallet, tip).single { it.id == requestId }
                    assertEquals("fixture: Pay stays hidden", true, row.paymentRequest?.isPaid)
                    assertEquals(
                        "a pass that could not read the payment's memo says Paid at tip $tip (the payment is not mined)",
                        true,
                        paymentPending(row),
                    )
                }
                assertTrue("fixture: the memo reads failed", failing.failures > 0)

                failing.failing = false
                wallet.txs = listOf(h.fulfilTx(number, requestId, E - 30, E, AMOUNT))
                h.passAt(vm, wallet, E - 20)
                val mined = h.passAt(vm, wallet, E - 19).single { it.id == requestId }
                assertEquals("a mined payment offers Pay again", true, mined.paymentRequest?.isPaid)
                assertEquals("a mined payment still says Payment pending", false, paymentPending(mined))
                assertFalse("the started record of a mined payment is kept", h.prefs.started.containsKey(requestId))
                assertEquals("fixture sanity", emptyList<String>(), h.prefs.unexpectedCalls)
            } finally {
                pendingIds().remove(requestId)
            }
        }

    /** Pays [requestId] through the real fulfil, which records the payment's stored id [number] on its started record. */
    private suspend fun paid(
        requestId: String,
        number: Int,
    ): Pair<FulfilStoredIdsHarness, ChatViewModel> {
        val h = FulfilStoredIdsHarness(PEER)
        val t = txId(number)
        val seam = FulfilStoredIdsHarness.IdsSeam(PEER, listOf(StoredTransaction(t, E)), SubmitResult.Success(listOf(t)))
        val vm = h.viewModel(seam, requestId, AMOUNT)
        PaymentHarness.setBlockHeight(vm, E - 40)
        vm.fulfillPaymentRequest(PEER, AMOUNT, requestId)
        awaitIdle(vm)
        assertEquals("fixture: the payment was handed over once", 1, seam.submits.get())
        assertTrue("fixture: the request is marked paid", requestId in h.prefs.paid)
        assertTrue("fixture: the started record holds the payment's id", h.recorded[requestId]?.first == listOf(t))
        return h to vm
    }

    /**
     * The process restarts: the process-wide pending set is empty, and a new chat screen loads the request row from disk
     * with isPaid from the paid marker (loadPendingMessagesFromPrefs) and no pending flag (it is not stored).
     */
    private suspend fun restarted(
        h: FulfilStoredIdsHarness,
        requestId: String,
        wallet: Wallet,
    ): ChatViewModel {
        pendingIds().clear()
        val seam = FulfilStoredIdsHarness.IdsSeam(PEER, emptyList(), SubmitResult.Success(emptyList()))
        val vm = h.viewModel(seam, requestId, AMOUNT)
        val loaded = PaymentHarness.pendingMessages(vm).single { it.id == requestId }
        val row: ChatMessage = loaded.copy(paymentRequest = loaded.paymentRequest?.copy(isPaid = requestId in h.prefs.paid))
        PaymentHarness.setField(vm, "pendingMessages", MutableStateFlow(listOf(row)))
        h.installWallet(vm, wallet)
        return vm
    }

    /** Makes the view model's memo read of [txId] throw while [failing] (the SDK read failed). */
    private class FailingMemos(
        vm: ChatViewModel,
        private val txId: String,
    ) {
        @Volatile var failing = true

        @Volatile var failures = 0

        init {
            val field = ChatViewModel::class.java.getDeclaredField("transactionRepository").apply { isAccessible = true }
            val base = field.get(vm) as TransactionRepository
            val proxy =
                Proxy.newProxyInstance(
                    TransactionRepository::class.java.classLoader,
                    arrayOf(TransactionRepository::class.java),
                ) { _, method, args ->
                    if (method.name == "getMemos" && failing && (args?.firstOrNull() as? Transaction)?.id?.txIdString() == txId) {
                        failures++
                        throw IllegalStateException("synthetic memo read failure")
                    }
                    try {
                        method.invoke(base, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                } as TransactionRepository
            field.set(vm, proxy)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingIds(): MutableSet<String> =
        ChatViewModel::class.java
            .getDeclaredField("fulfilPendingIds")
            .apply { isAccessible = true }
            .get(null) as MutableSet<String>
}
