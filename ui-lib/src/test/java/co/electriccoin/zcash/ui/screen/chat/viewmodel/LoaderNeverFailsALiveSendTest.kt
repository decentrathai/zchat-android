package co.electriccoin.zcash.ui.screen.chat.viewmodel

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
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1071 — the pending-row loader never marks a send another screen is still making as failed, and Retry never takes
 * such a send over.
 *
 * Each screen has its own ChatViewModel over the one store (the list and the open chat, or a chat opened again while
 * its first message still waits on the process-wide send scope). At c83565631 a view model created while another one
 * is sending reads the stored in-flight row and maps it as orphaned: FAILED, "Not sent — ZChat was closed before this
 * message went out. Tap Retry to send it." and a Retry button. Retry is accepted and the second screen builds and pays
 * its own transaction for the message, while the first screen's send is still running (its submit marker check then
 * stops the first one, so one payment is made, by the screen the user did not send from, and the user was told
 * something false about a message that was going out). Wanted: while the sending screen is alive the other screens
 * show the row as sending, with the markers it has in the store, and their Retry is refused with nothing built or paid.
 * A row whose sender is gone (no live sender in this process) still reloads as today: FAILED, with Retry.
 *
 * Drives the real sendMessage / retryMessage / loader through the real use case and repository; only the store, the
 * proposal and submit seam and the wallet are faked (PaymentSendHarness). All addresses are synthetic; no amount
 * leaves the fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoaderNeverFailsALiveSendTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1071livesend0001"
        const val CHOSEN = 50_000L
    }

    @Before
    fun setUp() {
        resetLiveSenders()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        resetLiveSenders()
    }

    @Test
    fun `a send held before its submit in the chat screen stays sending in the list, and the list cannot take it over`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val gated = GatedProposals(seam)
            val chat = PaymentHarness.paymentViewModel(prefs, seam)
            PaymentHarness.setField(chat, "createChunkedMessageProposal", useCase(gated))
            assertTrue(chat.sendMessage(PEER, "synthetic live send before submit", CHOSEN))
            withTimeout(PaymentHarness.TIMEOUT_MS) { gated.firstCreateEntered.await() }
            val id =
                prefs.pending.values
                    .single()
                    .id
            try {
                val list = PaymentHarness.paymentViewModel(prefs, seam)
                PaymentHarness.loadPendingMessagesFromPrefs(list)
                assertStillSending(PaymentHarness.pendingMessages(list).single { it.id == id })

                assertFalse("Retry took over a send another screen is making", list.retryMessage(PEER, id))
                HandshakeHarness.awaitIdle(list)
                assertEquals("the list built a second transaction for a message being sent", 1, gated.creates.get())
                assertEquals("the list paid for a message being sent", 0, seam.submits.get())
            } finally {
                gated.gate.complete(Unit)
            }
            HandshakeHarness.awaitIdle(chat)
            assertEquals("one payment for one message", 1, seam.submits.get())
            assertTrue(
                "the chat's own send went out",
                prefs.pending
                    .getValue(id)
                    .submittedTxIds
                    .isNotEmpty(),
            )
            assertNotEquals(
                "the chat's send was stopped by another screen's Retry",
                SendMessageState.Error(ChatSendCopy.RETRY_ALREADY_CLAIMED),
                PaymentHarness.sendMessageState(chat),
            )
        }

    @Test
    fun `a send whose submit is running in the chat screen is not shown as not sent in the list`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, holdFirstSubmit = true)
            val chat = PaymentHarness.paymentViewModel(prefs, seam)
            assertTrue(chat.sendMessage(PEER, "synthetic live send in submit", CHOSEN))
            withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            val id =
                prefs.pending.values
                    .single()
                    .id
            try {
                val list = PaymentHarness.paymentViewModel(prefs, seam)
                PaymentHarness.loadPendingMessagesFromPrefs(list)
                val row = PaymentHarness.pendingMessages(list).single { it.id == id }
                assertStillSending(row)
                assertEquals(
                    "the submit marker is shown as stored",
                    prefs.pending.getValue(id).submitStartedAtMillis,
                    row.submitStartedAtMillis,
                )

                assertFalse(list.retryMessage(PEER, id))
                HandshakeHarness.awaitIdle(list)
                assertEquals("the list paid for a message being sent", 1, seam.submits.get())
            } finally {
                seam.release.complete(Unit)
                withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitDone.await() }
            }
            HandshakeHarness.awaitIdle(chat)
            assertEquals("one payment for one message", 1, seam.submits.get())
        }

    @Test
    fun `a send queued behind another in the chat screen stays sending in the list, and the list cannot take it over`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val chat = PaymentHarness.paymentViewModel(prefs, seam)
            // The chat is busy with an earlier send: this message waits in the chat's queue, stored as sending.
            PaymentHarness.setSendMessageState(chat, SendMessageState.Sending)
            assertTrue(chat.sendMessage(PEER, "synthetic queued live send", CHOSEN))
            val id =
                prefs.pending.values
                    .single()
                    .id

            val list = PaymentHarness.paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(list)
            assertStillSending(PaymentHarness.pendingMessages(list).single { it.id == id })
            assertFalse("Retry took over a message waiting in another screen's queue", list.retryMessage(PEER, id))
            HandshakeHarness.awaitIdle(list)
            assertEquals("the list built a transaction for a queued message", 0, seam.proposalUris.size)
            assertEquals("the list paid for a queued message", 0, seam.submits.get())
            val stored = prefs.pending.getValue(id)
            assertTrue("the queued row stays in flight in the store", stored.isPending)
            assertNull("the list claimed the queued row", stored.submitStartedAtMillis)
        }

    @Test
    fun `guard - a stored in-flight row with no live sender in this process still reloads as not sent, with Retry`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val id = "pending_1071000000001"
            prefs.pending[id] =
                ZchatPreferences.PendingMessageData(
                    id = id,
                    text = "synthetic orphaned send",
                    timestampMillis = 1_792_000_000_000L,
                    peerAddress = PEER,
                    sendAmountZatoshi = CHOSEN,
                    submitTracked = true,
                )
            val reopened = PaymentHarness.paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            val row = PaymentHarness.pendingMessages(reopened).single { it.id == id }
            assertEquals(MessageStatus.FAILED, row.status)
            assertFalse(row.isPending)
            assertEquals(ChatSendCopy.FAILED_APP_CLOSED, row.failureReason)
            assertEquals(0, seam.submits.get())
        }

    private fun assertStillSending(row: ChatMessage) {
        assertNotEquals("a send another screen is making was marked failed", MessageStatus.FAILED, row.status)
        assertTrue("a send another screen is making lost its in-flight mark", row.isPending)
        assertEquals(MessageStatus.SENDING, row.effectiveStatus)
        assertNull("a send another screen is making was said to be closed before it went out", row.failureReason)
    }

    /** The process-wide live-sender registry's reset, when this build has one (none at the RED commit). */
    private fun resetLiveSenders() {
        val registry =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.viewmodel.LiveSenders") }.getOrNull() ?: return
        val instance = registry.getField("INSTANCE").get(null)
        registry.getMethod("resetForTest").invoke(instance)
    }

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

    /** [PaymentSeam] with its first proposal held open (the send is built, its submit not started) until [gate]. */
    internal class GatedProposals(
        private val inner: PaymentSeam,
    ) : ProposalDataSource by inner {
        val creates = AtomicInteger(0)
        val firstCreateEntered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            if (creates.incrementAndGet() == 1) {
                firstCreateEntered.complete(Unit)
                gate.await()
            }
            return inner.createZip321Proposal(account, zip321Uri, allowMultiPayment)
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: OnTransactionsStored,
        ): SubmitResult = inner.submitTransaction(proposal, usk, onStored)
    }
}
