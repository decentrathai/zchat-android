package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModelStore
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1071, independent review of c38c702b7, findings 1 to 3 (P2): what happens to a row once the screen that was
 * sending it lets it go.
 *
 * Every screen has its own ChatViewModel over the one store; LiveSenders keeps a row "sending" in the other screens and
 * refuses their Retry while a screen of this process still sends it.
 *
 * (1) A screen B that loaded the row while it was live keeps it SENDING for good once the sender releases it (its run
 * failed, or it waited in a queue whose screen was closed): B's loader runs once, and the sender updates only its own
 * bubble. B's Retry is then refused (the row is not FAILED there) until B is recreated. Wanted: B shows FAILED, with
 * the stored failure or "ZChat was closed", and accepts Retry, without being recreated.
 * (2) onCleared releases the rows waiting in the closed screen's queue but leaves them in that queue; a run of that
 * screen still on the process-wide scope ends with processNextQueuedMessage and starts a send of a row it released
 * (while another screen offers its Retry). Wanted: a closed screen never sends a row it released.
 * (3) Each release path, with a new screen B built over the same store after the release: FAILED, the reason, Retry
 * accepted, one payment, made by B (guards of c38c702b7).
 *
 * Drives the real sendMessage / retryMessage / loader / queue through the real use case and repository; only the
 * store, the proposal and submit seam and the wallet are faked (PaymentSendHarness). Addresses are synthetic; no
 * amount leaves the fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSendReleaseReachesOtherScreensTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private companion object {
        const val PEER = "usyntheticzc1071release0001"
        const val CHOSEN = 50_000L
        const val SETTLE_MS = 2_000L
        val waitingForBlock = SendMessageState.Success(ChatSendCopy.QUEUED_WAITING_FOR_BLOCK)
        val timedOut = SendMessageState.Error(ChatSendCopy.QUEUE_TIMED_OUT)
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        // The process-wide send scope belongs to this test (cancelled with its other jobs).
        processScopeField().set(null, sendScopes.heldAcceptScope)
    }

    @After
    fun tearDown() {
        kexOwedPeers().remove(PEER)
        sendScopes.close()
        Dispatchers.resetMain()
        LiveSenders.resetForTest()
    }

    // ── finding 1: a screen that already shows the row ──────────────────────────────────────────────

    @Test
    fun `finding 1 - a screen that loaded a live send shows the failure once the sender's run fails, and its Retry is accepted`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val proposals = ScriptedProposals(seam, holdCreate = 1, failCreate = 1)
            val chat = screen(prefs, seam, proposals)
            assertTrue(chat.sendMessage(PEER, "synthetic send that fails", CHOSEN))
            withTimeout(PaymentHarness.TIMEOUT_MS) { proposals.heldEntered.await() }
            val id = onlyRowId(prefs)

            val list = screen(prefs, seam, proposals)
            PaymentHarness.loadPendingMessagesFromPrefs(list)
            assertStillSending("fixture sanity", shown(list, id))

            proposals.gate.complete(Unit)
            assertTrue(
                "fixture sanity: the chat's run failed and stored the row FAILED",
                PaymentHarness.eventually(SETTLE_MS) { prefs.pending.getValue(id).status == MessageStatus.FAILED.name },
            )
            assertTrue("fixture sanity: the failed run still holds the row live", released(id))
            val stored = prefs.pending.getValue(id)

            PaymentHarness.eventually(SETTLE_MS) { shown(list, id).status == MessageStatus.FAILED }
            val onList = shown(list, id)
            assertEquals(
                "the list still shows a send the chat gave up on as sending (status ${onList.status}, pending ${onList.isPending})",
                MessageStatus.FAILED,
                onList.status,
            )
            assertFalse(onList.isPending)
            assertEquals("the list shows the failure the chat stored", stored.failureReason, onList.failureReason)
            retryPaysOnceFrom(list, prefs, seam, id)
        }

    @Test
    fun `finding 1 - a screen that loaded a send queued in another screen shows it not sent once that screen closes, and accepts Retry`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val proposals = ScriptedProposals(seam, failCreate = 1, failure = { HandshakeHarness.stillConfirming() })
            val chat = screen(prefs, seam, proposals)
            val id = queueOne(chat, prefs, "synthetic send waiting for funds")
            // The queue runs it; its funds are still confirming, so it goes back into the chat's queue for the next block.
            PaymentHarness.setSendMessageState(chat, SendMessageState.Idle)
            processNextQueuedMessage(chat)
            PaymentHarness.eventually(SETTLE_MS) { PaymentHarness.sendMessageState(chat) == waitingForBlock }
            assertEquals(
                "fixture sanity: the send waits in the chat's queue for a block",
                waitingForBlock,
                PaymentHarness.sendMessageState(chat),
            )
            assertTrue("fixture sanity: the waiting row is live", LiveSenders.isLive(id))

            val list = screen(prefs, seam, proposals)
            PaymentHarness.loadPendingMessagesFromPrefs(list)
            assertStillSending("fixture sanity", shown(list, id))

            closeScreen(chat)
            assertTrue("fixture sanity: the closed chat still holds the row live", released(id))

            PaymentHarness.eventually(SETTLE_MS) { shown(list, id).status == MessageStatus.FAILED }
            val onList = shown(list, id)
            assertEquals(
                "the list still shows as sending a message no screen sends any more (status ${onList.status}, pending ${onList.isPending})",
                MessageStatus.FAILED,
                onList.status,
            )
            assertFalse(onList.isPending)
            assertEquals(
                "the list says why it was not sent",
                prefs.pending.getValue(id).failureReason ?: ChatSendCopy.FAILED_APP_CLOSED,
                onList.failureReason,
            )
            assertEquals("fixture sanity: only the chat's failed proposal so far", 1, proposals.creates.get())
            retryPaysOnceFrom(list, prefs, seam, id)
        }

    // ── finding 2: a closed screen never sends a row it released ────────────────────────────────────

    @Test
    fun `finding 2 - a closed screen never sends a row it released when its run on the process-wide scope reaches its queue`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val proposals = ScriptedProposals(seam)
            val chat = screen(prefs, seam, proposals)
            val id = queueOne(chat, prefs, "synthetic send queued in a closed chat")
            closeScreen(chat)
            assertFalse("fixture sanity: onCleared released the queued row", LiveSenders.isLive(id))
            val released = prefs.pending.getValue(id)

            // The chat's key exchange is still owed, so its sends run on the process-wide scope; one of them ends now and
            // calls processNextQueuedMessage, as every run's end does.
            kexOwedPeers().add(PEER)
            try {
                processNextQueuedMessage(chat)
                awaitProcessScope()
            } finally {
                kexOwedPeers().remove(PEER)
            }

            assertEquals("the closed chat built a transaction for a row it released", 0, proposals.creates.get())
            assertEquals("the closed chat paid for a row it released", 0, seam.submits.get())
            assertEquals("the closed chat took up again a row it released", released, prefs.pending.getValue(id))
            assertFalse("the closed chat made a row it released live again", LiveSenders.isLive(id))

            val list = screen(prefs, seam, proposals)
            PaymentHarness.loadPendingMessagesFromPrefs(list)
            assertNotSent(shown(list, id), ChatSendCopy.FAILED_APP_CLOSED)
            retryPaysOnceFrom(list, prefs, seam, id)
        }

    // ── finding 3: every release path, seen by a new screen (guards) ───────────────────────────────

    @Test
    fun `finding 3 - onCleared releases a queued row - a new screen shows it not sent and its Retry pays once`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val proposals = ScriptedProposals(seam)
            val chat = screen(prefs, seam, proposals)
            val id = queueOne(chat, prefs, "synthetic queued send, screen closed")
            closeScreen(chat)

            val list = screen(prefs, seam, proposals)
            PaymentHarness.loadPendingMessagesFromPrefs(list)
            assertNotSent(shown(list, id), ChatSendCopy.FAILED_APP_CLOSED)
            retryPaysOnceFrom(list, prefs, seam, id)
        }

    @Test
    fun `finding 3 - a queue retry that times out releases its row - a new screen shows the stored failure and its Retry pays once`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val proposals = ScriptedProposals(seam, failCreate = 1, failure = { HandshakeHarness.stillConfirming() })
            val chat = screen(prefs, seam, proposals)
            val id = queueOne(chat, prefs, "synthetic send whose block never came")
            PaymentHarness.setSendMessageState(chat, SendMessageState.Idle)
            processNextQueuedMessage(chat)
            PaymentHarness.eventually(SETTLE_MS) { PaymentHarness.sendMessageState(chat) == waitingForBlock }
            assertEquals(
                "fixture sanity: the send waits in the chat's queue for a block",
                waitingForBlock,
                PaymentHarness.sendMessageState(chat),
            )

            // No block comes: the queue's wait times out (virtual time on the screen's dispatcher).
            scheduler.advanceTimeBy(queueRetryTimeoutMs() + 1)
            scheduler.runCurrent()
            PaymentHarness.eventually(SETTLE_MS) { PaymentHarness.sendMessageState(chat) == timedOut }
            assertEquals(
                "fixture sanity: the wait timed out",
                timedOut,
                PaymentHarness.sendMessageState(chat),
            )
            assertTrue("the timed-out row is still held live", released(id))

            val list = screen(prefs, seam, proposals)
            PaymentHarness.loadPendingMessagesFromPrefs(list)
            assertNotSent(shown(list, id), ChatSendCopy.FAILED_FUNDS_CONFIRMING)
            retryPaysOnceFrom(list, prefs, seam, id)
        }

    @Test
    fun `finding 3 - a run that cannot start on a cleared screen releases its row - a new screen shows it not sent, Retry pays once`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val proposals = ScriptedProposals(seam)
            val chat = screen(prefs, seam, proposals)
            val id = queueOne(chat, prefs, "synthetic queued send, run after close")
            closeScreen(chat)
            // A run of the closed chat ends and reaches its queue; the message's run would take the screen's own (cancelled)
            // scope, so it cannot start.
            processNextQueuedMessage(chat)
            awaitProcessScope()
            assertFalse("the run that could not start still holds the row live", LiveSenders.isLive(id))
            assertEquals("the closed chat built a transaction", 0, proposals.creates.get())

            val list = screen(prefs, seam, proposals)
            PaymentHarness.loadPendingMessagesFromPrefs(list)
            assertNotSent(shown(list, id), ChatSendCopy.FAILED_APP_CLOSED)
            retryPaysOnceFrom(list, prefs, seam, id)
        }

    @Test
    fun `finding 3 - a run cancelled by its screen's close releases its row - a new screen shows it not sent and its Retry pays once`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val proposals = ScriptedProposals(seam)
            val chat = screen(prefs, seam, proposals)
            // The run waits for the conversation-id lock, before its proposal (whose build and submit are NonCancellable, P2a,
            // and so outlive a close): there the close cancels it.
            val convIdLock =
                ChatViewModel::class.java
                    .getDeclaredField("convIdMutex")
                    .apply { isAccessible = true }
                    .get(chat) as Mutex
            convIdLock.lock()
            try {
                assertTrue(chat.sendMessage(PEER, "synthetic send cancelled by a close", CHOSEN))
                assertTrue(
                    "fixture sanity: the run stored its row",
                    PaymentHarness.eventually(SETTLE_MS) { prefs.pending.size == 1 },
                )
                val id = onlyRowId(prefs)
                assertTrue("fixture sanity: the running send is live", LiveSenders.isLive(id))
                closeScreen(chat)
                assertTrue(
                    "the cancelled run still holds the row live",
                    released(id),
                )
                assertEquals("fixture sanity: the cancelled run built nothing", 0, proposals.creates.get())

                val list = screen(prefs, seam, proposals)
                PaymentHarness.loadPendingMessagesFromPrefs(list)
                assertNotSent(
                    shown(list, id),
                    prefs.pending.getValue(id).failureReason ?: ChatSendCopy.FAILED_APP_CLOSED,
                )
                retryPaysOnceFrom(list, prefs, seam, id)
            } finally {
                convIdLock.unlock()
            }
        }

    @Test
    fun `finding 3 - a failed run releases its row - a new screen shows the stored failure and its Retry pays once`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val proposals = ScriptedProposals(seam, failCreate = 1)
            val chat = screen(prefs, seam, proposals)
            assertTrue(chat.sendMessage(PEER, "synthetic send that fails at once", CHOSEN))
            val id = onlyRowId(prefs)
            assertTrue(
                "fixture sanity: the run failed and stored the row FAILED",
                PaymentHarness.eventually(SETTLE_MS) { prefs.pending.getValue(id).status == MessageStatus.FAILED.name },
            )
            assertTrue("the failed run still holds the row live", released(id))

            val list = screen(prefs, seam, proposals)
            PaymentHarness.loadPendingMessagesFromPrefs(list)
            val stored = prefs.pending.getValue(id).failureReason
            assertNotNull("fixture sanity: the failure is stored with its reason", stored)
            assertNotSent(shown(list, id), stored!!)
            retryPaysOnceFrom(list, prefs, seam, id)
        }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    /** A chat screen whose sends go through [proposals] (one counter for every screen of the test). */
    private suspend fun screen(
        prefs: PaymentPrefs,
        seam: PaymentSeam,
        proposals: ScriptedProposals,
    ): ChatViewModel =
        PaymentHarness.paymentViewModel(prefs, seam).also {
            PaymentHarness.setField(it, "createChunkedMessageProposal", useCase(proposals))
        }

    /** [text] is typed while the chat is busy with an earlier send: it waits in the chat's queue, stored as sending. */
    private fun queueOne(
        chat: ChatViewModel,
        prefs: PaymentPrefs,
        text: String,
    ): String {
        PaymentHarness.setSendMessageState(chat, SendMessageState.Sending)
        assertTrue(chat.sendMessage(PEER, text, CHOSEN))
        val id = onlyRowId(prefs)
        assertTrue("fixture sanity: the queued row is live", LiveSenders.isLive(id))
        return id
    }

    /** The screen goes away: its ViewModelStore is cleared (scope cancelled, then onCleared). */
    private fun closeScreen(vm: ChatViewModel) {
        ViewModelStore().apply { put("chat", vm) }.clear()
    }

    /** Retry from [screen] is accepted and pays the message once, from that screen: one payment in the whole test. */
    private suspend fun retryPaysOnceFrom(
        screen: ChatViewModel,
        prefs: PaymentPrefs,
        seam: PaymentSeam,
        id: String,
    ) {
        assertTrue(
            "Retry was refused for a message no screen sends any more (state ${PaymentHarness.sendMessageState(screen)})",
            screen.retryMessage(PEER, id),
        )
        assertTrue("Retry did not pay", PaymentHarness.eventually(SETTLE_MS) { seam.submits.get() >= 1 })
        HandshakeHarness.awaitIdle(screen)
        assertEquals("one payment for one message", 1, seam.submits.get())
        assertTrue(
            "the Retry's payment is recorded on the row",
            prefs.pending
                .getValue(id)
                .submittedTxIds
                .isNotEmpty(),
        )
    }

    private fun assertStillSending(
        what: String,
        row: ChatMessage,
    ) {
        assertNotEquals("$what: a live send was marked failed", MessageStatus.FAILED, row.status)
        assertTrue("$what: a live send lost its in-flight mark", row.isPending)
    }

    private fun assertNotSent(
        row: ChatMessage,
        reason: String,
    ) {
        assertEquals("a released row is not shown as not sent", MessageStatus.FAILED, row.status)
        assertFalse("a released row is still shown in flight", row.isPending)
        assertEquals("a released row does not say why it was not sent", reason, row.failureReason)
    }

    /** The one message row of the test (the store's notes are kept apart). */
    private fun onlyRowId(prefs: PaymentPrefs): String =
        prefs.pending.values
            .single()
            .id

    /** The row as [screen] shows it. */
    private fun shown(
        screen: ChatViewModel,
        id: String,
    ): ChatMessage = PaymentHarness.pendingMessages(screen).single { it.id == id }

    /** No screen of this process holds [id] live (polled: a run releases it in its finally). */
    private suspend fun released(id: String): Boolean = PaymentHarness.eventually(SETTLE_MS) { !LiveSenders.isLive(id) }

    private fun processNextQueuedMessage(vm: ChatViewModel) {
        ChatViewModel::class.java
            .getDeclaredMethod("processNextQueuedMessage")
            .apply { isAccessible = true }
            .invoke(vm)
    }

    /** Lets the runs on the process-wide send scope end (bounded; a run left waiting is cancelled in tearDown). */
    private suspend fun awaitProcessScope() {
        val job = sendScopes.heldAcceptScope.coroutineContext[Job]!!
        PaymentHarness.eventually(SETTLE_MS) { job.children.none { it.isActive } }
    }

    private fun processScopeField(): java.lang.reflect.Field =
        ChatViewModel::class.java
            .getDeclaredField("heldGroupAcceptScope")
            .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun kexOwedPeers(): MutableSet<String> =
        ChatViewModel::class.java
            .getDeclaredField("kexOwedPeers")
            .apply { isAccessible = true }
            .get(null) as MutableSet<String>

    private fun queueRetryTimeoutMs(): Long =
        runCatching {
            ChatViewModel::class.java
                .getDeclaredField("QUEUE_RETRY_TIMEOUT_MS")
                .apply { isAccessible = true }
                .getLong(null)
        }.getOrDefault(300_000L)

    private suspend fun useCase(source: ProposalDataSource): CreateChunkedMessageProposalUseCase {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        return CreateChunkedMessageProposalUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = source,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ).also(sendScopes::own),
            accountDataSource = accounts,
            navigationRouter = Zc101RecordingNavigationRouter(),
            biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
        )
    }

    /**
     * [PaymentSeam] whose proposal number [holdCreate] waits for [gate] and whose proposal number [failCreate] throws
     * [failure] (nothing built, nothing sent). [creates] counts every proposal any screen of the test asked for.
     */
    private class ScriptedProposals(
        private val inner: PaymentSeam,
        private val holdCreate: Int? = null,
        private val failCreate: Int? = null,
        private val failure: () -> Exception = { IllegalStateException("synthetic proposal failure") },
    ) : ProposalDataSource by inner {
        val creates = AtomicInteger(0)
        val heldEntered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            val n = creates.incrementAndGet()
            if (n == holdCreate) {
                heldEntered.complete(Unit)
                gate.await()
            }
            if (n == failCreate) throw failure()
            return inner.createZip321Proposal(account, zip321Uri, allowMultiPayment)
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: OnTransactionsStored,
        ): SubmitResult = inner.submitTransaction(proposal, usk, onStored)
    }
}
