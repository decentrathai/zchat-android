package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.sendattempt.ChainTimingApi
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestInfo
import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PendingReconcileHarness.SCANNED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ZC-114 (fix-all 2.13.0, train T5b; high-items SPECS §1.5 R3): a payment of a request whose started record holds no
 * transaction id (the process was killed before the SDK stored it) gives Pay back only once nothing it may have stored
 * can still be mined: past the attempt's expiry window, the largest expiry delta the shipped SDK can use at the
 * attempt's height plus 2. Before NU7 that is 42 (FulfilKilledBeforeStoreIsReconciledTest); from NU7 on (ZIP 218: 120)
 * it is 126 (ZC-1554 keeps the margin's time: 2 blocks of 75 s are 6 of 25 s), so the request is never paid twice while
 * the first payment can still be mined.
 *
 * At RED the window is the constant 42 at every height. Runs the real fulfil and the real history passes
 * ([FulfilKilledBeforeStoreIsReconciledTest]'s harness); the NU7 height is made up, ids and addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FulfilWithoutIdsWaitsForTheExpiryDeltaOfItsHeightTest {
    private val sendScopes = SendHarnessScopes()
    private var savedHistoryRead = false
    private var savedOwnHistoryRead = false
    private lateinit var savedTiming: ChainTimingApi.Saved

    private companion object {
        const val PEER = "usyntheticzc114fulfil00000001"
        const val REQUEST_AMOUNT = 40_000L
        const val REASON = "synthetic zc114 share"

        /** How far below the attempt the made-up NU7 activation lies. */
        const val NU7_BELOW_ATTEMPT = 10L
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        savedHistoryRead = historyReadFlag().get()
        savedOwnHistoryRead = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        savedTiming = ChainTimingApi.Saved.take()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        historyReadFlag().set(savedHistoryRead)
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(savedOwnHistoryRead)
        savedTiming.restore()
    }

    @Test
    fun `a fulfil started after NU7 still counts as paid 43 blocks after its attempt`() =
        runBlocking {
            val requestId = "synthetic-zc114-fulfil-43"
            val attempt = SCANNED - 43
            val (prefs, vm, rows) = killedAndReconciled(requestId, attempt, nu7 = attempt - NU7_BELOW_ATTEMPT)
            assertStaysPaid(
                "a payment made after NU7 can be mined until 120 blocks past its height, yet Pay shows again at " +
                    "attempt + 43: the request can be paid twice",
                prefs,
                vm,
                rows,
                requestId,
            )
        }

    @Test
    fun `a fulfil started after NU7 still counts as paid 126 blocks after its attempt`() =
        runBlocking {
            val requestId = "synthetic-zc114-fulfil-126"
            val attempt = SCANNED - 126
            val (prefs, vm, rows) = killedAndReconciled(requestId, attempt, nu7 = attempt - NU7_BELOW_ATTEMPT)
            assertStaysPaid("Pay shows again inside the post-NU7 expiry window (attempt + 126)", prefs, vm, rows, requestId)
        }

    @Test
    fun `a fulfil started after NU7 is payable again once the tip passes its attempt plus 126`() =
        runBlocking {
            val requestId = "synthetic-zc114-fulfil-127"
            val attempt = SCANNED - 127
            val (prefs, vm, rows) = killedAndReconciled(requestId, attempt, nu7 = attempt - NU7_BELOW_ATTEMPT)
            assertWithdrawn(prefs, vm, rows, requestId)
        }

    @Test
    fun `control - a fulfil started well before NU7 is payable again at its attempt plus 43 as today`() =
        runBlocking {
            val requestId = "synthetic-zc114-fulfil-before"
            val attempt = SCANNED - 43
            val (prefs, vm, rows) = killedAndReconciled(requestId, attempt, nu7 = attempt + 1_000)
            assertWithdrawn(prefs, vm, rows, requestId)
        }

    // --------------------------------------------------------------- harness

    private data class Outcome(
        val prefs: PaymentPrefs,
        val vm: ChatViewModel,
        val rows: List<ChatMessage>,
    )

    /**
     * A fulfil of [requestId] started at chain tip [attempt] and killed before the SDK stored anything; a new process
     * with NU7 active from [nu7] on runs three complete history passes at [SCANNED] with no transaction of it.
     */
    private suspend fun killedAndReconciled(
        requestId: String,
        attempt: Long,
        nu7: Long,
    ): Outcome {
        ChainTimingApi.current = ChainTimingApi.heights(nu7)
        val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL)
        val seam = PaymentSeam(PEER, holdFirstSubmit = true)
        val first = paymentViewModel(prefs, seam)
        PaymentHarness.setBlockHeight(first, attempt)
        first.fulfillPaymentRequest(PEER, REQUEST_AMOUNT, requestId)
        withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
        assertTrue("fixture: the paid marker is on disk when the process dies", requestId in prefs.paid)
        fulfilInFlight().remove(requestId)

        val vm = paymentViewModel(prefs, PaymentSeam(PEER))
        PaymentHarness.setField(vm, "restoreRecheck", MutableStateFlow(0))
        PaymentHarness.setField(vm, "pendingMessages", MutableStateFlow(listOf(nostrRequest(requestId, true))))
        var rows: List<ChatMessage> = emptyList()
        repeat(3) {
            rows =
                PendingReconcileHarness
                    .reconcileConversations(vm, prefs, emptyList(), paidReqIds = paidRequestIds(vm), historyScanComplete = true)
                    .flatMap { it.messages }
        }
        return Outcome(prefs, vm, rows)
    }

    private fun assertStaysPaid(
        why: String,
        prefs: PaymentPrefs,
        vm: ChatViewModel,
        rows: List<ChatMessage>,
        requestId: String,
    ) {
        assertTrue("$why (the paid marker was withdrawn)", requestId in prefs.paid)
        assertTrue("$why (the request is no longer paid in memory)", requestId in paidRequestIds(vm))
        assertEquals("$why (the bubble offers Pay again)", true, rows.single { it.id == requestId }.paymentRequest?.isPaid)
        assertTrue(
            "$why (a note says a payment that may have gone out did not)",
            prefs.pending.values.none { SysNotes.isSystemNoteId(it.id) && it.peerAddress == PEER },
        )
    }

    private fun assertWithdrawn(
        prefs: PaymentPrefs,
        vm: ChatViewModel,
        rows: List<ChatMessage>,
        requestId: String,
    ) {
        assertFalse("nothing can be mined any more, yet the request stays marked paid on disk", requestId in prefs.paid)
        assertFalse("the request is still paid in memory", requestId in paidRequestIds(vm))
        assertEquals("the bubble still shows Paid", false, rows.single { it.id == requestId }.paymentRequest?.isPaid)
    }

    private fun nostrRequest(
        id: String,
        paid: Boolean,
    ): ChatMessage =
        ChatMessage(
            id = id,
            txId = null,
            text = REASON,
            timestamp = java.time.Instant.now(),
            isOutgoing = false,
            peerAddress = PEER,
            isPending = false,
            status = MessageStatus.SENT,
            paymentRequest = PaymentRequestInfo(amountZatoshi = REQUEST_AMOUNT, reason = REASON, isPaid = paid),
            authenticated = true,
        )

    @Suppress("UNCHECKED_CAST")
    private fun paidRequestIds(vm: ChatViewModel): Set<String> =
        (
            ChatViewModel::class.java
                .getDeclaredField("paidRequestIds")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<Set<String>>
        ).value

    @Suppress("UNCHECKED_CAST")
    private fun fulfilInFlight(): MutableSet<String> =
        ChatViewModel::class.java
            .getDeclaredField("fulfilInFlight")
            .apply { isAccessible = true }
            .get(null) as MutableSet<String>

    private fun historyReadFlag(): AtomicBoolean =
        ChatViewModel::class.java.declaredFields
            .single { it.name == "processHistoryRead" && Modifier.isStatic(it.modifiers) }
            .apply { isAccessible = true }
            .get(null) as AtomicBoolean
}
