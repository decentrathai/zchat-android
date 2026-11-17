package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestInfo
import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PendingReconcileHarness.SCANNED
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PendingReconcileHarness.SentTx
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Round 8 P2 (PHONE_TRIAGE F2 and the expired gap) — "Paid" must be backed by a transaction.
 *
 * (a) The fulfil writes its paid marker in onSubmitStarting, before the SDK proves and stores the payment (C3). A kill
 * in that window (the phone: the PIN at 05:51:11, a force-stop 1.2 s later, no new transaction) leaves the marker on
 * disk with nothing on chain, and nothing ever withdraws it: the request shows "Paid" with no Pay button for good.
 *
 * (b) A fulfil the SDK stored but that expired unmined is a SendTransaction.Failed. The history scan-back reads its
 * ZREQ_FULFILL memo like any other and keeps the request marked paid for good.
 *
 * After the fix, a stable, fully read history pass whose scanned tip is past the attempt's expiry window, with no
 * live ZREQ_FULFILL for the request and no transaction that could be it, withdraws the marker and leaves a note.
 * The controls pin what must never be withdrawn.
 *
 * Runs the real fulfil (onSubmitStarting through the real use case, then a submit that never returns) and the real
 * convertToConversations ([PendingReconcileHarness]). All ids and addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FulfilKilledBeforeStoreIsReconciledTest {
    private val sendScopes = SendHarnessScopes()
    private var savedHistoryRead = false
    private var savedOwnHistoryRead = false

    private companion object {
        const val PEER = "usyntheticfulfilkilled0000001"
        const val REQUEST_AMOUNT = 40_000L
        const val REASON = "synthetic taxi share"

        /** SDK expiry delta (40) plus a margin of two blocks. */
        const val WINDOW = 42L
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        savedHistoryRead = historyReadFlag().get()
        savedOwnHistoryRead = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        historyReadFlag().set(savedHistoryRead)
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(savedOwnHistoryRead)
    }

    // ── (a) a kill between the PIN and the store ────────────────────────────────────────────────────

    @Test
    fun `a fulfil killed before the SDK stored anything is payable again once its expiry window has passed`() =
        runBlocking {
            val requestId = "synthetic-p2-request-kill-a"
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL)
            val attempt = SCANNED - WINDOW - 1
            killedFulfil(prefs, requestId, attempt)

            val restarted = restartedChat(prefs, requestId)
            val rows = stablePasses(restarted, prefs, emptyList())

            assertFalse(
                "nothing was ever stored for this payment and its expiry window has passed, yet the request stays " +
                    "marked paid on disk",
                requestId in prefs.paid,
            )
            assertFalse("the request is still paid in memory", requestId in paidRequestIds(restarted))
            assertEquals(
                "the request bubble still shows Paid with no Pay button, over a payment that never went out",
                false,
                rows.single { it.id == requestId }.paymentRequest?.isPaid,
            )
            val noteRes = fulfilNotSentRes()
            assertNotNull("there is no chat_note_fulfil_not_sent string (EN)", noteRes)
            val note = prefs.pending.values.filter { SysNotes.isSystemNoteId(it.id) && it.peerAddress == PEER }
            assertTrue(
                "the chat does not tell the user the started payment did not go out: $note",
                note.any { it.text.startsWith("note#$noteRes(") },
            )
        }

    // ── (b) a stored fulfil that expired unmined ────────────────────────────────────────────────────

    @Test
    fun `a fulfil that was stored but expired unmined is payable again`() =
        runBlocking {
            val requestId = "synthetic-p2-request-expired-b"
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL)
            val attempt = SCANNED - WINDOW - 20
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)
            PaymentHarness.setBlockHeight(vm, attempt)
            vm.fulfillPaymentRequest(PEER, REQUEST_AMOUNT, requestId)
            awaitIdle(vm)
            assertEquals("fixture: the payment was stored and broadcast", 1, seam.submits.get())
            assertTrue("fixture: the request is marked paid", requestId in prefs.paid)

            val restarted = restartedChat(prefs, requestId)
            val expired =
                SentTx(
                    number = 1,
                    recipient = PEER,
                    memo = "ZREQ_FULFILL|$requestId",
                    minedHeight = null,
                    blockTime = null,
                    expiryHeight = attempt + 40,
                    expired = true,
                    amount = REQUEST_AMOUNT + 10_000,
                )
            val rows = stablePasses(restarted, prefs, listOf(expired))

            assertFalse(
                "the only payment for this request expired unmined (SendTransaction.Failed), yet the request stays paid",
                requestId in prefs.paid,
            )
            assertEquals(
                "the bubble still shows Paid over a payment that expired unmined",
                false,
                rows.single { it.id == requestId }.paymentRequest?.isPaid,
            )
        }

    // ── controls: never withdrawn ───────────────────────────────────────────────────────────────────

    @Test
    fun `control - a killed fulfil still inside its expiry window stays paid`() =
        runBlocking {
            val requestId = "synthetic-p2-request-window-c1"
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL)
            killedFulfil(prefs, requestId, SCANNED - 10)

            val restarted = restartedChat(prefs, requestId)
            val rows = stablePasses(restarted, prefs, emptyList())

            assertStaysPaid(prefs, restarted, rows, requestId)
        }

    @Test
    fun `control - a pending fulfil that has not expired stays paid`() =
        runBlocking {
            val requestId = "synthetic-p2-request-pending-c2"
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL)
            killedFulfil(prefs, requestId, SCANNED - WINDOW - 1)

            val restarted = restartedChat(prefs, requestId)
            val pending = SentTx(1, PEER, "ZREQ_FULFILL|$requestId", minedHeight = null, blockTime = null)
            val rows = stablePasses(restarted, prefs, listOf(pending))

            assertStaysPaid(prefs, restarted, rows, requestId)
        }

    @Test
    fun `control - a mined payment to the peer for the amount whose memo is not read yet stays paid`() =
        runBlocking {
            val requestId = "synthetic-p2-request-nomemo-c3"
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL)
            val attempt = SCANNED - WINDOW - 1
            killedFulfil(prefs, requestId, attempt)

            val restarted = restartedChat(prefs, requestId)
            val unread = SentTx(1, PEER, memo = null, minedHeight = attempt + 2, amount = REQUEST_AMOUNT + 10_000)
            val rows = stablePasses(restarted, prefs, listOf(unread))

            assertStaysPaid(prefs, restarted, rows, requestId)
        }

    @Test
    fun `control - a paid marker with no started record is never withdrawn`() =
        runBlocking {
            val requestId = "synthetic-p2-request-norecord-c4"
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL)
            // A marker from the scan-back, an older test build or the Keystone path: no fulfil started here.
            prefs.paid.add(requestId)

            val restarted = restartedChat(prefs, requestId)
            val rows = stablePasses(restarted, prefs, emptyList())

            assertStaysPaid(prefs, restarted, rows, requestId)
        }

    @Test
    fun `control - nothing is withdrawn while the history pass is not stable`() =
        runBlocking {
            val requestId = "synthetic-p2-request-unstable-c5"
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL)
            killedFulfil(prefs, requestId, SCANNED - WINDOW - 1)

            val restarted = restartedChat(prefs, requestId)
            var rows: List<ChatMessage> = emptyList()
            repeat(3) {
                rows =
                    PendingReconcileHarness
                        .reconcileConversations(
                            restarted,
                            prefs,
                            emptyList(),
                            paidReqIds = paidRequestIds(restarted),
                            historyScanComplete = false,
                        ).flatMap { it.messages }
            }

            assertStaysPaid(prefs, restarted, rows, requestId)
        }

    @Test
    fun `control - a request another screen is paying right now is never withdrawn`() =
        runBlocking {
            val requestId = "synthetic-p2-request-inflight-c6"
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL)
            killedFulfil(prefs, requestId, SCANNED - WINDOW - 1)
            // Another chat screen of this process holds the claim on the request.
            fulfilInFlight().add(requestId)
            try {
                val restarted = restartedChat(prefs, requestId)
                val rows = stablePasses(restarted, prefs, emptyList())

                assertStaysPaid(prefs, restarted, rows, requestId)
            } finally {
                fulfilInFlight().remove(requestId)
            }
        }

    @Test
    fun `control - a fulfil that was mined stays paid long after its expiry window`() =
        runBlocking {
            val requestId = "synthetic-p2-request-mined-c7"
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL)
            val attempt = SCANNED - WINDOW - 100
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)
            PaymentHarness.setBlockHeight(vm, attempt)
            vm.fulfillPaymentRequest(PEER, REQUEST_AMOUNT, requestId)
            awaitIdle(vm)

            val restarted = restartedChat(prefs, requestId)
            val mined =
                SentTx(1, PEER, "ZREQ_FULFILL|$requestId", minedHeight = attempt + 3, amount = REQUEST_AMOUNT + 10_000)
            stablePasses(restarted, prefs, listOf(mined))
            // Later passes that no longer list it (a wallet view that dropped it) never undo a payment seen mined.
            val rows = stablePasses(restarted, prefs, emptyList())

            assertStaysPaid(prefs, restarted, rows, requestId)
        }

    // --------------------------------------------------------------- harness

    /**
     * Starts a fulfil of [requestId] with the chain tip at [attempt]. The use case runs onSubmitStarting and hands the
     * payment to the SDK, whose submit never returns. The process is killed there: only [prefs] (the disk) survives.
     */
    private suspend fun killedFulfil(
        prefs: PaymentPrefs,
        requestId: String,
        attempt: Long,
    ) {
        val seam = PaymentSeam(PEER, holdFirstSubmit = true)
        val vm = paymentViewModel(prefs, seam)
        PaymentHarness.setBlockHeight(vm, attempt)
        vm.fulfillPaymentRequest(PEER, REQUEST_AMOUNT, requestId)
        withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
        assertTrue("fixture: the paid marker is on disk when the process dies", requestId in prefs.paid)
        // A new process knows no in-flight claim.
        fulfilInFlight().remove(requestId)
    }

    /** A new process opens the chat: the paid set loads from [prefs], the free request row is built from it. */
    private suspend fun restartedChat(
        prefs: PaymentPrefs,
        requestId: String,
    ): ChatViewModel {
        val vm = paymentViewModel(prefs, PaymentSeam(PEER))
        PaymentHarness.setField(vm, "restoreRecheck", MutableStateFlow(0))
        PaymentHarness.setField(vm, "pendingMessages", MutableStateFlow(listOf(nostrRequest(requestId, requestId in prefs.paid))))
        return vm
    }

    /** Two complete history passes over [txs] (the second is stable), then one more; returns the last pass's rows. */
    private suspend fun stablePasses(
        vm: ChatViewModel,
        prefs: PaymentPrefs,
        txs: List<SentTx>,
    ): List<ChatMessage> {
        var rows: List<ChatMessage> = emptyList()
        repeat(3) {
            rows =
                PendingReconcileHarness
                    .reconcileConversations(vm, prefs, txs, paidReqIds = paidRequestIds(vm), historyScanComplete = true)
                    .flatMap { it.messages }
        }
        return rows
    }

    private fun assertStaysPaid(
        prefs: PaymentPrefs,
        vm: ChatViewModel,
        rows: List<ChatMessage>,
        requestId: String,
    ) {
        assertTrue("the paid marker was withdrawn", requestId in prefs.paid)
        assertTrue("the request is no longer paid in memory", requestId in paidRequestIds(vm))
        assertEquals("the bubble offers Pay again", true, rows.single { it.id == requestId }.paymentRequest?.isPaid)
        assertTrue(
            "a note says a payment that may have gone out did not",
            prefs.pending.values.none { SysNotes.isSystemNoteId(it.id) && it.peerAddress == PEER },
        )
    }

    /** An inbound free (NOSTR) payment request row, as loadPendingMessagesFromPrefs builds it from the paid set. */
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

    /** The resource id of chat_note_fulfil_not_sent, or null while it does not exist. */
    private fun fulfilNotSentRes(): Int? =
        runCatching {
            co.electriccoin.zcash.ui.R.string::class.java
                .getField("chat_note_fulfil_not_sent")
                .getInt(null)
        }.getOrNull()

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
