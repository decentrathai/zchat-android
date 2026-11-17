package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.SynchronizerError
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
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmation
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1272, ZC-1306, ZC-1308, ZC-1309: one phone's chat store and wallet, seen by the two screens a first message crosses:
 * New Chat (a real ZchatComposeVM) and the chat (a real ChatViewModel, [FundsConfirmingChat]). Pay on one and the chat
 * opened on the other therefore read the same rows, marks and wallet. Everything is synthetic.
 *
 * [store] does not name the record the fix will keep for the legs a question covered: any store method this fixture does
 * not know works as a small durable map ([RememberingStore]), so a new one needs no change here.
 */
internal class OneDecisionWorld(
    val peer: String = PEER,
) {
    val funds: FundsConfirmingChat = FundsConfirmingChat(peer)
    val chat: FirstMessagePlanChat get() = funds.chat
    val wallet: FundsConfirmingChat.MeteredFunds get() = funds.funds
    val memory = RememberingStore(chat.prefs)
    val store: ZchatPreferences get() = memory.proxy
    val navigation = Zc101RecordingNavigationRouter()
    val accounts = ChangingAccountDataSource(walletWith(orchard = 0, ironwood = ONE_NOTE))

    fun submits(): Int = funds.seam.submits.get()

    /** The network fee every build and every price probe gets from now on. */
    fun fee(zatoshi: Long) {
        wallet.feeFor = { zatoshi }
        funds.seam.feeProbe = { FeeProbe.Built(Zatoshi(zatoshi), usesOrchardInputs = false, transactionCount = 1) }
    }

    /** The whole balance still confirming, as the wallet after a fresh deposit: the SDK prices it as short. */
    fun fundsStillConfirming() {
        accounts.account.value = confirmingWallet()
        funds.seam.feeProbe = { FeeProbe.Short }
        funds.plan = "WaitForFunds"
    }

    /** The deposit confirmed: the same wallet can pay now. */
    fun fundsConfirmed() {
        accounts.account.value = walletWith(orchard = 0, ironwood = ONE_NOTE)
        fee(FundsConfirmingChat.NORMAL_FEE)
        funds.plan = "Fundable"
    }

    /** A chat screen over this store and this wallet's balances (a wallet that can pay one handshake is asked about it). */
    suspend fun chatScreen(): ChatViewModel =
        funds.screen().also {
            PaymentHarness.setField(it, "zchatPreferences", store)
            PaymentHarness.setField(it, "accountDataSource", accounts)
            canHideMessages(it)
        }

    /** New Chat with the recipient and [text] typed; the mode it offers is the stored one. */
    suspend fun newChat(text: String = FIRST_WORDS): ZchatComposeVM {
        val synchronizers = AnsweringSynchronizerProvider()
        val selected = GetSelectedWalletAccountUseCase(accounts)
        val vm =
            ZchatComposeVM(
                contactBook = EmptyContactBook(),
                getSelectedWalletAccount = selected,
                getDefaultUnifiedAddress = GetDefaultUnifiedAddressUseCase(synchronizers, selected),
                createChunkedMessageProposal =
                    CreateChunkedMessageProposalUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository =
                            ZashiProposalRepositoryImpl(
                                accountDataSource = accounts,
                                proposalDataSource = wallet,
                                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                            ).also { SendHarnessScopes.current().own(it) },
                        accountDataSource = accounts,
                        navigationRouter = Zc101RecordingNavigationRouter(),
                        biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                    ),
                navigationRouter = navigation,
                prefillZchat = PrefillZchatUseCase(),
                transactionRepository = EmptyTransactionRepository(),
                zchatPreferences = store,
                validateAddress = ValidateAddressUseCase(synchronizers),
            )
        SendHarnessScopes.current().own(vm)
        ready(vm).onRecipientChange(peer)
        ready(vm).onMessageChange(text)
        return vm
    }

    fun ready(vm: ZchatComposeVM): ZchatComposeState.Ready =
        vm.state.value as? ZchatComposeState.Ready ?: throw AssertionError("New Chat is not a form: ${vm.state.value}")

    /** Taps Send and waits until the tap's work ended: a question, a notice, a refusal or a form at rest. */
    suspend fun tap(vm: ZchatComposeVM) {
        ready(vm).onSendClick()
        settle(vm)
    }

    /** Answers Pay on the open question and waits for the send to end; returns what the question asked. */
    suspend fun pay(vm: ZchatComposeVM): PaidActionConfirmation {
        val asked = NewChatPriceQuestion.require(vm)
        NewChatPriceQuestion.pay(vm)
        settle(vm)
        return asked
    }

    suspend fun settle(vm: ZchatComposeVM) {
        withTimeout(WAIT_MS) {
            while (true) {
                val state = vm.state.value
                val working =
                    state is ZchatComposeState.Loading ||
                        NewChatPriceQuestion.askingPrice(state) ||
                        (state is ZchatComposeState.Ready && state.isSending)
                if (NewChatPriceQuestion.open(vm) != null || navigation.commands.isNotEmpty() || !working) break
                delay(POLL_MS)
            }
        }
    }

    /** The chat's question for the payment, or null. */
    fun paidQuestion(vm: ChatViewModel): PaidActionConfirmation? =
        (HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsPaidActionConfirmation)?.confirmation

    fun askedHandshakeConsent(vm: ChatViewModel): Boolean =
        HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsHandshakeSpendConsent

    /** What opening the chat does (the AndroidChatDetail effect): the Tunnel bootstrap. */
    suspend fun openChat(vm: ChatViewModel) {
        vm.ensureNostrBootstrapSent(peer)
        HandshakeHarness.awaitIdle(vm)
    }

    /** The contact's key answer reached this phone: the exchange is complete and our ZBOOT is due. */
    fun contactAnswered() {
        memory.answers["isE2EKeyExchangeComplete"] = true
        memory.answers["getE2EPeerPublicKey"] = E2EEncryption.generateKeyPair().publicKey
    }

    private fun confirmingWallet() =
        walletWith(orchard = 0, ironwood = 0).let { empty ->
            empty.copy(
                unified = empty.unified.copy(ironwood = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(ONE_NOTE))),
            )
        }

    companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 5L
        const val ONE_NOTE = 443_002L
        const val FIRST_WORDS = "synthetic first words"
        const val NORMAL_LEG = FundsConfirmingChat.NORMAL_FEE + FundsConfirmingChat.LEG_OUTPUTS

        /** Bare-address shaped and longer than 100 characters, as New Chat requires. Synthetic. */
        val PEER = "u1synthetic" + "peer".repeat(25)
        val OTHER_PEER = "u1synthetic" + "othr".repeat(25)
        val SELF_ADDRESS = "u1synthetic" + "self".repeat(25)

        /** More than one memo, whatever its framing. */
        val LONG_WORDS = "synthetic long message ".repeat(60)
    }

    /**
     * [delegate], plus: the two standing answers under test control ([costAcknowledged], [consent]) with their writes
     * counted, fixed [answers] by method name, and, for any other `setX(.., value)`, a durable value that `getX(..)` and
     * `isX(..)` read back. Marks the delegate keeps itself (owed KEX, rows, modes, keys) stay with it.
     */
    internal class RememberingStore(
        private val delegate: ZchatPreferences,
    ) {
        @Volatile var costAcknowledged = true

        @Volatile var consent = false

        val consentWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val answers = ConcurrentHashMap<String, Any>()
        private val remembered = ConcurrentHashMap<String, Any>()

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                val name = method.name
                val all = args?.toList().orEmpty()
                answers[name]?.let { return@newProxyInstance it }
                when {
                    name == "hasAcknowledgedMessageCost" -> costAcknowledged
                    name == "setAcknowledgedMessageCost" -> {
                        costAcknowledged = true
                        if (method.returnType == java.lang.Boolean.TYPE) true else null
                    }
                    name == "hasConsentedToHandshakeSpend" -> consent
                    name == "setConsentedToHandshakeSpend" -> {
                        consent = true
                        consentWrites += name
                        true
                    }
                    name in DELEGATE_KEEPS -> pass(method, args)
                    name.startsWith("set") && all.isNotEmpty() -> {
                        val key = name.removePrefix("set") + all.dropLast(1)
                        val value = all.last()
                        if (value == null) remembered.remove(key) else remembered[key] = value
                        pass(method, args)
                    }
                    name.startsWith("get") -> remembered[name.removePrefix("get") + all] ?: pass(method, args)
                    name.startsWith("is") -> remembered[name.removePrefix("is") + all] ?: pass(method, args)
                    else -> pass(method, args)
                }
            } as ZchatPreferences

        private fun pass(
            method: java.lang.reflect.Method,
            args: Array<out Any?>?,
        ): Any? =
            try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }

        private companion object {
            val DELEGATE_KEEPS =
                setOf(
                    "setKexOwed",
                    "setKexOwedAccount",
                    "setDraft",
                    "setConversationMode",
                    "setE2EOurKeys",
                    "setE2EKeyChanged",
                )
        }
    }

    /** Answers the two Synchronizer calls New Chat makes: our own address, and "valid" for any other. */
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
}

/** The cost notice is on the chat's screen. The screens here are allocated bare, so its backing flow is read. */
internal fun costNoticeShown(vm: ChatViewModel): Boolean {
    val field = ChatViewModel::class.java.getDeclaredField("_showCostDisclaimer").apply { isAccessible = true }
    return (field.get(vm) as StateFlow<*>).value == true
}

/** The screens here are allocated bare: gives one the set "Delete for me" adds to. */
internal fun canHideMessages(vm: ChatViewModel) {
    PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(emptySet()))
}

/** A New Chat form in [mode] with its stored mode set, so the form offers it. */
internal suspend fun OneDecisionWorld.newChatIn(
    mode: ConversationMode,
    text: String = OneDecisionWorld.FIRST_WORDS,
): ZchatComposeVM {
    chat.fixture.mode = mode
    return newChat(text)
}
