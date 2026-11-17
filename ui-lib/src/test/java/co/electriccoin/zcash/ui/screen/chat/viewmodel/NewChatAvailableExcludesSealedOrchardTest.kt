package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
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
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatZip317ProposalDataSource
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 9 R9-1 (REVIEW_7C F11) — New Chat's "Available" counts ZEC the send cannot use.
 *
 * The HONOR showed "Available: 0.00580 ZEC": 441,000 zatoshi of Ironwood plus 139,000 of sealed Orchard (ZIP 258). A
 * send to another person can spend only the Ironwood (WalletAccount.peerSpendableBalance), so the figure promised
 * 139,000 zatoshi more than any New Chat send can use, and a wallet holding only sealed Orchard showed a non-zero,
 * non-red "Available".
 *
 * Wanted: "Available" is the peer-spendable figure, and the zero warning follows it.
 *
 * A real ZchatComposeVM on the JVM, as in [ZchatSendAllQuoteTest]. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewChatAvailableExcludesSealedOrchardTest {
    private val vms = mutableListOf<ZchatComposeVM>()
    private val accountDataSource = MutableAccountDataSource(account(ironwood = IRONWOOD))
    private val proposalDataSource = ChatZip317ProposalDataSource(ironwoodNotes = listOf(IRONWOOD))
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
    fun available_shows_what_can_be_sent_not_the_sealed_orchard() =
        runBlocking {
            val vm = buildVm(prefs(emptyMap()))
            vm.ready().onRecipientChange(PEER_ADDRESS)
            val state = vm.ready()

            assertEquals(
                zec(IRONWOOD),
                state.availableBalanceDisplay,
                "Available must count only the Ironwood a send can use (441,000), not the sealed Orchard too",
            )
            assertTrue(zec(IRONWOOD + ORCHARD) != state.availableBalanceDisplay, "fixture sanity")
            assertEquals(IRONWOOD, state.spendableBalanceZatoshi, "the zero warning must follow the Available figure")
        }

    @Test
    fun a_wallet_holding_only_sealed_orchard_shows_nothing_available() =
        runBlocking {
            accountDataSource.account.value = account(ironwood = 0L)
            proposalDataSource.ironwoodNotes = emptyList()
            val vm = buildVm(prefs(emptyMap()))
            vm.ready().onRecipientChange(PEER_ADDRESS)
            val state = vm.ready()

            assertEquals("0 ZEC", state.availableBalanceDisplay, "139,000 sealed Orchard cannot be sent to anyone")
            assertEquals(0L, state.spendableBalanceZatoshi, "Available 0 must be shown as the zero warning")
        }

    /** The screen's own "Available" format (five decimals, the phone's language), for [zatoshi]. */
    private fun zec(zatoshi: Long): String = String.format("%.5f ZEC", zatoshi / 100_000_000.0)

    // --------------------------------------------------------------- harness

    private suspend fun buildVm(zchatPreferences: ZchatPreferences): ZchatComposeVM {
        val synchronizerProvider = AnsweringSynchronizerProvider()
        val getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accountDataSource)
        val vm =
            ZchatComposeVM(
                contactBook = EmptyContactBook(),
                getSelectedWalletAccount = getSelectedWalletAccount,
                getDefaultUnifiedAddress =
                    GetDefaultUnifiedAddressUseCase(synchronizerProvider, getSelectedWalletAccount),
                createChunkedMessageProposal =
                    CreateChunkedMessageProposalUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository =
                            ZashiProposalRepositoryImpl(
                                accountDataSource = accountDataSource,
                                proposalDataSource = proposalDataSource,
                                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                            ),
                        accountDataSource = accountDataSource,
                        navigationRouter = Zc101RecordingNavigationRouter(),
                        biometricRepository = Zc101SuspendingBiometricRepository(),
                    ),
                navigationRouter = navigationRouter,
                prefillZchat = PrefillZchatUseCase(),
                transactionRepository = EmptyTransactionRepository(),
                zchatPreferences = zchatPreferences,
                validateAddress = ValidateAddressUseCase(synchronizerProvider),
            )
        vms += vm
        return vm
    }

    private fun ZchatComposeVM.ready(): ZchatComposeState.Ready =
        state.value as? ZchatComposeState.Ready
            ?: error("VM state was ${state.value}, expected Ready")

    /** ZchatPreferences as a dynamic Proxy: [answers] by method name, "nothing stored" for the rest. */
    private fun prefs(answers: Map<String, Any?>): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when {
                method.name == "toString" -> "ZchatSendAllQuotePreferences"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args!![0]
                answers.containsKey(method.name) -> answers[method.name]
                // Round 8 P4: nothing stored means no conversation yet, so the next message is the INIT (as the
                // real store answers). "false" said the peer already had a chat, which New Chat now hands over.
                method.name == "needsConversationInit" -> true
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

    /** One selected Zashi account whose balances a test can move. */
    private class MutableAccountDataSource(
        initial: ZashiAccount
    ) : AccountDataSource {
        val account = MutableStateFlow(initial)

        override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(initial))
        override val selectedAccount: Flow<WalletAccount?> = account
        override val zashiAccount: Flow<ZashiAccount?> = account

        override suspend fun getAllAccounts(): List<WalletAccount> = listOf(account.value)

        override suspend fun getSelectedAccount(): WalletAccount = account.value

        override suspend fun getZashiAccount(): ZashiAccount = account.value

        override suspend fun selectAccount(account: Account) = Unit

        override suspend fun selectAccount(account: WalletAccount) = Unit

        override suspend fun importKeystoneAccount(
            ufvk: String,
            seedFingerprint: String,
            index: Long,
        ): Account = error("not used in this test")

        override suspend fun requestNextShieldedAddress() = Unit
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
        /** The HONOR on 24 Sep (REVIEW_7C F11): 441,000 Ironwood that can be sent, 139,000 sealed Orchard that can't. */
        const val IRONWOOD = 441_000L
        const val ORCHARD = 139_000L

        /** Bare-address shaped and longer than 100 chars, as the screen requires. Synthetic. */
        val PEER_ADDRESS = "u1synthetic" + "peer".repeat(25)
        val SELF_ADDRESS = "u1synthetic" + "self".repeat(25)

        private fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        fun account(
            ironwood: Long,
            orchard: Long = ORCHARD,
        ): ZashiAccount =
            runBlocking {
                ZashiAccount(
                    sdkAccount = AccountFixture.new(),
                    unified =
                        UnifiedInfo(
                            WalletAddress.Unified.new("usynthetic-self"),
                            balance(orchard),
                            balance(ironwood),
                        ),
                    sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                    transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                    isSelected = true,
                )
            }
    }
}
