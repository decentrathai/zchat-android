package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
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
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
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
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R2-6 (c), p2-3414e39f2fe4, New-Chat half: a first message refused before submission is still sent as the
 * first message (a v4 INIT naming our address) the next time the user sends it from New Chat.
 *
 * At 566b866d2 ZchatComposeVM.doSendMessage calls getOrCreateConversationId, which stores the conversation id,
 * and only then builds the send. When the use case refuses it (an Orchard-only wallet, a shortfall, a Send All
 * that comes back short), the id stays. The next New-Chat send to that address reads isNew = false and sends a
 * REPLY with only a hash of our address, which the recipient cannot place. A Send All is also priced for the
 * REPLY's memo count (isNewConversation reads the stored id), not the INIT it should send.
 *
 * Harness: the ZchatSendAllQuoteTest idiom (a real ZchatComposeVM on an unconfined main dispatcher, the real
 * use case and repository) with the real conversation-id store ([RealConversationIds]). All addresses are
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailedFirstMessageRetriedAsInitComposeTest {
    private val vms = mutableListOf<ZchatComposeVM>()
    private val conversations = RealConversationIds()
    private val accounts = ChangingAccountDataSource(walletWith(orchard = ORCHARD, ironwood = IRONWOOD))
    private val proposals = ScriptedProposals(ironwoodNotes = listOf(IRONWOOD))
    private val navigationRouter = Zc101RecordingNavigationRouter()

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

    @Test
    fun `a first message refused because the wallet is Orchard-only is sent as an INIT once Ironwood arrives`() =
        runBlocking {
            accounts.account.value = walletWith(orchard = ORCHARD, ironwood = 0)
            val refused = send("synthetic first hello")
            assertTrue(refused is ZchatComposeState.Error, "fixture sanity: the sealed-Orchard send is refused: $refused")
            assertTrue(proposals.proposedUris.isEmpty(), "fixture sanity: refused before any proposal")

            accounts.account.value = walletWith(orchard = ORCHARD, ironwood = IRONWOOD)
            send("synthetic first hello")

            val sent = firstMemo(proposals.proposedUris.single())
            assertTrue(isInitFrom(sent, SELF_ADDRESS), "the first message went out as a REPLY: ${sent.take(40)}…")
            assertEquals(1, proposals.recording.submittedProposals.size)
        }

    @Test
    fun `a first message refused for a shortfall is sent again as an INIT`() =
        runBlocking {
            proposals.failNextProposalWith = IllegalStateException("Insufficient balance (have 1000, need 17000)")
            val refused = send("synthetic first hello")
            assertTrue(refused is ZchatComposeState.Error, "fixture sanity: the short send is refused: $refused")
            assertTrue(isInitFrom(firstMemo(proposals.proposedUris[0]), SELF_ADDRESS), "fixture sanity: it was an INIT")
            assertTrue(proposals.recording.submittedProposals.isEmpty(), "fixture sanity: nothing was submitted")

            send("synthetic first hello")

            assertEquals(2, proposals.proposedUris.size)
            val sent = firstMemo(proposals.proposedUris[1])
            assertTrue(isInitFrom(sent, SELF_ADDRESS), "the retried first message went out as a REPLY: ${sent.take(40)}…")
            assertEquals(1, proposals.recording.submittedProposals.size)
        }

    /**
     * A Send All the SDK refuses as short re-prices on the same screen. The new price must be for the INIT that
     * the next tap sends (more memo outputs for a long first message), not for a REPLY.
     */
    @Test
    fun `after a Send All that came back short, the new price is for the INIT, not a REPLY`() =
        runBlocking {
            val initChunks = ZMSGProtocol.calculateV4ChunkCount(LONG_FIRST_MESSAGE, true, SELF_ADDRESS)
            val replyChunks = ZMSGProtocol.calculateV4ChunkCount(LONG_FIRST_MESSAGE, false, SELF_ADDRESS)
            assertNotEquals(initChunks, replyChunks, "fixture sanity: the INIT needs more memo outputs than a REPLY")

            val vm = buildVm()
            vm.ready().onRecipientChange(PEER_ADDRESS)
            vm.ready().onMessageChange(LONG_FIRST_MESSAGE)
            vm.ready().onAmountSelect(MessageAmount.SEND_ALL)
            assertEquals(initChunks, memoCount(proposals.probedUris.last()), "fixture sanity: first priced as an INIT")

            proposals.failNextProposalWith = IllegalStateException("Insufficient balance (have 1000, need 17000)")
            val probesBefore = proposals.probedUris.size
            vm.ready().onSendClick()
            NewChatPriceQuestion.payIfAsked(vm)
            withTimeout(TIMEOUT_MS) {
                while ((vm.state.value as? ZchatComposeState.Ready)?.sendAllNote?.contains("Amount updated") != true) {
                    delay(5)
                }
            }
            assertTrue(proposals.recording.submittedProposals.isEmpty(), "fixture sanity: nothing was submitted")
            assertTrue(proposals.probedUris.size > probesBefore, "fixture sanity: the Send All was priced again")

            assertEquals(
                initChunks,
                memoCount(proposals.probedUris.last()),
                "the Send All was re-priced for a REPLY ($replyChunks memo outputs) though the next send is the INIT",
            )
        }

    /** Control, green before and after: once the INIT was sent, the next New-Chat message is a REPLY. */
    @Test
    fun `control - an INIT that was sent is never sent again`() =
        runBlocking {
            send("synthetic first hello")
            send("synthetic second message")

            // Round 8 P4 (F6): once the INIT went out the peer has a chat, and New Chat hands the second message to it
            // (the chat sends it as the REPLY, through its ratchet). New Chat builds nothing more.
            assertEquals(1, proposals.recording.submittedProposals.size, "New Chat sent a second message outside the chat")
            assertTrue(isInitFrom(firstMemo(proposals.proposedUris[0]), SELF_ADDRESS))
            assertEquals(1, proposals.proposedUris.size, "the INIT was sent a second time")
        }

    // --------------------------------------------------------------- harness

    /** Opens New Chat, enters the peer and [message] with the default amount, taps Send; returns the settled state. */
    private suspend fun send(message: String): ZchatComposeState {
        val vm = buildVm()
        val navigationsBefore = navigationRouter.commands.size
        vm.ready().onRecipientChange(PEER_ADDRESS)
        vm.ready().onMessageChange(message)
        vm.ready().onSendClick()
        NewChatPriceQuestion.payIfAsked(vm)
        withTimeout(TIMEOUT_MS) {
            while (vm.state.value !is ZchatComposeState.Error && navigationRouter.commands.size == navigationsBefore) {
                delay(5)
            }
        }
        return vm.state.value
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
                zchatPreferences = conversations.wrap(prefs()),
                validateAddress = ValidateAddressUseCase(synchronizerProvider),
            )
        vms += vm
        return vm
    }

    private fun ZchatComposeVM.ready(): ZchatComposeState.Ready =
        state.value as? ZchatComposeState.Ready
            ?: error("VM state was ${state.value}, expected Ready")

    /** A Vault peer, the cost notice accepted, nothing else stored. */
    private fun prefs(): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "FirstMessageComposePreferences"
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
     * Send All probes priced like SDK 3.0.0 over [ironwoodNotes] ([zip317ChatPrice]); real proposals recorded and
     * answered with a synthetic proposal, or refused once with [failNextProposalWith]. Submits are recorded.
     */
    private class ScriptedProposals(
        var ironwoodNotes: List<Long>,
        val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val probedUris = mutableListOf<String>()
        val proposedUris = mutableListOf<String>()

        @Volatile var failNextProposalWith: Exception? = null

        override suspend fun probeZip321Fee(
            account: WalletAccount,
            zip321Uri: String,
        ): FeeProbe {
            probedUris += zip321Uri
            return zip317ChatPrice(ironwoodNotes, zip321Amounts(zip321Uri))
        }

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            proposedUris += zip321Uri
            failNextProposalWith?.let {
                failNextProposalWith = null
                throw it
            }
            return feeCarryingZip321Proposal("usynthetic-first-message-peer", zip321Uri, ironwoodNotes)
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
        const val ORCHARD = 139_000L
        const val TIMEOUT_MS = 10_000L

        /** Bare-address shaped and longer than 100 chars, as the screen requires. Synthetic. */
        val PEER_ADDRESS = "u1synthetic" + "peer".repeat(25)
        val SELF_ADDRESS = "u1synthetic" + "self".repeat(25)

        /** Fits one REPLY memo but not one INIT memo (the INIT spends ~110 bytes on our address). */
        val LONG_FIRST_MESSAGE = "synthetic long first message ".repeat(14).take(400)
    }
}
