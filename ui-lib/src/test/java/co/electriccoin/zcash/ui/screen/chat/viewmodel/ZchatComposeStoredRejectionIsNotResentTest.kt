package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
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
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.model.SubmitAttemptProof
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.usecase.zip317ChatPrice
import co.electriccoin.zcash.ui.screen.chat.usecase.zip321Amounts
import co.electriccoin.zcash.ui.screen.chat.util.NETWORK_HICCUP_MESSAGE
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.text.DecimalFormatSymbols
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * D2 (REVIEW_7C F11) — New Message never re-prices or re-offers a send that may have gone out.
 *
 * At 1b98dd127 ZchatComposeVM.doSendMessage passes onSubmitStarting only for a first message, so the screen
 * never knows the submit started. A stored-then-rejected submit (SubmitResult.Failure with a txid: the SDK stored
 * the transaction and may still broadcast it) reaches the screen unchanged since C4. Its catch then either:
 * - re-prices a Send All ("Amount updated – tap Send again") when the text reads like a shortfall, or
 * - shows the full-screen error through the mapper, whose Retry re-opens the form with the same recipient,
 *   message and amount (a SubmitRejectedException's text always contains "submit", so a generic one reads as the
 *   "network hiccup, try again" notice).
 * Either way one more tap pays a second time while the first transaction may still be broadcast.
 *
 * Wanted: once the submit started and the failure does not prove nothing was stored, the screen clears the message
 * and the amount, and shows the "may already have been sent" notice (the payment wording for a value send) with no
 * action that sends again; a later balance change does not bring the form back. A shortfall that provably stored
 * nothing is unchanged: Send All re-prices, a custom amount shows the shortfall notice.
 *
 * The notice is read without naming its state type (its text through a `message` property, its actions as every
 * `() -> Unit` it holds), so this file compiles at the base. The cleared form is read from the view model's own
 * fields. Harness: the FailedFirstMessageRetriedAsInitComposeTest idiom (a real ZchatComposeVM on an unconfined main
 * dispatcher, the real use case and repository, the real conversation-id store) with scripted submit results. A
 * Send All proposal is hand-encoded with the fee its quote was priced at, so the use case's send-time check passes
 * and the submit is reached. All addresses and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZchatComposeStoredRejectionIsNotResentTest {
    private val vms = mutableListOf<ZchatComposeVM>()
    private val conversations = RealConversationIds()
    private val accounts = ChangingAccountDataSource(walletWith(orchard = 0, ironwood = IRONWOOD))
    private val proposals = ScriptedProposals(ironwoodNotes = listOf(IRONWOOD))
    private val navigationRouter = Zc101RecordingNavigationRouter()
    private val initMarks = AtomicInteger()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
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
    }

    /** (a) A value send stored then rejected with a shortfall text: the payment notice, nothing to re-send. */
    @Test
    fun `a custom amount stored then rejected shows the payment notice and never sends again`() =
        runBlocking {
            proposals.nextSubmit = storedRejection(code = -26, description = "Insufficient balance (have 1, need 2 including fee)")
            val vm = buildVm()
            val shown = sendFirst(vm) { customAmount(it, "0", "001") }
            assertEquals(1, proposals.submitted.size, "fixture sanity: the send reached the submit")

            val settled = vm.state.value
            assertFalse(settled is ZchatComposeState.Error, "a stored transaction got the error screen with Retry: $settled")
            assertEquals(ChatSendCopy.PAYMENT_MAY_HAVE_BEEN_SENT, noticeText(settled), "the notice for a value send")
            assertFormCleared(vm)

            pokeEverything(vm, shown)
            assertEquals(1, proposals.submitted.size, "a second payment was submitted while the first may still go out")
        }

    /** (b) A Send All stored then rejected with a shortfall text: no "Amount updated", no second send. */
    @Test
    fun `a Send All stored then rejected is not re-priced and never sends again`() =
        runBlocking {
            proposals.nextSubmit = storedRejection(code = -26, description = "Insufficient balance (have 1, need 2 including fee)")
            val vm = buildVm()
            val shown = sendFirst(vm) { it.ready().onAmountSelect(MessageAmount.SEND_ALL) }
            assertEquals(1, proposals.submitted.size, "fixture sanity: the Send All reached the submit")

            awaitSendAllQuote(vm)
            val settled = vm.state.value
            assertFalse(
                settled is ZchatComposeState.Ready && settled.sendAllNote.contains("Amount updated"),
                "a Send All the SDK may still broadcast was re-priced for a new tap",
            )
            assertFalse(settled is ZchatComposeState.Error, "a stored transaction got the error screen with Retry: $settled")
            assertEquals(ChatSendCopy.PAYMENT_MAY_HAVE_BEEN_SENT, noticeText(settled), "the notice for a value send")
            assertFormCleared(vm)

            pokeEverything(vm, shown)
            assertEquals(1, proposals.submitted.size, "a second Send All was submitted while the first may still go out")
        }

    /** (c) A dust message stored then rejected for another reason: not the "network hiccup, try again" notice. */
    @Test
    fun `a message stored then rejected shows the may-have-been-sent notice, not a network hiccup`() =
        runBlocking {
            proposals.nextSubmit = storedRejection(code = 18, description = "bad-txns")
            val vm = buildVm()
            val shown = sendFirst(vm) { }
            assertEquals(1, proposals.submitted.size, "fixture sanity: the send reached the submit")

            val settled = vm.state.value
            assertNotEquals(NETWORK_HICCUP_MESSAGE, noticeText(settled), "a stored transaction was called a network hiccup")
            assertFalse(settled is ZchatComposeState.Error, "a stored transaction got the error screen with Retry: $settled")
            assertEquals(SubmitAttemptProof.MAY_HAVE_BEEN_SENT, noticeText(settled), "the notice for a message")
            assertFormCleared(vm)

            pokeEverything(vm, shown)
            assertEquals(1, proposals.submitted.size, "a second message was submitted while the first may still go out")
        }

    /** (d) Control, green before and after: a shortfall that stored nothing still re-prices a Send All. */
    @Test
    fun `control - a Send All short with nothing stored is re-priced`() =
        runBlocking {
            proposals.nextSubmit = nothingStoredShortfall()
            val vm = buildVm()
            sendFirst(vm) { it.ready().onAmountSelect(MessageAmount.SEND_ALL) }
            assertEquals(1, proposals.submitted.size, "fixture sanity: the Send All reached the submit")

            awaitSendAllQuote(vm)
            val settled = vm.state.value
            assertTrue(
                settled is ZchatComposeState.Ready && settled.sendAllNote.contains("Amount updated"),
                "a Send All that provably sent nothing must be re-priced: $settled",
            )
        }

    /** (d) Control, green before and after: a custom amount short with nothing stored shows the shortfall notice. */
    @Test
    fun `control - a custom amount short with nothing stored shows the shortfall notice`() =
        runBlocking {
            proposals.nextSubmit = nothingStoredShortfall()
            val vm = buildVm()
            sendFirst(vm) { customAmount(it, "0", "001") }
            assertEquals(1, proposals.submitted.size, "fixture sanity: the send reached the submit")

            val settled = vm.state.value
            assertTrue(settled is ZchatComposeState.Error, "a send that provably sent nothing keeps its Retry: $settled")
            assertTrue(
                settled.message in
                    setOf(
                        CreateChunkedMessageProposalUseCase.INSUFFICIENT_PAYMENT_MESSAGE,
                        CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE,
                        CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE,
                    ),
                "not the shortfall notice: ${settled.message}",
            )
        }

    /** (e) Control, green before and after: a first message marks its INIT submitted exactly once. */
    @Test
    fun `control - a first message marks its INIT submitted once, and a reply does not`() =
        runBlocking {
            sendFirst(buildVm()) { }
            assertEquals(1, proposals.submitted.size, "fixture sanity: the first message was submitted")
            assertEquals(1, initMarks.get(), "the INIT must be marked submitted exactly once")

            // Round 8 P4 (F6): the INIT went out, so this peer has a chat. New Chat sends it nothing more (it has no
            // ratchet): the second message is handed to the chat, which sends it as the reply.
            sendFirst(buildVm()) { }
            assertEquals(1, proposals.submitted.size, "New Chat sent a second message outside the chat")
            assertEquals(1, initMarks.get(), "a reply must not mark an INIT again")
        }

    /**
     * (f) The review re-check of D2 (no path other than the catch may re-submit). updateState always rebuilt the form
     * with isSending = false, and the double-tap guard in doSendMessage reads that flag. So a wallet update while a
     * send is in flight (the balance or the transaction list moves as soon as the SDK stores the transaction; here
     * while the value send waits for the PIN / biometric check) re-enabled Send with the same message and amount, and
     * one tap started a second paid send.
     */
    @Test
    fun `a wallet update while a send is in flight does not let Send start a second one`() =
        runBlocking {
            val biometrics = Zc101SuspendingBiometricRepository()
            val vm = buildVm(biometrics)
            vm.ready().onRecipientChange(PEER_ADDRESS)
            vm.ready().onMessageChange(MESSAGE)
            customAmount(vm, "0", "001")
            vm.ready().onSendClick()
            NewChatPriceQuestion.payIfAsked(vm)
            withTimeout(TIMEOUT_MS) { biometrics.promptShown.await() }
            assertTrue(vm.ready().isSending, "fixture sanity: the send is in flight")

            accounts.account.value = walletWith(orchard = 0, ironwood = IRONWOOD - MOVED)
            val during = vm.state.value
            assertTrue(
                during is ZchatComposeState.Ready && during.isSending,
                "a wallet update re-enabled Send while a send was in flight: $during",
            )

            during.onSendClick()
            biometrics.approve()
            withTimeout(TIMEOUT_MS) {
                while (replaces() == 0) delay(5)
            }
            withTimeoutOrNull(QUOTE_TIMEOUT_MS) {
                while (proposals.submitted.size < 2) delay(5)
            }
            assertEquals(1, proposals.submitted.size, "a second send started while the first was in flight")
        }

    // --------------------------------------------------------------- harness

    /** Enters the peer and a message, applies [amount], taps Send and waits until the send settles. */
    private suspend fun sendFirst(
        vm: ZchatComposeVM,
        amount: (ZchatComposeVM) -> Unit,
    ): ZchatComposeState.Ready {
        vm.ready().onRecipientChange(PEER_ADDRESS)
        vm.ready().onMessageChange(MESSAGE)
        amount(vm)
        awaitSendAllQuote(vm)
        val shown = vm.ready()
        val navigationsBefore = replaces()
        shown.onSendClick()
        NewChatPriceQuestion.payIfAsked(vm)
        settle(vm, navigationsBefore)
        return shown
    }

    /**
     * Everything a user can still do after the failure: tap Send on the form still drawn (a double tap), let the
     * wallet's balance move, invoke every action the screen now offers, and tap Send on any form that comes back.
     */
    private suspend fun pokeEverything(
        vm: ZchatComposeVM,
        shown: ZchatComposeState.Ready,
    ) {
        var navigationsBefore = replaces()
        shown.onSendClick()
        NewChatPriceQuestion.payIfAsked(vm)
        settle(vm, navigationsBefore)

        accounts.account.value = walletWith(orchard = 0, ironwood = IRONWOOD - MOVED)
        proposals.ironwoodNotes = listOf(IRONWOOD - MOVED)

        val now = vm.state.value
        if (now !is ZchatComposeState.Ready) {
            actionsOf(now).forEach { action ->
                navigationsBefore = replaces()
                action()
                settle(vm, navigationsBefore)
            }
        }
        if (vm.state.value is ZchatComposeState.Ready) {
            awaitSendAllQuote(vm)
            navigationsBefore = replaces()
            vm.ready().onSendClick()
            NewChatPriceQuestion.payIfAsked(vm)
            settle(vm, navigationsBefore)
        }
    }

    private fun customAmount(
        vm: ZchatComposeVM,
        whole: String,
        fraction: String,
    ) {
        vm.ready().onAmountSelect(MessageAmount.CUSTOM)
        vm.ready().onCustomAmountChange(whole + DecimalFormatSymbols.getInstance().decimalSeparator + fraction)
    }

    /**
     * A Send All is sent only once priced, and a re-price shows its note only once priced again: waits for the price
     * (or gives up quietly: a send then does nothing).
     */
    private suspend fun awaitSendAllQuote(vm: ZchatComposeVM) {
        withTimeoutOrNull(QUOTE_TIMEOUT_MS) {
            while (true) {
                val ready = vm.state.value as? ZchatComposeState.Ready ?: return@withTimeoutOrNull
                if (ready.selectedAmount != MessageAmount.SEND_ALL || ready.sendAllAmountDisplay.isNotEmpty()) {
                    return@withTimeoutOrNull
                }
                delay(5)
            }
        }
    }

    /** Waits while the screen is loading or sending, until it navigates to the chat or shows a result. */
    private suspend fun settle(
        vm: ZchatComposeVM,
        navigationsBefore: Int,
    ) {
        withTimeout(TIMEOUT_MS) {
            while (replaces() == navigationsBefore) {
                val s = vm.state.value
                if (s !is ZchatComposeState.Loading && !(s is ZchatComposeState.Ready && s.isSending)) break
                delay(5)
            }
        }
    }

    private fun replaces(): Int = navigationRouter.commands.count { it == "replace" }

    /** The text a non-form state shows (a `message` property), or null for the form, loading or none. */
    private fun noticeText(state: ZchatComposeState): String? =
        if (state is ZchatComposeState.Ready || state is ZchatComposeState.Loading) {
            null
        } else {
            state.javaClass.declaredFields
                .firstOrNull { it.name == "message" && it.type == String::class.java }
                ?.apply { isAccessible = true }
                ?.get(state) as String?
        }

    /** Every `() -> Unit` a non-form state holds: the actions its screen offers. */
    private fun actionsOf(state: ZchatComposeState): List<() -> Unit> =
        state.javaClass.declaredFields
            .filter { Function0::class.java.isAssignableFrom(it.type) }
            .map { field ->
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                field.get(state) as () -> Unit
            }

    /** The form the screen would show next: no message, and the default amount (no custom amount, no Send All). */
    private fun assertFormCleared(vm: ZchatComposeVM) {
        assertEquals("", field<String>(vm, "message"), "the message is kept, so a tap can build the same send again")
        assertEquals(
            MessageAmount.MINIMAL,
            field<MessageAmount>(vm, "selectedAmount"),
            "the amount tier is kept, so a tap can build the same payment again",
        )
        assertEquals("", field<String>(vm, "customAmountText"), "the custom amount is kept")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(
        vm: ZchatComposeVM,
        name: String,
    ): T =
        ZchatComposeVM::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(vm) as T

    private fun storedRejection(
        code: Int,
        description: String,
    ) = SubmitResult.Failure(txIds = listOf(STORED_TXID), code = code, description = description)

    private fun nothingStoredShortfall() =
        SubmitResult.Failure(txIds = emptyList(), code = 0, description = "Insufficient balance (have 0, need 17000 including fee)")

    private suspend fun buildVm(
        biometrics: Zc101SuspendingBiometricRepository = Zc101SuspendingBiometricRepository().apply { approve() },
    ): ZchatComposeVM {
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
                        biometricRepository = biometrics,
                    ),
                navigationRouter = navigationRouter,
                prefillZchat = PrefillZchatUseCase(),
                transactionRepository = EmptyTransactionRepository(),
                zchatPreferences = countingInitMarks(conversations.wrap(prefs())),
                validateAddress = ValidateAddressUseCase(synchronizerProvider),
            )
        vms += vm
        return vm
    }

    private fun ZchatComposeVM.ready(): ZchatComposeState.Ready =
        state.value as? ZchatComposeState.Ready
            ?: error("VM state was ${state.value}, expected Ready")

    /** Counts markConversationInitSubmitted and passes every call on to [prefs]. */
    private fun countingInitMarks(prefs: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "CountingInitMarks($prefs)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> {
                    if (method.name == "markConversationInitSubmitted") initMarks.incrementAndGet()
                    try {
                        method.invoke(prefs, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }
        } as ZchatPreferences

    /** A Vault peer, the cost notice accepted, nothing else stored. */
    private fun prefs(): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "StoredRejectionComposePreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "getConversationModeOrNull", "getConversationMode" -> ConversationMode.VAULT
                "hasAcknowledgedMessageCost" -> true
                else -> nothingStored(method.returnType)
            }
        } as ZchatPreferences

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

    /**
     * Send All probes priced like SDK 3.0.0 over [ironwoodNotes] ([zip317ChatPrice]). A real proposal is a
     * hand-encoded Ironwood proposal carrying the fee its probe was priced at (so a quoted Send All matches it), and
     * each submit is recorded and answered with [nextSubmit] once, then with Success.
     */
    private class ScriptedProposals(
        @Volatile var ironwoodNotes: List<Long>,
        val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val submitted = java.util.Collections.synchronizedList(mutableListOf<Proposal>())

        @Volatile var nextSubmit: SubmitResult? = null

        override suspend fun probeZip321Fee(
            account: WalletAccount,
            zip321Uri: String,
        ): FeeProbe = zip317ChatPrice(ironwoodNotes, zip321Amounts(zip321Uri))

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            val amounts = zip321Amounts(zip321Uri)
            val synthetic = syntheticZip321Proposal("usynthetic-stored-rejection-peer", amounts.first(), "")
            val priced = zip317ChatPrice(ironwoodNotes, amounts) as? FeeProbe.Built ?: return synthetic
            return synthetic.copy(
                proposal =
                    SweepProposalBytes.proposal(
                        inputs = ironwoodNotes.map { SweepProposalBytes.ironwood(it) },
                        fee = priced.fee.value,
                        paymentPools = amounts.map { SweepProposalBytes.IRONWOOD },
                        change = priced.change.orEmpty(),
                    ),
            )
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
            submitted += proposal
            val scripted = nextSubmit
            nextSubmit = null
            return scripted ?: SubmitResult.Success(txIds = listOf("synthetic-txid-${submitted.size}"))
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
        const val IRONWOOD = 443_002L
        const val MOVED = 20_000L
        const val TIMEOUT_MS = 10_000L
        const val QUOTE_TIMEOUT_MS = 2_000L
        const val MESSAGE = "synthetic hello"
        const val STORED_TXID = "synthetic-stored-txid"

        /** Bare-address shaped and longer than 100 chars, as the screen requires. Synthetic. */
        val PEER_ADDRESS = "u1synthetic" + "peer".repeat(25)
        val SELF_ADDRESS = "u1synthetic" + "self".repeat(25)
    }
}
