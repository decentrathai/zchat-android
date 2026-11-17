package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.SendTransaction
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * B2 / Grok checklist H1, New Message half (FIRST-MSG-QUOTE-PREDICATE): the New Message screen prices a first
 * message by transaction history and sends it by the conversation store.
 *
 * `ZchatComposeVM.updateState` sizes the message with `!sentToAddresses.contains(recipient)`, where
 * `sentToAddresses` is every address this wallet ever sent a transaction to. The send (`doSendMessage`) builds
 * the INIT whenever `getOrCreateConversationIdForMessage(recipient).second` is true. A contact already paid from
 * the wallet's Send screen (or one whose conversation id was lost after a restore) is in the history but has no
 * conversation: the screen shows the chunk count and price of a REPLY and the send packs the longer INIT, which
 * carries our whole address in its first chunk. Only Send All re-checked this.
 *
 * Wanted: the screen asks the store the question the send asks (`needsConversationInit`), so the chunk count and
 * price it shows are those of the message that is sent.
 *
 * Harness: the FailedFirstMessageRetriedAsInitComposeTest idiom (a real ZchatComposeVM, the real use case and
 * repository, the real conversation-id store) with a transaction history holding one earlier send to the peer.
 * All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessageQuoteMatchesSendComposeTest {
    private val vms = mutableListOf<ZchatComposeVM>()
    private val conversations = RealConversationIds()
    private val accounts = ChangingAccountDataSource(walletWith(orchard = 0, ironwood = IRONWOOD))
    private val proposals = RecordingProposals()
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
    fun `a contact paid before from the wallet but never written to is shown the INIT it is sent`() =
        runBlocking {
            val initChunks = ZMSGProtocol.calculateV4ChunkCount(LONG_FIRST_MESSAGE, true, SELF_ADDRESS)
            val replyChunks = ZMSGProtocol.calculateV4ChunkCount(LONG_FIRST_MESSAGE, false, SELF_ADDRESS)
            assertNotEquals(initChunks, replyChunks, "fixture sanity: the INIT needs more memo outputs than a REPLY")
            assertTrue(conversations.impl.needsConversationInit(PEER_ADDRESS), "fixture sanity: no conversation yet")

            val vm = buildVm(history = listOf(earlierSendTo(PEER_ADDRESS)))
            vm.ready().onRecipientChange(PEER_ADDRESS)
            vm.ready().onMessageChange(LONG_FIRST_MESSAGE)
            val shown = vm.ready()

            vm.ready().onSendClick()
            NewChatPriceQuestion.payIfAsked(vm)
            withTimeout(TIMEOUT_MS) {
                while (vm.state.value !is ZchatComposeState.Error && proposals.recording.submittedProposals.isEmpty()) {
                    delay(5)
                }
            }
            val sent = proposals.proposedUris.single()
            assertTrue(isInitFrom(firstMemo(sent), SELF_ADDRESS), "fixture sanity: the first message went out as the INIT")
            assertEquals(initChunks, memoCount(sent), "fixture sanity: the INIT packs $initChunks memos")

            assertEquals(
                initChunks,
                shown.chunkCount,
                "the screen showed $replyChunks memo(s) for a REPLY, but the send is the $initChunks-memo INIT",
            )
            assertEquals(
                ZMSGProtocol.getMaxChunkedMessageLength(true, 10),
                shown.maxMessageLength,
                "the screen's length limit is the REPLY's, but the send is the INIT",
            )
        }

    /** Control, green before and after: a contact whose INIT went out is shown (and sent) a REPLY. */
    @Test
    fun `control - after the INIT went out the next message is shown as a REPLY`() =
        runBlocking {
            val (convId, _) = conversations.impl.getOrCreateConversationIdForMessage(PEER_ADDRESS)
            conversations.impl.markConversationInitSubmitted(PEER_ADDRESS, convId)

            val vm = buildVm(history = listOf(earlierSendTo(PEER_ADDRESS)))
            vm.ready().onRecipientChange(PEER_ADDRESS)
            vm.ready().onMessageChange(LONG_FIRST_MESSAGE)

            assertEquals(ZMSGProtocol.calculateV4ChunkCount(LONG_FIRST_MESSAGE, false, SELF_ADDRESS), vm.ready().chunkCount)
        }

    // --------------------------------------------------------------- harness

    private suspend fun earlierSendTo(address: String): Transaction {
        val overview =
            ScanPassHarness.overview(
                id = TransactionId.new("%064x".format(1)),
                minedHeight = 3_000_000L,
                blockTime = Instant.ofEpochSecond(1_790_000_000L),
                sent = true,
            )
        return SendTransaction.Success(
            id = overview.txId,
            amount = Zatoshi(50_000),
            timestamp = Instant.ofEpochSecond(1_790_000_000L),
            memoCount = 0,
            fee = Zatoshi(10_000),
            transactionOutputs = emptyList(),
            overview = overview,
            recipient = WalletAddress.Unified.new(address),
        )
    }

    private suspend fun buildVm(history: List<Transaction>): ZchatComposeVM {
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
                transactionRepository = HistoryRepository(history),
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
                "toString" -> "QuoteMatchesSendComposePreferences"
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

    /** Records every proposal (answered with one that carries its priced fee) and every submit; probes priced like SDK 3.0.0. */
    private class RecordingProposals(
        val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val proposedUris = mutableListOf<String>()

        override suspend fun probeZip321Fee(
            account: WalletAccount,
            zip321Uri: String,
        ): FeeProbe = zip317ChatPrice(listOf(IRONWOOD), zip321Amounts(zip321Uri))

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            proposedUris += zip321Uri
            return feeCarryingZip321Proposal("usynthetic-quote-compose-peer", zip321Uri, listOf(IRONWOOD))
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

    /** A wallet history of [history]: what `loadSentToAddresses` reads. */
    private class HistoryRepository(
        history: List<Transaction>,
    ) : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(history)

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
        const val TIMEOUT_MS = 10_000L

        /** Bare-address shaped and longer than 100 chars, as the screen requires. Synthetic. */
        val PEER_ADDRESS = "u1synthetic" + "peer".repeat(25)
        val SELF_ADDRESS = "u1synthetic" + "self".repeat(25)

        /** Fits one REPLY memo but not one INIT memo (the INIT spends ~110 bytes on our address). */
        val LONG_FIRST_MESSAGE = "synthetic long first message ".repeat(14).take(400)
    }
}
