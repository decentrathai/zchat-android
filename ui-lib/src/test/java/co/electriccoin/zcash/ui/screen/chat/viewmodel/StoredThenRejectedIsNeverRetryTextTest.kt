package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.model.PublicCrossingException
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.SubmitAttemptProof
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 8 D8 (found while moving the chat notices to resources) — a send that may have gone out is reported as a
 * plain failure, and the text invites the tap that pays a second time.
 *
 * These senders submit once, with no Retry row, and read the failure without asking whether the submit had
 * started:
 *
 * - the scheduled / block-locked / payment-locked / conditional message senders go through the block-aware user
 *   retry, whose MAY_HAVE_BEEN_SENT outcome they read as "not sent" and word as "Couldn't send the locked message
 *   yet — … (it'll go through once that settles) … Wait a moment and retry";
 * - "Pay to unlock" (a value transfer of the amount the sender asked for), the conditional unlock answer, an
 *   on-chain reaction, the disappearing timer and the conversation mode in a Shielded chat call the send chokepoint
 *   directly and show "Failed to unlock message", "Failed to submit answer", "Failed to send reaction", "Couldn't sync
 *   the disappearing-message timer — try again" or "Couldn't sync the mode change — try again" (each through the
 *   error mapper).
 *
 * When the SDK stored the transaction and then reported it rejected (`Failure(txIds = [T])`, which the chokepoint
 * rethrows as SubmitRejectedException(nothingStored = false) since C4), T may still be broadcast, and the tap the
 * text asks for pays again (for "Pay to unlock", the whole amount again).
 *
 * Wanted: SubmitAttemptProof.MAY_HAVE_BEEN_SENT (ChatSendCopy.PAYMENT_MAY_HAVE_BEEN_SENT for "Pay to unlock"),
 * which tells the user not to send again before checking. A refusal before the submit keeps a "not sent" text
 * (control). All addresses, keys and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoredThenRejectedIsNeverRetryTextTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun storedThenRejected(peer: String) = HandshakeSeam(peer, firstSubmitOutcome = GroupPaidOnceFixture.storedThenRejected())

    private fun errorText(vm: ChatViewModel): String {
        val state = HandshakeHarness.sendMessageState(vm)
        assertTrue("the user must be told about the failed send: $state", state is SendMessageState.Error)
        return (state as SendMessageState.Error).message
    }

    // ── the time-lock senders ────────────────────────────────────────────────────────────────────────

    private fun lockedSend(
        peer: String,
        send: (ChatViewModel, String) -> Unit,
    ) = runBlocking {
        val seam = storedThenRejected(peer)
        val vm = handshakeViewModel(HandshakePrefs(peer), seam)

        send(vm, peer)
        awaitIdle(vm)

        assertEquals("fixture: one submit reached the SDK and was stored", 1, seam.submits.get())
        assertEquals(
            "the locked message may already be on its way, but the user is asked to send it again",
            SubmitAttemptProof.MAY_HAVE_BEEN_SENT,
            errorText(vm),
        )
    }

    @Test
    fun `a scheduled message stored then rejected is may-have-been-sent`() =
        lockedSend("usyntheticd8lockscheduled001") { vm, peer ->
            vm.sendScheduledMessage(peer, "see you tomorrow", unlockTimestamp = 4_102_444_800L)
        }

    @Test
    fun `a block-locked message stored then rejected is may-have-been-sent`() =
        lockedSend("usyntheticd8lockblock0000001") { vm, peer ->
            vm.sendBlockLockedMessage(peer, "after the upgrade", unlockHeight = 3_600_000L)
        }

    @Test
    fun `a payment-locked message stored then rejected is may-have-been-sent`() =
        lockedSend("usyntheticd8lockpayment00001") { vm, peer ->
            vm.sendPaymentLockedMessage(peer, "the answer", requiredZatoshi = 50_000L)
        }

    @Test
    fun `a conditional message stored then rejected is may-have-been-sent`() =
        lockedSend("usyntheticd8lockcondition001") { vm, peer ->
            vm.sendConditionalMessage(peer, "the answer", answer = "blue", hint = "sky")
        }

    @Test
    fun `control - a locked message refused before the submit is not may-have-been-sent`() =
        runBlocking {
            val peer = "usyntheticd8lockrefused00001"
            val seam = HandshakeSeam(peer, failFirstProposalWith = PublicCrossingException(Zatoshi(90_000), Zatoshi(0)))
            val vm = handshakeViewModel(HandshakePrefs(peer), seam)

            vm.sendScheduledMessage(peer, "see you tomorrow", unlockTimestamp = 4_102_444_800L)
            awaitIdle(vm)

            assertEquals("nothing reached the SDK", 0, seam.submits.get())
            assertNotEquals(SubmitAttemptProof.MAY_HAVE_BEEN_SENT, errorText(vm))
        }

    // ── the unlocks ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pay-to-unlock payment stored then rejected is payment-may-have-been-sent`() =
        runBlocking {
            val peer = "usyntheticd8unlockpayment001"
            val seam = storedThenRejected(peer)
            val vm = handshakeViewModel(HandshakePrefs(peer), seam)

            vm.unlockPaymentMessage("synthetic-locked-txid", peer, 50_000L)
            awaitIdle(vm)

            assertEquals("fixture: one submit reached the SDK and was stored", 1, seam.submits.get())
            assertEquals(
                "the unlock payment may already be on its way, but the user is told it failed and pays again",
                ChatSendCopy.PAYMENT_MAY_HAVE_BEEN_SENT,
                errorText(vm),
            )
        }

    @Test
    fun `a conditional unlock answer stored then rejected is may-have-been-sent`() =
        runBlocking {
            val peer = "usyntheticd8unlockanswer0001"
            val seam = storedThenRejected(peer)
            val vm = handshakeViewModel(HandshakePrefs(peer), seam)

            vm.unlockConditionalMessage("synthetic-locked-txid", peer, "blue", answerHash = answerHash("blue"))
            awaitIdle(vm)

            assertEquals("fixture: one submit reached the SDK and was stored", 1, seam.submits.get())
            assertEquals(SubmitAttemptProof.MAY_HAVE_BEEN_SENT, errorText(vm))
        }

    // ── the Shielded chat's reaction and controls ────────────────────────────────────────────────────

    private suspend fun shieldedChat(peer: String): Pair<ChatViewModel, HandshakeSeam> {
        val fixture = PaidChatFixture(peer, ConversationMode.VAULT)
        val vm = fixture.viewModel()
        val seam = storedThenRejected(peer)
        AutoSpendVm.setField(vm, "createChunkedMessageProposal", useCaseOver(seam))
        AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
        return vm to seam
    }

    @Test
    fun `an on-chain reaction stored then rejected is may-have-been-sent`() =
        runBlocking {
            val peer = "usyntheticd8reaction00000001"
            val (vm, seam) = shieldedChat(peer)

            vm.sendReaction(peer, "synthetic-target-id", "+1", costConfirmed = true)
            awaitIdle(vm)

            assertEquals("fixture: one submit reached the SDK and was stored", 1, seam.submits.get())
            assertEquals(SubmitAttemptProof.MAY_HAVE_BEEN_SENT, errorText(vm))
        }

    @Test
    fun `a Shielded timer stored then rejected is may-have-been-sent, not try again`() =
        runBlocking {
            val peer = "usyntheticd8timer00000000001"
            val (vm, seam) = shieldedChat(peer)

            vm.setDisappearingTtl(peer, 3_600L, costConfirmed = true)
            awaitIdle(vm)

            assertEquals("fixture: one submit reached the SDK and was stored", 1, seam.submits.get())
            assertEquals(SubmitAttemptProof.MAY_HAVE_BEEN_SENT, errorText(vm))
        }

    @Test
    fun `a Shielded mode change stored then rejected is may-have-been-sent, not try again`() =
        runBlocking {
            val peer = "usyntheticd8mode000000000001"
            val (vm, seam) = shieldedChat(peer)

            vm.changeConversationMode(peer, ConversationMode.TUNNEL, costConfirmed = true)
            awaitIdle(vm)

            assertEquals("fixture: one submit reached the SDK and was stored", 1, seam.submits.get())
            assertEquals(SubmitAttemptProof.MAY_HAVE_BEEN_SENT, errorText(vm))
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    /** The real send chokepoint over [seam], with a synthetic account whose Ironwood can pay. */
    private suspend fun useCaseOver(seam: ProposalDataSource): CreateChunkedMessageProposalUseCase {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        return CreateChunkedMessageProposalUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = seam,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ).also(SendHarnessScopes.current()::own),
            accountDataSource = accounts,
            navigationRouter = Zc101RecordingNavigationRouter(),
            biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
        )
    }

    /** The hash a conditional message carries for [answer] (what verifyConditionalAnswer computes). */
    private fun answerHash(answer: String): String =
        co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
            .generateAddressHash(answer.lowercase().trim())
}
