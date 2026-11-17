package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModelStore
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
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

/**
 * ZC-1276 (owner's Fold, release 2.13.0): a plain message stayed "Sending" for 35+ minutes after the chat was left and
 * opened again; the wallet shows no outgoing transaction and an unchanged balance, so nothing was paid.
 *
 * What is pinned here: a send parked before its submit started runs its build inside NonCancellable with no time limit
 * (doSendMessage and sendReply, P2a). Leaving the chat cannot end such a run, so LiveSenders holds the row live for good:
 * every screen shows it Sending and refuses its Retry, with nothing ever paid. Wanted, within an hour: the row is either
 * sent once or settled as a clear not-sent state with a working Retry, and a build that wakes up late pays nothing more.
 *
 * Virtual time: the screen's and the process-wide send scope's dispatcher is the test scheduler, so the limit must run on
 * Dispatchers.Main. A timeout placed inside the build's own withContext(Dispatchers.Default) is real time and is not seen.
 *
 * Guards (green before the fix, and must stay so): a run that waits for a block in the screen's queue is let go when the
 * chat closes; and a stalled SUBMIT may have gone out, so no limit may ever make it retriable (money-path rule 3).
 *
 * Real ChatViewModel send / loader / Retry over the fakes of PaymentSendHarness. Addresses are synthetic; no amount leaves
 * the fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StalledSendNeverStaysSendingTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private companion object {
        const val PEER = "usyntheticzc1276stalled0001"
        const val PLAIN = ChatViewModel.DEFAULT_MESSAGE_AMOUNT
        const val SETTLE_MS = 2_000L
        const val ONE_HOUR_MS = 3_600_000L
        val waitingForBlock = SendMessageState.Success(ChatSendCopy.QUEUED_WAITING_FOR_BLOCK)
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        // The process-wide send scope belongs to this test and runs on its scheduler.
        ChatViewModel::class.java
            .getDeclaredField("heldGroupAcceptScope")
            .apply { isAccessible = true }
            .set(null, sendScopes.heldAcceptScope)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        LiveSenders.resetForTest()
    }

    // ── RED: a stalled build, nothing submitted ──────────────────────────────────────────────────────────

    @Test
    fun `a message whose build stalled does not stay sending after the chat is left and opened again`() =
        runBlocking {
            stalledBuildSettles(close = true) { it.sendMessage(PEER, "synthetic zc1276 plain message", PLAIN) }
        }

    @Test
    fun `a reply whose build stalled does not stay sending after the chat is left and opened again`() =
        runBlocking {
            stalledBuildSettles(close = true) {
                it.sendReply(PEER, "synthetic zc1276 reply", "nmsg-syntheticzc1276target", "synthetic target", PLAIN)
            }
        }

    @Test
    fun `a message whose build stalled does not stay sending in a chat that stays open`() =
        runBlocking {
            stalledBuildSettles(close = false) { it.sendMessage(PEER, "synthetic zc1276 chat left open", PLAIN) }
        }

    // ── guards ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `guard - a message waiting for a block when the chat is left settles as not sent, with one payment on Retry`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, failFirstProposalWith = HandshakeHarness.stillConfirming())
            val chat = PaymentHarness.paymentViewModel(prefs, seam)
            // Typed while the chat is busy, so it waits in the queue; its run then meets funds still confirming.
            PaymentHarness.setSendMessageState(chat, SendMessageState.Sending)
            assertTrue(chat.sendMessage(PEER, "synthetic zc1276 waits for a block", PLAIN))
            PaymentHarness.setSendMessageState(chat, SendMessageState.Idle)
            ChatViewModel::class.java
                .getDeclaredMethod("processNextQueuedMessage")
                .apply { isAccessible = true }
                .invoke(chat)
            assertTrue(
                "fixture sanity: the send waits in the chat's queue for a block",
                PaymentHarness.eventually(SETTLE_MS) { PaymentHarness.sendMessageState(chat) == waitingForBlock },
            )
            val id = onlyRowId(prefs)

            closeScreen(chat)
            val reopened = PaymentHarness.paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            scheduler.advanceTimeBy(ONE_HOUR_MS)
            scheduler.runCurrent()

            assertFalse("no screen sends the row, yet it is held live", LiveSenders.isLive(id))
            val row = shown(reopened, id)
            assertEquals("a row nobody sends is not shown as not sent", MessageStatus.FAILED, row.status)
            assertFalse("a row nobody sends is still shown in flight", row.isPending)
            assertEquals("fixture sanity: nothing paid before Retry", 0, seam.submits.get())
            assertTrue("Retry was refused", reopened.retryMessage(PEER, id))
            assertTrue("Retry did not pay", PaymentHarness.eventually(SETTLE_MS) { seam.submits.get() == 1 })
        }

    @Test
    fun `guard - a stalled submit may have gone out, so no time limit ever offers a Retry that pays again`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, holdFirstSubmit = true)
            val chat = PaymentHarness.paymentViewModel(prefs, seam)
            assertTrue(chat.sendMessage(PEER, "synthetic zc1276 stalled submit", PLAIN))
            withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            val id = onlyRowId(prefs)
            try {
                closeScreen(chat)
                val reopened = PaymentHarness.paymentViewModel(prefs, seam)
                PaymentHarness.loadPendingMessagesFromPrefs(reopened)
                scheduler.advanceTimeBy(ONE_HOUR_MS)
                scheduler.runCurrent()

                assertFalse("Retry was accepted for a send whose submit may have gone out", reopened.retryMessage(PEER, id))
                HandshakeHarness.awaitIdle(reopened)
                assertEquals("a second payment for one message", 1, seam.submits.get())
            } finally {
                seam.release.complete(Unit)
                withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitDone.await() }
            }
            assertEquals("a second payment for one message", 1, seam.submits.get())
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * [send] parks in its proposal build (nothing submitted). An hour later, on a chat left and opened again ([close]) or
     * on the same open chat, the row is settled, and the stalled build waking up afterwards pays nothing more.
     */
    private suspend fun stalledBuildSettles(
        close: Boolean,
        send: (ChatViewModel) -> Boolean,
    ) {
        val prefs = PaymentPrefs(PEER)
        val seam = PaymentSeam(PEER)
        val build = LoaderNeverFailsALiveSendTest.GatedProposals(seam)
        val chat = screen(prefs, seam, build)
        assertTrue("fixture sanity: the send was refused", send(chat))
        withTimeout(PaymentHarness.TIMEOUT_MS) { build.firstCreateEntered.await() }
        val id = onlyRowId(prefs)
        assertTrue("fixture sanity: the running send is live", LiveSenders.isLive(id))
        assertEquals("fixture sanity: nothing paid", 0, seam.submits.get())

        try {
            val shownOn =
                if (close) {
                    closeScreen(chat)
                    screen(prefs, seam, build).also { PaymentHarness.loadPendingMessagesFromPrefs(it) }
                } else {
                    chat
                }
            scheduler.advanceTimeBy(ONE_HOUR_MS)
            scheduler.runCurrent()
            val settled =
                PaymentHarness.eventually(SETTLE_MS) {
                    shown(shownOn, id).status == MessageStatus.FAILED || seam.submits.get() > 0
                }
            val row = shown(shownOn, id)
            assertTrue(
                "after an hour a send that was never submitted still shows Sending with nothing paid " +
                    "(status ${row.status}, pending ${row.isPending}, live ${LiveSenders.isLive(id)}, submits ${seam.submits.get()})",
                settled,
            )

            if (seam.submits.get() == 0) {
                assertFalse("a row shown as not sent is still in flight", row.isPending)
                assertTrue("a row shown as not sent does not say why", !row.failureReason.isNullOrBlank())
                assertFalse("a row shown as not sent is still held live, so its Retry is refused", LiveSenders.isLive(id))
                assertTrue("Retry was refused for a row shown as not sent", shownOn.retryMessage(PEER, id))
                assertTrue("Retry did not pay", PaymentHarness.eventually(SETTLE_MS) { seam.submits.get() >= 1 })
            }

            // The stalled build wakes up after the row was settled or sent again.
            build.gate.complete(Unit)
            PaymentHarness.eventually(SETTLE_MS) { seam.submits.get() > 1 }
            assertEquals("one message was paid twice after its stalled build woke up", 1, seam.submits.get())
        } finally {
            // Never left parked for the teardown: the fakes pay nothing real.
            build.gate.complete(Unit)
        }
    }

    /** A chat screen whose sends go through [source] (one stalled build for every screen of the test). */
    private suspend fun screen(
        prefs: PaymentPrefs,
        seam: PaymentSeam,
        source: ProposalDataSource,
    ): ChatViewModel =
        PaymentHarness.paymentViewModel(prefs, seam).also {
            PaymentHarness.setField(it, "createChunkedMessageProposal", useCase(source))
        }

    /** The screen goes away: its ViewModelStore is cleared (scope cancelled, then onCleared). */
    private fun closeScreen(vm: ChatViewModel) {
        ViewModelStore().apply { put("chat", vm) }.clear()
    }

    private fun onlyRowId(prefs: PaymentPrefs): String =
        prefs.pending.values
            .single()
            .id

    private fun shown(
        screen: ChatViewModel,
        id: String,
    ): ChatMessage = PaymentHarness.pendingMessages(screen).single { it.id == id }

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
}
