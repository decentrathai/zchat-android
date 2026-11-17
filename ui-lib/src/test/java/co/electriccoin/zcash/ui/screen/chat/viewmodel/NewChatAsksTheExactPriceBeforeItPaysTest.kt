package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeGuard
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeTestApi
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.usecase.zip317ChatPrice
import co.electriccoin.zcash.ui.screen.chat.usecase.zip321Amounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ZC-601 (register, high; the unplanned payment of 5 Oct on the HONOR debug) — New Chat's "Send message" is the payment.
 *
 * At the base `ZchatComposeVM.doSendMessage` writes the chat's mode, creates the conversation id and calls the send
 * chokepoint with `directSubmit = true` and no quote, in every mode except a free Open send: one tap builds, signs and
 * submits a transaction. The form shows a "~" fee from a formula that ignores the wallet's notes, and nothing asks. A
 * wallet whose balance sits in several notes pays more than that line says.
 *
 * Wanted: after the tap the screen takes the SDK's price of exactly this transaction and asks one exact question
 * ("You pay exactly this"); before Pay nothing is built, stored, written or submitted; Pay sends that transaction once
 * and the chokepoint refuses any other; Cancel leaves the form as it was. No exact price or a shortfall: no question
 * and nothing sent.
 *
 * Harness: a real ZchatComposeVM over the real use case and repository (the FirstMessageQuoteMatchesSendComposeTest
 * idiom). Probes are priced like SDK 3.0.0 ([zip317ChatPrice]); a real proposal carries the fee its probe was priced
 * at ([feeCarryingZip321Proposal]); the preferences are a proxy that logs every write. The question is reached by
 * reflection ([NewChatPriceQuestion]) so this file compiles at the base. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewChatAsksTheExactPriceBeforeItPaysTest {
    private val vms = mutableListOf<ZchatComposeVM>()
    private val accounts = ChangingAccountDataSource(walletWith(orchard = 0, ironwood = ONE_NOTE))
    private val proposals = PricedProposals(listOf(ONE_NOTE))
    private val navigationRouter = Zc101RecordingNavigationRouter()
    private val store = LoggedStore()

    @Before
    fun setUp() {
        NetworkUpgradeTestApi.resetIfPresent()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        vms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
        NetworkUpgradeTestApi.resetIfPresent()
    }

    // ------------------------------------------------------------------ (a) (b)

    @Test
    fun `a Shielded first message is asked before anything is built, written or paid`() =
        runBlocking {
            val vm = typed()

            tap(vm)

            assertEquals(0, proposals.submitted.size, "the tap on Send paid: a transaction was submitted with no question")
            assertEquals(0, proposals.proposed.size, "a proposal was built before the user was asked")
            assertEquals(emptyList(), store.writes.toList(), "the tap wrote to the chat store before the user was asked")
            NewChatPriceQuestion.require(vm)
            val form = vm.ready()
            assertFalse(form.isSending, "the form says Sending while the question is open")
            assertEquals(MESSAGE, form.message, "the form lost its text")
        }

    @Test
    fun `the question is the exact price of this one transaction, with the wallet's own fee`() =
        runBlocking {
            useWallet(SEVERAL_NOTES)
            val vm = typed()

            tap(vm)

            val fee = feeOf(SEVERAL_NOTES, listOf(DUST, PLATFORM_FEE))
            assertEquals(25_000L, fee, "fixture sanity: five notes cost more than the form's ~0.00015 estimate")
            val asked = NewChatPriceQuestion.require(vm)
            assertTrue(asked.exact, "the question is a floor (\"at least\"), not the exact price")
            assertEquals(1, asked.onChainMessages, "New Chat sends one transaction")
            assertEquals(DUST + PLATFORM_FEE + fee, asked.totalCost.value, "the total is not outputs + the SDK's fee")
            assertEquals(Zatoshi(fee), asked.networkFee, "the network fee is not the SDK's price")
            assertEquals(1, asked.memos)
            assertEquals("NEW_CHAT_FIRST_MESSAGE", asked.action.name)
            assertEquals(0, proposals.submitted.size)
        }

    @Test
    fun `a text of two memos is asked the price of that shape`() =
        runBlocking {
            val memos = ZMSGProtocol.calculateV4ChunkCount(TWO_MEMO_MESSAGE, true, SELF_ADDRESS)
            assertEquals(2, memos, "fixture sanity: the text needs two memos as an INIT")
            val vm = typed(message = TWO_MEMO_MESSAGE)

            tap(vm)

            val outputs = listOf(DUST, DUST, PLATFORM_FEE)
            val fee = feeOf(listOf(ONE_NOTE), outputs)
            val asked = NewChatPriceQuestion.require(vm)
            assertEquals(
                CreateChunkedMessageProposalUseCase.totalCost(memos, Zatoshi(DUST)).value + fee,
                asked.totalCost.value,
            )
            assertEquals(outputs.sum() + fee, asked.totalCost.value)
            assertEquals(Zatoshi(fee), asked.networkFee)
            assertEquals(2, asked.memos)
            assertTrue(asked.exact)
        }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `Cancel builds, writes and pays nothing, keeps the form and a new tap asks again`() =
        runBlocking {
            val vm = typed()
            tap(vm)
            NewChatPriceQuestion.require(vm)

            NewChatPriceQuestion.cancel(vm)
            settle(vm)

            assertNull(NewChatPriceQuestion.open(vm), "the question stayed open after Cancel")
            assertEquals(0, proposals.proposed.size, "a cancelled send built a proposal")
            assertEquals(0, proposals.submitted.size, "a cancelled send was submitted")
            assertEquals(
                emptyList(),
                store.writes.toList(),
                "a cancelled send left something in the chat store (a conversation id, a mode, a row, a mark or a draft)",
            )
            assertEquals(emptyList(), navigationRouter.commands.toList(), "a cancelled send opened a chat")
            val form = vm.ready()
            assertFalse(form.isSending, "the form says Sending after Cancel")
            assertFalse(NewChatPriceQuestion.askingPrice(form), "the form is still working out a price after Cancel")
            assertEquals(MESSAGE, form.message)
            assertEquals(PEER_ADDRESS, form.recipientAddress)
            assertTrue(form.isValidAddress)

            tap(vm)
            NewChatPriceQuestion.require(vm)
            assertEquals(0, proposals.submitted.size)
        }

    // ------------------------------------------------------------------ (d)

    @Test
    fun `Pay sends exactly the quoted transaction, once`() =
        runBlocking {
            useWallet(SEVERAL_NOTES)
            val vm = typed()
            tap(vm)
            val asked = NewChatPriceQuestion.require(vm)

            NewChatPriceQuestion.pay(vm)
            settle(vm)

            assertEquals(1, proposals.proposed.size, "Pay must build exactly one proposal")
            assertEquals(1, proposals.submitted.size, "Pay must submit exactly once")
            val paid = zip321Amounts(proposals.proposed.single()).sum() + feePaid()
            assertEquals(asked.totalCost.value, paid, "the wallet paid another figure than the question quoted")
            assertTrue(isInitFrom(firstMemo(proposals.proposed.single()), SELF_ADDRESS), "the first message is not the INIT")
            assertEquals(1, store.writes.count { it == "markConversationInitSubmitted" }, "the INIT is marked submitted once")
            assertEquals(listOf("replace"), navigationRouter.commands.toList(), "the chat did not open")
            assertNull(NewChatPriceQuestion.open(vm))
        }

    // ------------------------------------------------------------------ (e)

    @Test
    fun `a price that changed after the question pays nothing, keeps the form and asks again`() =
        runBlocking {
            val vm = typed()
            tap(vm)
            NewChatPriceQuestion.require(vm)
            val probesAtTheQuestion = proposals.probed.size

            proposals.realFeeDelta = 5_000L
            NewChatPriceQuestion.pay(vm)
            settle(vm)

            assertEquals(0, proposals.submitted.size, "a transaction that is not the quoted one was submitted")
            assertEquals(1, proposals.proposed.size, "fixture sanity: Pay built the one proposal the door then refused")
            val state = vm.state.value
            assertTrue(state is ZchatComposeState.Ready, "the price change must leave the form, not $state")
            assertFalse(state.isSending)
            assertEquals(MESSAGE, state.message)
            assertEquals(probesAtTheQuestion + 1, proposals.probed.size, "the price was not taken again, once")
            NewChatPriceQuestion.require(vm)
            assertEquals(emptyList(), navigationRouter.commands.toList(), "the chat opened though nothing was sent")

            proposals.realFeeDelta = 0L
            NewChatPriceQuestion.pay(vm)
            settle(vm)
            assertEquals(1, proposals.submitted.size, "Pay on the new question sends once")
        }

    @Test
    fun `Pay after the form changed sends nothing`() =
        runBlocking {
            val vm = typed()
            tap(vm)
            NewChatPriceQuestion.require(vm)

            vm.ready().onMessageChange("$MESSAGE and more words that were never priced")
            NewChatPriceQuestion.pay(vm)
            settle(vm)

            assertEquals(0, proposals.proposed.size, "a text that was not priced was proposed")
            assertEquals(0, proposals.submitted.size, "a text that was not priced was paid for")
            assertEquals(emptyList(), store.writes.toList())
            assertTrue(vm.state.value is ZchatComposeState.Ready)
        }

    // ------------------------------------------------------------------ (f)

    @Test
    fun `Tunnel is asked the same way, with its own sentence`() =
        runBlocking {
            store.storedMode = null
            val vm = typed()
            assertEquals(ConversationMode.TUNNEL, vm.ready().selectedMode, "fixture sanity: New Chat's default is Tunnel")
            assertFalse(vm.ready().isFreeOpenSend)

            tap(vm)

            assertEquals(0, proposals.submitted.size, "a Tunnel first message was paid on the tap")
            assertEquals(0, proposals.proposed.size)
            assertEquals(emptyList(), store.writes.toList())
            val asked = NewChatPriceQuestion.require(vm)
            assertEquals("NEW_CHAT_FIRST_MESSAGE_TUNNEL", asked.action.name)
            assertTrue(asked.exact)
            val message = DUST + PLATFORM_FEE + feeOf(listOf(ONE_NOTE), listOf(DUST, PLATFORM_FEE))
            val setupLeg = onChainSendMinimumCost()
            // ZC-1272: one question for the chat: the message, our key exchange and our relay identity, each with its figure.
            assertEquals(3, asked.onChainMessages, "the question does not count every leg of a Tunnel chat")
            assertEquals(message + 2 * setupLeg.value, asked.totalCost.value, "the total is not the sum of the legs")
            assertTrue(asked.includesChatSetup, "the question does not name the chat's setup")
            assertTrue(asked.includesRelayIdentity, "the question does not name the relay identity")
            assertEquals(2, asked.setupLegs, "the question does not state its two setup legs")
            assertEquals(setupLeg, asked.setupLegCost, "the question does not state what one setup leg costs")
            assertEquals(Zatoshi(message), asked.messageLegCost, "the question does not state what the message costs")
            assertEquals(R.string.paid_action_chat_setup, PaidActionCopy.actionText(asked), "the dialog does not use the setup sentence")

            NewChatPriceQuestion.pay(vm)
            settle(vm)
            assertEquals(1, proposals.submitted.size)
        }

    // ------------------------------------------------------------------ (g)

    @Test
    fun `Send All asks its priced total without another probe, and Pay sends it`() =
        runBlocking {
            val vm = typed()
            vm.ready().onAmountSelect(MessageAmount.SEND_ALL)
            awaitSendAllQuote(vm)
            val probesBefore = proposals.probed.size

            tap(vm)

            assertEquals(0, proposals.submitted.size, "a Send All was paid on the tap")
            assertEquals(0, proposals.proposed.size)
            val asked = NewChatPriceQuestion.require(vm)
            assertEquals(probesBefore, proposals.probed.size, "a priced Send All was probed again")
            assertTrue(asked.exact)
            assertEquals(ONE_NOTE, asked.totalCost.value, "a Send All of one note costs the whole note: amount, ZChat fee, fee")
            assertEquals(Zatoshi(15_000L), asked.networkFee)

            NewChatPriceQuestion.pay(vm)
            settle(vm)
            assertEquals(1, proposals.submitted.size)
            val paid = zip321Amounts(proposals.proposed.single()).sum() + feePaid()
            assertEquals(asked.totalCost.value, paid)
        }

    @Test
    fun `a Send All whose proposal no longer matches sends nothing`() =
        runBlocking {
            val vm = typed()
            vm.ready().onAmountSelect(MessageAmount.SEND_ALL)
            awaitSendAllQuote(vm)
            tap(vm)
            NewChatPriceQuestion.require(vm)

            proposals.realFeeDelta = 5_000L
            NewChatPriceQuestion.pay(vm)
            withTimeout(TIMEOUT_MS) {
                while ((vm.state.value as? ZchatComposeState.Ready)?.sendAllNote?.contains("Amount updated") != true) {
                    delay(POLL_MS)
                }
            }

            assertEquals(0, proposals.submitted.size, "a Send All that is not the quoted one was submitted")
            assertEquals(emptyList(), navigationRouter.commands.toList())
        }

    // ------------------------------------------------------------------ (h)

    @Test
    fun `a second tap while the question is open does nothing`() =
        runBlocking {
            val vm = typed()
            tap(vm)
            val first = NewChatPriceQuestion.require(vm)
            val probes = proposals.probed.size

            vm.ready().onSendClick()
            settle(vm)

            assertEquals(probes, proposals.probed.size, "the second tap priced the send again")
            assertEquals(first, NewChatPriceQuestion.require(vm), "the second tap replaced the question")
            assertEquals(0, proposals.proposed.size)
            assertEquals(0, proposals.submitted.size)
        }

    @Test
    fun `with the one-time cost notice unread the tap goes straight to the question, not to a payment`() =
        runBlocking {
            store.costAcknowledged = false
            val vm = typed()

            tap(vm)

            assertFalse(vm.showCostDisclaimer.value, "the one-time notice stands in front of the price question")
            NewChatPriceQuestion.require(vm)
            assertEquals(0, proposals.submitted.size, "the tap paid: the question was not answered")
            assertEquals(0, proposals.proposed.size)
            assertEquals(emptyList(), store.writes.toList(), "the tap wrote to the chat store before the user was asked")

            NewChatPriceQuestion.pay(vm)
            settle(vm)

            assertTrue("setAcknowledgedMessageCost" in store.writes, "Pay on the price question did not mark the notice as read")
            assertFalse(vm.showCostDisclaimer.value, "the one-time notice followed the question the user confirmed")
            assertEquals(1, proposals.submitted.size, "Pay must submit exactly once")
        }

    // ------------------------------------------------------------------ (i)

    @Test
    fun `a price the SDK cannot give asks nothing and sends nothing`() =
        runBlocking {
            proposals.probeThrows = IllegalStateException("synthetic SDK failure")
            assertRefusedWithoutAPrice(R.string.payment_quote_unavailable)
        }

    @Test
    fun `a price that needs Orchard inputs asks nothing and sends nothing`() =
        runBlocking {
            proposals.probeAnswer = FeeProbe.Built(Zatoshi(15_000L), usesOrchardInputs = true, transactionCount = 1)
            assertRefusedWithoutAPrice(R.string.payment_quote_unavailable)
        }

    @Test
    fun `a price of two transactions asks nothing and sends nothing`() =
        runBlocking {
            proposals.probeAnswer = FeeProbe.Built(Zatoshi(15_000L), usesOrchardInputs = false, transactionCount = 2)
            assertRefusedWithoutAPrice(R.string.payment_quote_unavailable)
        }

    @Test
    fun `a wallet that cannot pay is told so, asked nothing, and sends nothing`() =
        runBlocking {
            proposals.probeAnswer = FeeProbe.Short
            val vm = typed()

            tap(vm)

            assertNull(NewChatPriceQuestion.open(vm), "a wallet that cannot pay was asked to pay")
            assertEquals(0, proposals.proposed.size, "a proposal was built for a send the wallet cannot pay")
            assertEquals(0, proposals.submitted.size)
            assertEquals(emptyList(), store.writes.toList())
            val state = vm.state.value
            assertTrue(state is ZchatComposeState.Error, "the shortfall must show the refused-send screen, not $state")
            assertTrue(
                state.message in
                    setOf(
                        CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE,
                        CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE,
                    ),
                "not the shortfall text: ${state.message}",
            )
        }

    /** Control, green before and after (ZC-538): the update refusal shows its sentence; nothing asked, built or paid. */
    @Test
    fun `control - in the update-required state the form shows the update sentence and asks nothing`() =
        runBlocking {
            NetworkUpgradeGuard.onSubmitResult(SubmitResult.Failure(emptyList(), 0, "incorrect consensus branch id"))
            assertTrue(NetworkUpgradeGuard.refusal() != null, "fixture sanity: the app is in its update-required state")
            val vm = typed()

            tap(vm)

            assertNull(NewChatPriceQuestion.open(vm), "the user was asked to pay in the update-required state")
            assertEquals(0, proposals.proposed.size)
            assertEquals(0, proposals.submitted.size)
            val state = vm.state.value
            assertTrue(state is ZchatComposeState.Error, "expected the refused-send screen, not $state")
            assertEquals(NetworkUpgradeTestApi.MESSAGE, state.message)
        }

    // ------------------------------------------------------------------ (j)

    /** Control, green before and after: a free Open send costs nothing, so it asks nothing and builds nothing. */
    @Test
    fun `control - a free Open send asks nothing and builds no proposal`() =
        runBlocking {
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(1, "synthetic-zc601-rumor") }
            store.storedMode = ConversationMode.OPEN
            store.peerRelayKey = "ab".repeat(32)
            val vm = typed()
            assertTrue(vm.ready().isFreeOpenSend, "fixture sanity: the send is the free Open send")

            vm.ready().onSendClick()
            awaitNavigation(vm)

            assertNull(NewChatPriceQuestion.open(vm), "a free send was asked a price")
            assertEquals(0, proposals.probed.size, "a free send was priced")
            assertEquals(0, proposals.proposed.size, "a free send built a proposal")
            assertEquals(0, proposals.submitted.size)
            assertEquals(listOf("replace"), navigationRouter.commands.toList())
        }

    /** Control, green before and after: a recipient that has a chat gets the text as that chat's draft. */
    @Test
    fun `control - an existing chat gets the draft and is asked nothing`() =
        runBlocking {
            store.existingChat = true
            val vm = typed()

            vm.ready().onSendClick()
            awaitNavigation(vm)

            assertNull(NewChatPriceQuestion.open(vm))
            assertEquals(0, proposals.probed.size)
            assertEquals(0, proposals.proposed.size)
            assertEquals(0, proposals.submitted.size)
            assertEquals(listOf("setDraft"), store.writes.toList())
        }

    // --------------------------------------------------------------- harness

    private suspend fun assertRefusedWithoutAPrice(expectedText: Int) {
        val vm = typed()

        tap(vm)

        assertNull(NewChatPriceQuestion.open(vm), "a send with no exact price was asked about")
        assertEquals(0, proposals.proposed.size, "a proposal was built for a send that has no exact price")
        assertEquals(0, proposals.submitted.size, "a send with no exact price was paid")
        assertEquals(emptyList(), store.writes.toList(), "a send with no exact price wrote to the chat store")
        val state = vm.state.value
        assertTrue(state is ZchatComposeState.Error, "no exact price must show the refused-send screen, not $state")
        assertEquals(
            expectedText,
            ChatSendCopy.localizedResource(state.message),
            "the refusal is not the 'couldn't work out the exact price' text: ${state.message}",
        )
    }

    /** The network fee of the one transaction that was submitted. */
    private fun feePaid(): Long {
        val submitted = proposals.submitted.single()
        return submitted.totalFeeRequired().value
    }

    private fun useWallet(notes: List<Long>) {
        accounts.account.value = walletWith(orchard = 0, ironwood = notes.sum())
        proposals.ironwoodNotes = notes
    }

    private fun feeOf(
        notes: List<Long>,
        outputs: List<Long>,
    ): Long = (zip317ChatPrice(notes, outputs) as FeeProbe.Built).fee.value

    /** New Chat with the peer and [message] entered, the default amount, the stored mode (Shielded unless changed). */
    private suspend fun typed(message: String = MESSAGE): ZchatComposeVM {
        val vm = buildVm()
        vm.ready().onRecipientChange(PEER_ADDRESS)
        vm.ready().onMessageChange(message)
        store.writes.clear()
        return vm
    }

    /** Taps Send and waits until the tap's work ends: a question, a refusal, a submit, or a form at rest. */
    private suspend fun tap(vm: ZchatComposeVM) {
        vm.ready().onSendClick()
        settle(vm)
    }

    private suspend fun settle(vm: ZchatComposeVM) {
        withTimeout(TIMEOUT_MS) {
            while (true) {
                val state = vm.state.value
                val busy = state is ZchatComposeState.Loading || NewChatPriceQuestion.askingPrice(state)
                val sending = state is ZchatComposeState.Ready && state.isSending
                if (NewChatPriceQuestion.open(vm) != null || navigationRouter.commands.isNotEmpty()) break
                if (!busy && !sending) break
                delay(POLL_MS)
            }
        }
    }

    /** Waits until the screen navigated; a refusal on the way fails with what the screen shows. */
    private suspend fun awaitNavigation(vm: ZchatComposeVM) {
        withTimeout(TIMEOUT_MS) {
            while (navigationRouter.commands.isEmpty()) {
                val state = vm.state.value
                if (state is ZchatComposeState.Error) throw AssertionError("the send was refused: ${state.message}")
                delay(POLL_MS)
            }
        }
    }

    private suspend fun awaitSendAllQuote(vm: ZchatComposeVM) {
        withTimeout(TIMEOUT_MS) {
            while ((vm.state.value as? ZchatComposeState.Ready)?.sendAllAmountDisplay.isNullOrEmpty()) delay(POLL_MS)
        }
    }

    private suspend fun buildVm(): ZchatComposeVM {
        val synchronizerProvider = AnsweringSynchronizerProvider()
        val getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accounts)
        val vm =
            ZchatComposeVM(
                contactBook = EmptyContactBook(),
                getSelectedWalletAccount = getSelectedWalletAccount,
                getDefaultUnifiedAddress = GetDefaultUnifiedAddressUseCase(synchronizerProvider, getSelectedWalletAccount),
                createChunkedMessageProposal =
                    CreateChunkedMessageProposalUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository =
                            ZashiProposalRepositoryImpl(
                                accountDataSource = accounts,
                                proposalDataSource = proposals,
                                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                            ),
                        accountDataSource = accounts,
                        navigationRouter = Zc101RecordingNavigationRouter(),
                        biometricRepository = Zc101SuspendingBiometricRepository().apply { approve() },
                    ),
                navigationRouter = navigationRouter,
                prefillZchat = PrefillZchatUseCase(),
                transactionRepository = EmptyTransactionRepository(),
                zchatPreferences = store.proxy,
                validateAddress = ValidateAddressUseCase(synchronizerProvider),
            )
        vms += vm
        return vm
    }

    private fun ZchatComposeVM.ready(): ZchatComposeState.Ready =
        state.value as? ZchatComposeState.Ready
            ?: error("VM state was ${state.value}, expected Ready")

    /**
     * The chat store as a proxy that answers what New Chat reads and logs the name of every call that is not a read:
     * a conversation id, a mode, a row, a mark, a draft, the accepted cost notice.
     */
    private class LoggedStore {
        val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile var storedMode: ConversationMode? = ConversationMode.VAULT

        @Volatile var costAcknowledged = true

        @Volatile var existingChat = false

        @Volatile var peerRelayKey: String? = null

        @Volatile private var initSubmitted = false

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "toString" -> "LoggedStore"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "getConversationModeOrNull" -> storedMode
                    "getConversationMode" -> storedMode ?: ConversationMode.VAULT
                    "hasAcknowledgedMessageCost" -> costAcknowledged
                    "isE2EEnabled" -> existingChat
                    "needsConversationInit" -> !initSubmitted
                    "getPeerNostrPubkey" -> peerRelayKey
                    else -> {
                        if (isWrite(method.name)) writes += method.name
                        when (method.name) {
                            "setAcknowledgedMessageCost" -> costAcknowledged = true
                            "setConversationMode" -> storedMode = args!![1] as ConversationMode
                            "markConversationInitSubmitted" -> initSubmitted = true
                        }
                        when (method.name) {
                            "getOrCreateConversationIdForMessage", "getOrCreateConversationId" -> "SYNTH601" to !initSubmitted
                            else -> nothingStored(method.returnType)
                        }
                    }
                }
            } as ZchatPreferences

        private fun isWrite(name: String): Boolean = name.startsWith("getOrCreate") || READS.none { name.startsWith(it) }

        private fun nothingStored(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                List::class.java -> emptyList<Any>()
                Set::class.java -> emptySet<Any>()
                Map::class.java -> emptyMap<Any, Any>()
                else -> null
            }

        private companion object {
            val READS = listOf("get", "is", "has", "needs", "can", "should", "peek", "count", "find")
        }
    }

    /**
     * Probes priced like SDK 3.0.0 over [ironwoodNotes] (or answered with [probeAnswer], or failing with
     * [probeThrows]); a real proposal carries the fee its probe was priced at plus [realFeeDelta] (the wallet moved
     * between the question and Pay); every probe, proposal and submit is recorded and every submit succeeds.
     */
    private class PricedProposals(
        @Volatile var ironwoodNotes: List<Long>,
        val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val probed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val proposed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val submitted: MutableList<Proposal> = Collections.synchronizedList(mutableListOf())

        @Volatile var probeAnswer: FeeProbe? = null

        @Volatile var probeThrows: Exception? = null

        @Volatile var realFeeDelta = 0L

        override suspend fun probeZip321Fee(
            account: WalletAccount,
            zip321Uri: String,
        ): FeeProbe {
            probed += zip321Uri
            probeThrows?.let { throw it }
            return probeAnswer ?: zip317ChatPrice(ironwoodNotes, zip321Amounts(zip321Uri))
        }

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            proposed += zip321Uri
            return feeCarryingZip321Proposal("usynthetic-zc601-peer", zip321Uri, ironwoodNotes, realFeeDelta)
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored,
        ): SubmitResult = submitTransaction(proposal, usk)

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult {
            submitted += proposal
            return SubmitResult.Success(txIds = listOf("synthetic-txid-${submitted.size}"))
        }
    }

    /** Answers the two Synchronizer calls this screen makes: our own address, and "valid" for any other. */
    private class AnsweringSynchronizerProvider : SynchronizerProvider {
        private val answering =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "AnsweringSynchronizer"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    "getUnifiedAddress" -> SELF_ADDRESS
                    "validateAddress" -> AddressType.Unified
                    else -> throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
                }
            } as Synchronizer

        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(answering)

        override suspend fun getSynchronizer(): Synchronizer = answering

        override fun resetSynchronizer() = Unit
    }

    private class EmptyTransactionRepository : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(null)

        override suspend fun getMemos(transaction: Transaction): List<String> = emptyList()

        override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)

        override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)

        override suspend fun getTransactions(): List<Transaction> = emptyList()

        override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()
    }

    private class EmptyContactBook : ContactBook {
        override fun addContact(contact: Contact) = Unit

        override fun removeContact(address: String) = Unit

        override fun getContact(address: String): Contact? = null

        override fun getAllContacts(): List<Contact> = emptyList()

        override fun hasContact(address: String): Boolean = false

        override fun updateContactName(
            address: String,
            newName: String
        ) = Unit

        override fun clearAll() = Unit
    }

    private companion object {
        const val ONE_NOTE = 443_002L
        const val DUST = 1_000L
        const val PLATFORM_FEE = 1_000L
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
        const val MESSAGE = "synthetic first hello"

        /** Four small notes before the large one: the SDK spends five inputs, so the fee is 25,000, not 15,000. */
        val SEVERAL_NOTES = listOf(6_000L, 6_000L, 6_000L, 6_000L, 400_000L)

        /** Bare-address shaped and longer than 100 chars, as the screen requires. Synthetic. */
        val PEER_ADDRESS = "u1synthetic" + "peer".repeat(25)
        val SELF_ADDRESS = "u1synthetic" + "self".repeat(25)

        /** Needs two memos as an INIT (the INIT spends about 110 bytes on our address). */
        val TWO_MEMO_MESSAGE = "synthetic long first message ".repeat(14).take(400)
    }
}
