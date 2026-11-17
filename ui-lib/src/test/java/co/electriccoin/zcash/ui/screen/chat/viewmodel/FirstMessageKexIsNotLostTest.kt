package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.usecase.zip321Amounts
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 9 R9-2 (REVIEW_R8 F4, device D-F7, LEDGER row 6): the key exchange quoted with a first message is lost.
 *
 * The first on-chain message to a new contact is asked as PaidAction.FIRST_MESSAGE: "2 transactions, the key exchange
 * goes first, then the message". After the confirm, doSendMessage fires sendKEXMessage fire-and-forget on the chat
 * screen's viewModelScope and builds the message at once, so the message takes the spendable note and the KEX waits for
 * a block. Leaving the chat clears that screen's ViewModel, which cancels the KEX in its wait. sendKEXMessage had
 * already stored our key, so the trigger (no key of ours for this contact) never fires again and nothing re-arms it:
 * on the phone the KEX never went, both sides showed "Extra encryption pending", and recovery cost 3 more paid
 * transactions.
 *
 * Must hold:
 * 1. A confirmed FIRST_MESSAGE whose KEX is interrupted in its block wait (the chat screen closed, or the process
 *    ended) still sends exactly one KEX once a new screen opens the chat and a block lands, and asks no second paid
 *    question for it (not the first-message question, not the automatic-setup consent: the user already confirmed
 *    this KEX in the first-message quote).
 * 2. In a confirmed FIRST_MESSAGE the KEX goes first and the message waits for it.
 * 3. Controls: a KEX that was sent, or that may have been sent, is never sent a second time.
 *
 * Drives the real ChatViewModel send path (sendMessage, confirmPaidAction, doSendMessage) through the real use case
 * and repository ([HandshakeHarness.chatViewModelWith]) with the real conversation-id store ([RealConversationIds]).
 * The preferences fake ([FirstMessagePrefs]) is one store shared by every ViewModel, like the app's singleton; the
 * seam ([KexOrderSeam]) tells the KEX from the message by the memo it was proposed with. All keys are generated per
 * run and every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessageKexIsNotLostTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticfirstmsgkexlost001"
        const val MESSAGE = "synthetic first hello"
        const val IRONWOOD_CONFIRMING = 200_000L
        const val ABOVE_THE_LIMIT = 5_000L
        const val TRUE_FIGURE = 22_000L
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `a first-message KEX interrupted by leaving the chat still goes once when the chat is opened again`() =
        runBlocking {
            val chat = Chat(KexOrderSeam(failFirstKexWithStillConfirming = true))
            val first = chat.viewModel()

            first.sendMessage(PEER, MESSAGE)
            assertAsked(first, "FIRST_MESSAGE")
            first.confirmPaidAction()
            awaitTrue { chat.heights.isWaiting() }
            assertEquals("fixture sanity: the KEX hit the still-confirming wait", 1, chat.seam.kexProposals.get())
            assertEquals("fixture sanity: no KEX went yet", 0, chat.seam.kexAnswered.get())

            // The user leaves the chat: its screen's ViewModel is cleared.
            first.viewModelScope.coroutineContext.job
                .cancelAndJoin()

            val reopened = chat.viewModel()
            chat.openChat(reopened)
            chat.tick(reopened)
            awaitIdle(reopened)

            assertEquals(
                "the KEX the user confirmed with the first message was lost when the chat screen closed; opening the " +
                    "chat again and a new block must send it",
                1,
                chat.seam.kexAnswered.get(),
            )
            assertNoSecondQuestion(reopened)
        }

    @Test
    fun `a first-message KEX interrupted by the end of the process is sent once by the next process`() =
        runBlocking {
            val chat = Chat(KexOrderSeam(failFirstKexWithStillConfirming = true))
            val first = chat.viewModel()

            first.sendMessage(PEER, MESSAGE)
            assertAsked(first, "FIRST_MESSAGE")
            first.confirmPaidAction()
            awaitTrue { chat.heights.isWaiting() }
            assertEquals("fixture sanity: no KEX went yet", 0, chat.seam.kexAnswered.get())

            // The process ends: every screen and every process-wide send in flight stops.
            first.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            HandshakeHarness.cancelHeldAcceptSends()

            val next = chat.viewModel()
            chat.openChat(next)
            chat.tick(next)
            awaitIdle(next)

            assertEquals("the KEX owed to this contact was never sent", 1, chat.seam.kexAnswered.get())
            assertNoSecondQuestion(next)

            // Later blocks and later opens pay nothing more.
            chat.openChat(next)
            chat.tick(next)
            awaitIdle(next)
            assertEquals("the owed KEX was paid a second time", 1, chat.seam.kexAnswered.get())
        }

    @Test
    fun `the KEX confirmed in the first-message quote is not asked about again as an automatic setup`() =
        runBlocking {
            val chat = Chat(KexOrderSeam(), consent = false)
            val vm = chat.viewModel()

            vm.sendMessage(PEER, MESSAGE)
            assertAsked(vm, "FIRST_MESSAGE")
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertNoSecondQuestion(vm)
            assertEquals("the KEX quoted and confirmed with the first message was not sent", 1, chat.seam.kexAnswered.get())
            assertEquals("fixture sanity: the message went", 1, chat.seam.messageAnswered.get())
        }

    /**
     * ZC-1235 — the first message's KEX is above the limit: nothing is paid, so the message fails on it. The user must read
     * the 0.00022 ZEC it costs (a notice or a question), not a generic failure and not nothing.
     */
    @Test
    fun `a message whose KEX is above the limit tells the user the true figure`() =
        runBlocking {
            val chat = Chat(KexOrderSeam(kexFeeDelta = 5_000L))
            val vm = chat.viewModel()

            vm.sendMessage(PEER, MESSAGE)
            assertAsked(vm, "FIRST_MESSAGE")
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals("fixture: the KEX was not built", 1, chat.seam.kexProposals.get())
            assertEquals("the message was built though its KEX was not paid", 0, chat.seam.messageProposals.get())
            val state = HandshakeHarness.sendMessageState(vm)
            assertTrue(
                "the message failed on its KEX's price and the user reads no figure: $state",
                Regex("""(?<![\d.,])(0[.,]00022|22000)(?!\d)""").containsMatchIn(state.toString()),
            )
        }

    // ── ZC-1235: a message whose KEX is above its limit, end to end ────────────────────────────────────────────────────

    /** The first message's question confirmed: its KEX is built above the limit. */
    private suspend fun firstMessageAboveTheLimit(): Pair<Chat, ChatViewModel> {
        val chat = Chat(KexOrderSeam(kexFeeDelta = ABOVE_THE_LIMIT))
        val vm = chat.viewModel()
        vm.sendMessage(PEER, MESSAGE)
        assertAsked(vm, "FIRST_MESSAGE")
        vm.confirmPaidAction()
        awaitIdle(vm)
        return chat to vm
    }

    /** Our key exists and a KEX is owed from earlier (in the store and in memory); this message needs no question of its own. */
    private suspend fun owedFromEarlierAboveTheLimit(): Pair<Chat, ChatViewModel> {
        val chat = Chat(KexOrderSeam(kexFeeDelta = ABOVE_THE_LIMIT))
        chat.prefs.ourKeys[PEER] =
            co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
                .generateKeyPair()
        chat.prefs.kexOwed += PEER
        val vm = chat.viewModel()
        memoryOwed(vm).add(PEER)
        vm.sendMessage(PEER, MESSAGE, costConfirmed = true)
        awaitIdle(vm)
        return chat to vm
    }

    @Suppress("UNCHECKED_CAST")
    private fun memoryOwed(vm: ChatViewModel) =
        ChatViewModel::class.java
            .getDeclaredField("kexOwedPeers")
            .apply { isAccessible = true }
            .get(vm) as MutableSet<String>

    @Suppress("UNCHECKED_CAST")
    private fun sendStates(vm: ChatViewModel) =
        ChatViewModel::class.java
            .getDeclaredField("_sendMessageState")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<SendMessageState>

    private fun onlyRow(vm: ChatViewModel): String = PaymentHarness.pendingMessages(vm).single().id

    private fun assertAskedTheFigure(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertTrue("no question is on screen: $state", state is SendMessageState.NeedsPaidActionConfirmation)
        val asked = (state as SendMessageState.NeedsPaidActionConfirmation).confirmation
        assertEquals(PaidAction.OWN_SETUP_TRUE_PRICE, asked.action)
        assertEquals(Zatoshi(TRUE_FIGURE), asked.totalCost)
        assertTrue("the true price is not asked as exact", asked.exact)
    }

    private fun assertRowFailedWithItsReason(
        chat: Chat,
        vm: ChatViewModel,
        rows: Int = 1,
    ) {
        val failed = PaymentHarness.pendingMessages(vm).filter { it.status == MessageStatus.FAILED }
        assertEquals("the failed rows", rows, failed.size)
        failed.forEach { row ->
            assertEquals(ChatSendCopy.FAILED_KEX_ABOVE_PRICE, ChatSendCopy.failureReasonFor(row))
            assertEquals("the stored reason", ChatSendCopy.FAILED_KEX_ABOVE_PRICE, chat.prefs.pending[row.id]?.failureReason)
        }
    }

    private fun assertNothingPaid(chat: Chat) {
        assertEquals("something was paid", emptyList<String>(), chat.seam.submitted.toList())
        assertEquals("a message was built though its KEX was not paid", 0, chat.seam.messageProposals.get())
    }

    /** Retry, through its own question when it asks one. */
    private suspend fun retryRow(
        vm: ChatViewModel,
        id: String,
    ) {
        assertTrue("Retry was refused", vm.retryMessage(PEER, id))
        awaitIdle(vm)
        if (HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation) {
            vm.confirmPaidAction()
            awaitIdle(vm)
        }
    }

    private suspend fun payThenRetrySendsTheMessageOnce(
        chat: Chat,
        vm: ChatViewModel,
    ) {
        assertAskedTheFigure(vm)
        assertRowFailedWithItsReason(chat, vm)
        assertNothingPaid(chat)
        val row = onlyRow(vm)

        vm.confirmPaidAction()
        awaitIdle(vm)
        assertEquals("what Pay paid", listOf(TRUE_FIGURE), chat.seam.kexPaid.toList())
        assertFalse("the KEX is still owed after its Pay", PEER in chat.prefs.kexOwed)
        assertEquals("Pay sent the message by itself", 0, chat.seam.messageProposals.get())

        retryRow(vm, row)

        assertEquals("what was paid in all", listOf("KEX", "MESSAGE"), chat.seam.submitted.toList())
        assertEquals("the KEX was paid again", listOf(TRUE_FIGURE), chat.seam.kexPaid.toList())
    }

    private suspend fun cancelPaysNothingAndRetryAsksAgain(
        chat: Chat,
        vm: ChatViewModel,
    ) {
        assertAskedTheFigure(vm)
        val row = onlyRow(vm)

        vm.dismissPaidAction()
        awaitIdle(vm)

        assertNothingPaid(chat)
        assertRowFailedWithItsReason(chat, vm)
        assertTrue("Cancel forgot the owed KEX", PEER in chat.prefs.kexOwed)

        assertTrue("Retry was refused", vm.retryMessage(PEER, row))
        awaitIdle(vm)

        assertAskedTheFigure(vm)
        assertNothingPaid(chat)
        assertRowFailedWithItsReason(chat, vm)
    }

    @Test
    fun `first message - Pay pays exactly the figure, and Retry sends the message once`() =
        runBlocking {
            val (chat, vm) = firstMessageAboveTheLimit()
            payThenRetrySendsTheMessageOnce(chat, vm)
        }

    @Test
    fun `first message - Cancel pays nothing, and Retry asks again`() =
        runBlocking {
            val (chat, vm) = firstMessageAboveTheLimit()
            cancelPaysNothingAndRetryAsksAgain(chat, vm)
        }

    @Test
    fun `a KEX owed from earlier - Pay pays exactly the figure, and Retry sends the message once`() =
        runBlocking {
            val (chat, vm) = owedFromEarlierAboveTheLimit()
            payThenRetrySendsTheMessageOnce(chat, vm)
        }

    @Test
    fun `a KEX owed from earlier - Cancel pays nothing, and Retry asks again`() =
        runBlocking {
            val (chat, vm) = owedFromEarlierAboveTheLimit()
            cancelPaysNothingAndRetryAsksAgain(chat, vm)
        }

    @Test
    fun `messages queued behind the failing one - one question, one KEX, and each message once`() =
        runBlocking {
            val chat = Chat(KexOrderSeam(holdFirstKexProposal = true, kexFeeDelta = ABOVE_THE_LIMIT))
            val vm = chat.viewModel()
            vm.sendMessage(PEER, MESSAGE)
            assertAsked(vm, "FIRST_MESSAGE")
            val questions = mutableListOf<PaidAction>()
            val watcher =
                launch(Dispatchers.Unconfined) {
                    sendStates(vm).collect { state ->
                        if (state is SendMessageState.NeedsPaidActionConfirmation) questions += state.confirmation.action
                    }
                }
            questions.clear()
            vm.confirmPaidAction()
            withTimeout(HandshakeHarness.TIMEOUT_MS) { chat.seam.kexHeld.await() }
            vm.sendMessage(PEER, "synthetic second", costConfirmed = true)
            vm.sendMessage(PEER, "synthetic third", costConfirmed = true)

            chat.seam.releaseKex.complete(Unit)
            awaitIdle(vm)

            assertAskedTheFigure(vm)
            assertRowFailedWithItsReason(chat, vm, rows = 3)
            assertNothingPaid(chat)
            val rows = PaymentHarness.pendingMessages(vm).map { it.id }

            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals("what Pay paid", listOf(TRUE_FIGURE), chat.seam.kexPaid.toList())
            watcher.cancel()
            assertEquals("the questions raised", listOf(PaidAction.OWN_SETUP_TRUE_PRICE), questions.toList())
            assertEquals("Pay sent a message by itself", 0, chat.seam.messageProposals.get())

            rows.forEach { retryRow(vm, it) }

            assertEquals("the KEX was paid again", listOf(TRUE_FIGURE), chat.seam.kexPaid.toList())
            assertEquals("what was paid in all", listOf("KEX", "MESSAGE", "MESSAGE", "MESSAGE"), chat.seam.submitted.toList())
        }

    @Test
    fun `in a confirmed first message the KEX goes first and the message waits for it`() =
        runBlocking {
            val chat = Chat(KexOrderSeam(holdFirstKexProposal = true))
            val vm = chat.viewModel()

            vm.sendMessage(PEER, MESSAGE)
            assertAsked(vm, "FIRST_MESSAGE")
            vm.confirmPaidAction()
            withTimeout(HandshakeHarness.TIMEOUT_MS) { chat.seam.kexHeld.await() }

            assertEquals(
                "the message was built while the KEX quoted before it had not gone yet; the quote says the key " +
                    "exchange goes first, then the message",
                0,
                chat.seam.messageProposals.get(),
            )

            chat.seam.releaseKex.complete(Unit)
            awaitIdle(vm)
            assertEquals(listOf("KEX", "MESSAGE"), chat.seam.submitted.toList())
        }

    /**
     * The message waits for the next spendable note after its KEX in the same job: leaving the chat while it waits
     * (the KEX's change still confirming) does not drop it, and it goes on the next block.
     */
    @Test
    fun `after its KEX the first message waits for the next spendable note even when the chat is left`() =
        runBlocking {
            val chat = Chat(KexOrderSeam(failFirstMessageWithStillConfirming = true))
            val vm = chat.viewModel()

            vm.sendMessage(PEER, MESSAGE)
            assertAsked(vm, "FIRST_MESSAGE")
            vm.confirmPaidAction()
            awaitTrue { chat.seam.messageProposals.get() == 1 }
            assertEquals("fixture sanity: the KEX went", 1, chat.seam.kexAnswered.get())
            assertEquals("fixture sanity: the message is still waiting for a note", 0, chat.seam.messageAnswered.get())

            // The user leaves the chat while the message waits; then a block lands.
            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            // Wait (without failing here) until something waits for the next block, then let one land.
            PaymentHarness.eventually { chat.heights.isWaiting() }
            chat.heights.advance()
            awaitIdle(vm)

            assertEquals(
                "the first message, quoted as the second of two transactions, was dropped when the chat was left",
                1,
                chat.seam.messageAnswered.get(),
            )
            assertEquals(listOf("KEX", "MESSAGE"), chat.seam.submitted.toList())
        }

    /** Control, green before and after: a first message whose KEX was sent sends no second KEX later. */
    @Test
    fun `control - a KEX that was sent is not sent again on a later open or block`() =
        runBlocking {
            val chat = Chat(KexOrderSeam())
            val vm = chat.viewModel()

            vm.sendMessage(PEER, MESSAGE)
            assertAsked(vm, "FIRST_MESSAGE")
            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals("fixture sanity: the KEX went", 1, chat.seam.kexAnswered.get())

            val reopened = chat.viewModel()
            chat.openChat(reopened)
            chat.tick(reopened)
            awaitIdle(reopened)

            assertEquals(1, chat.seam.kexProposals.get())
            assertEquals(1, chat.seam.kexAnswered.get())
            assertNoSecondQuestion(reopened)
        }

    /** Control, green before and after: a KEX whose submit failed after it started (it may have gone) is never re-sent. */
    @Test
    fun `control - a KEX that may have been sent is not sent again on a later open or block`() =
        runBlocking {
            val chat = Chat(KexOrderSeam(failFirstKexSubmitAfterItStarted = true))
            val vm = chat.viewModel()

            vm.sendMessage(PEER, MESSAGE)
            assertAsked(vm, "FIRST_MESSAGE")
            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals("fixture sanity: the KEX submit started", 1, chat.seam.kexSubmits.get())

            val reopened = chat.viewModel()
            chat.openChat(reopened)
            chat.tick(reopened)
            awaitIdle(reopened)

            assertEquals("a KEX that may have been sent was sent again", 1, chat.seam.kexProposals.get())
            assertEquals(1, chat.seam.kexSubmits.get())
        }

    // --------------------------------------------------------------- harness

    private fun assertAsked(
        vm: ChatViewModel,
        action: String,
    ) {
        runBlocking { awaitIdle(vm) }
        val state = HandshakeHarness.sendMessageState(vm)
        assertEquals(
            "fixture sanity: the first message is asked about first, was $state",
            "NeedsPaidActionConfirmation",
            state.javaClass.simpleName,
        )
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        val asked = (confirmation.javaClass.getMethod("getAction").invoke(confirmation) as Enum<*>).name
        assertEquals("fixture sanity", action, asked)
    }

    private fun assertNoSecondQuestion(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertFalse(
            "the user was asked again to pay for a KEX already confirmed in the first-message quote: $state",
            state.javaClass.simpleName == "NeedsPaidActionConfirmation" || state is SendMessageState.NeedsHandshakeSpendConsent,
        )
    }

    /** A new contact in a Shielded chat, no keys yet: one store, one chain tip and one seam for every ViewModel. */
    private class Chat(
        val seam: KexOrderSeam,
        consent: Boolean = true,
    ) {
        val prefs = FirstMessagePrefs(PEER, consent)
        val conversations = RealConversationIds()
        val heights = HeightFeed()
        private val store = conversations.wrap(prefs.proxy)

        suspend fun viewModel(): ChatViewModel {
            val vm = chatViewModelWith(store, HandshakeSeam(PEER), heights)
            // The wallet's Ironwood is still confirming, so a proposal refused for funds is a wait for the next block.
            val base = syntheticZashiAccount()
            val account =
                base.copy(
                    unified =
                        base.unified.copy(
                            ironwood = WalletBalance(Zatoshi(0), Zatoshi(IRONWOOD_CONFIRMING), Zatoshi(0)),
                        ),
                )
            val accounts = Zc101FakeAccountDataSource(account)
            AutoSpendVm.setField(vm, "accountDataSource", accounts)
            AutoSpendVm.setField(
                vm,
                "createChunkedMessageProposal",
                CreateChunkedMessageProposalUseCase(
                    keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                    zashiProposalRepository =
                        ZashiProposalRepositoryImpl(
                            accountDataSource = accounts,
                            proposalDataSource = seam,
                            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                        ).also { SendHarnessScopes.current().own(it) },
                    accountDataSource = accounts,
                    navigationRouter = Zc101RecordingNavigationRouter(),
                    biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                ),
            )
            AutoSpendVm.wireBootstrapFields(vm)
            AutoSpendVm.setField(vm, "messageProcessors", ConcurrentHashMap<String, Any>())
            AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
            AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
            AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
            AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
            AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
            AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
            return vm
        }

        /** What the chat screen runs when it opens (AndroidChatDetail), with the owed-KEX re-arm when the build has it. */
        fun openChat(vm: ChatViewModel) {
            vm.retryKexAckIfResponder(PEER)
            ChatViewModel::class.java.methods
                .firstOrNull { it.name == "retryOwedKeyExchange" && it.parameterCount == 1 }
                ?.invoke(vm, PEER)
        }

        /** A new block: the chain tip moves and the ViewModel runs its per-block work. */
        fun tick(vm: ChatViewModel) {
            heights.advance()
            ChatViewModel::class.java
                .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
                .apply { isAccessible = true }
                .invoke(vm, heights.height.value?.value)
        }
    }

    /**
     * The settlement seam under the real repository. Each proposal is told apart by its first memo (our KEX, or the
     * message), and each submit by the proposal it carries.
     */
    private class KexOrderSeam(
        /** ZC-1235: while set, a KEX build carries a real fee, 15,000 for the one note plus this. */
        @Volatile var kexFeeDelta: Long? = null,
        private val failFirstKexWithStillConfirming: Boolean = false,
        private val holdFirstKexProposal: Boolean = false,
        private val failFirstKexSubmitAfterItStarted: Boolean = false,
        private val failFirstMessageWithStillConfirming: Boolean = false,
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val kexProposals = AtomicInteger(0)
        val kexAnswered = AtomicInteger(0)
        val kexSubmits = AtomicInteger(0)
        val messageProposals = AtomicInteger(0)
        val messageAnswered = AtomicInteger(0)
        val submitted: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** ZC-1235: while [kexFeeDelta] is set, what each submitted KEX costs: its outputs and the fee of the proposal submitted. */
        val kexPaid: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        @Volatile private var kexOutputs = 0L
        val kexHeld = CompletableDeferred<Unit>()
        val releaseKex = CompletableDeferred<Unit>()
        private val kinds = Collections.synchronizedMap(java.util.IdentityHashMap<Proposal, String>())

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            val isKex = ZMSGProtocol.isKEXMessage(firstMemo(zip321Uri))
            if (isKex) {
                val n = kexProposals.incrementAndGet()
                if (n == 1 && failFirstKexWithStillConfirming) throw HandshakeHarness.stillConfirming()
                if (n == 1 && holdFirstKexProposal) {
                    kexHeld.complete(Unit)
                    releaseKex.await()
                }
                kexFeeDelta?.let { delta ->
                    val priced = feeCarryingZip321Proposal(PEER, zip321Uri, listOf(1_000_000L), delta)
                    kinds[priced.proposal] = "KEX"
                    kexOutputs = zip321Amounts(zip321Uri).sum()
                    return priced
                }
                kexAnswered.incrementAndGet()
            } else {
                val n = messageProposals.incrementAndGet()
                if (n == 1 && failFirstMessageWithStillConfirming) throw HandshakeHarness.stillConfirming()
                messageAnswered.incrementAndGet()
            }
            val proposal = syntheticZip321Proposal(PEER, amount = 1_000L, memo = if (isKex) "synthetic-kex" else "synthetic-message")
            kinds[proposal.proposal] = if (isKex) "KEX" else "MESSAGE"
            return proposal
        }

        // F4: the 1:1 chat senders now call this overload (to record the stored ids before the submit). This fake
        // reports no stored ids: it settles through its own two-argument submit below, exactly as before F4.
        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored,
        ): SubmitResult = submitTransaction(proposal, usk)

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult {
            val kind = kinds[proposal] ?: "UNKNOWN"
            submitted += kind
            if (kind == "KEX" && kexFeeDelta != null) kexPaid += kexOutputs + proposal.totalFeeRequired().value
            if (kind == "KEX" && kexSubmits.incrementAndGet() == 1 && failFirstKexSubmitAfterItStarted) {
                throw IllegalStateException("synthetic failure after the KEX submit started")
            }
            return SubmitResult.Success(txIds = listOf("synthetic-txid-${submitted.size}"))
        }
    }

    /**
     * A map-backed store shared by every ViewModel of one test. It answers the calls the first-message send, the KEX
     * and the chat-open and block paths make; conversation ids go to [RealConversationIds]. Other reads get an empty
     * value and other writes report that they were saved, as a working store does.
     */
    private class FirstMessagePrefs(
        private val peer: String,
        @Volatile var consent: Boolean,
    ) {
        val ourKeys = ConcurrentHashMap<String, E2EKeyPair>()
        val pending = ConcurrentHashMap<String, ZchatPreferences.PendingMessageData>()
        private val paidRecords = ConcurrentHashMap<String, String>()
        val kexOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val ownBootSent: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Suppress("CyclomaticComplexMethod", "LongMethod")
        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                fun arg(i: Int) = args!![i]
                when (method.name) {
                    "hasAcknowledgedMessageCost" -> true
                    "hasConsentedToHandshakeSpend" -> consent
                    "setConsentedToHandshakeSpend" -> {
                        consent = true
                        true
                    }
                    "getConversationMode" -> ConversationMode.VAULT
                    "getConversationModeOrNull" -> ConversationMode.VAULT
                    "isE2EKeyChanged" -> false
                    "getE2EOurPublicKey" -> ourKeys[arg(0) as String]?.publicKey
                    "getE2EPrivateKey" -> ourKeys[arg(0) as String]?.privateKey
                    "setE2EOurKeys" -> {
                        ourKeys[arg(0) as String] = E2EKeyPair(arg(1) as String, arg(2) as String)
                        null
                    }
                    // The contact never answers in these tests.
                    "getE2EPeerPublicKey" -> null
                    "isE2EEnabled" -> false
                    "isE2EKeyExchangeComplete" -> false
                    "getReceivedKexPubkey" -> null
                    "getSentKexAckPubkey" -> null
                    "getPeerNostrPubkey" -> null
                    "getSentNostrBootPubkey" -> null
                    "getInboundKexAwaitingAccept" -> null
                    "getTunnelModePeers" -> emptyList<String>()
                    "walletScopeGeneration" -> 0L
                    "getHandshakePaidRecord" -> paidRecords[arg(0) as String]
                    "setHandshakePaidRecord" -> {
                        val value = arg(1) as String?
                        if (value == null) paidRecords.remove(arg(0) as String) else paidRecords[arg(0) as String] = value
                        true
                    }
                    "isOwnBootSent" -> arg(0) in ownBootSent
                    "setOwnBootSent" -> {
                        if (arg(1) as Boolean) ownBootSent += arg(0) as String else ownBootSent -= arg(0) as String
                        true
                    }
                    // R9-2: the durable "KEX owed" mark, when the build has one.
                    "isKexOwed" -> arg(0) in kexOwed
                    "getKexOwedPeers" -> kexOwed.toSet()
                    "setKexOwed" -> {
                        if (arg(1) as Boolean) kexOwed += arg(0) as String else kexOwed -= arg(0) as String
                        true
                    }
                    "getPendingMessages" -> pending.values.sortedBy { it.timestampMillis }
                    "addPendingMessage", "commitPendingMessage" -> {
                        val row = arg(0) as ZchatPreferences.PendingMessageData
                        pending[row.id] = row
                        null
                    }
                    "removePendingMessage" -> {
                        pending.remove(arg(0) as String)
                        null
                    }
                    "getNostrRotationIndex" -> 0
                    "resolvePeerAddress" -> arg(0)
                    "getDisplayName" -> "Synthetic Contact"
                    "toString" -> "FirstMessagePrefs"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> {
                        val isWrite =
                            method.name.startsWith("set") || method.name.startsWith("put") || method.name.startsWith("mark")
                        if (isWrite && method.returnType == java.lang.Boolean.TYPE) true else emptyValueFor(method.returnType)
                    }
                }
            } as ZchatPreferences

        private fun emptyValueFor(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                List::class.java -> emptyList<Any>()
                Set::class.java -> emptySet<Any>()
                Map::class.java -> emptyMap<Any, Any>()
                else -> null
            }
    }
}
