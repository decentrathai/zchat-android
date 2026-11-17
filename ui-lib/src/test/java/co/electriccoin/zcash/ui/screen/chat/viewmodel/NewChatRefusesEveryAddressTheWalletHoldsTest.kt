package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatZip317ProposalDataSource
import co.electriccoin.zcash.ui.screen.chat.usecase.OwnAddressChokepointProbe
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
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
import kotlin.test.assertTrue

/**
 * ZC-602 (register, medium; the same payment of 5 Oct) — New Chat paid one of the wallet's own addresses.
 *
 * "Is this address mine" is answered by one store, the self registry (`ZchatPreferences.isSelfAddress`): the address
 * strings the app itself saw and kept. An address the wallet holds that the registry never saw (another account's
 * address, the account's Sapling address, a Receive address no chat load registered) is paid like a contact's: the
 * form enables Send, and the send chokepoint builds the transaction.
 *
 * Wanted: one answer for the send chokepoint and the New Chat form: the registry as today, or an account in
 * `AccountDataSource.allAccounts` holds the address now as its unified, Sapling or transparent address
 * (`walletHoldsAddress`). The form says the address is this wallet's and keeps Send off; a tap that lands anyway
 * prices nothing, builds nothing and writes nothing.
 *
 * All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewChatRefusesEveryAddressTheWalletHoldsTest {
    private val vms = mutableListOf<ZchatComposeVM>()
    private val accounts = ChangingAccountDataSource(walletWith(orchard = 0, ironwood = IRONWOOD))
    private val proposals = ChatZip317ProposalDataSource(ironwoodNotes = listOf(IRONWOOD))
    private val navigationRouter = Zc101RecordingNavigationRouter()
    private val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

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

    // ------------------------------------------------------------------ (a)

    @Test
    fun `an address any account holds is the wallet's own - unified, Sapling or transparent`() =
        runBlocking {
            val selected = walletWith(orchard = 0, ironwood = IRONWOOD)
            val other =
                KeystoneAccount(
                    sdkAccount = AccountFixture.new(),
                    unified = UnifiedInfo(WalletAddress.Unified.new(OTHER_ACCOUNT_UNIFIED), balance(0)),
                    transparent = TransparentInfo(WalletAddress.Transparent.new(OTHER_ACCOUNT_TRANSPARENT), Zatoshi(0)),
                    isSelected = false,
                )
            val held = listOf<WalletAccount>(selected, other)

            assertTrue(walletHolds(held, OTHER_ACCOUNT_UNIFIED), "another account's unified address is not recognised")
            assertTrue(walletHolds(held, selected.sapling.address.address), "the account's Sapling address is not recognised")
            assertTrue(walletHolds(held, OTHER_ACCOUNT_TRANSPARENT), "an account's transparent address is not recognised")
            assertTrue(walletHolds(held, selected.unified.address.address))
            assertFalse(walletHolds(held, PEER_ADDRESS), "a contact's address was called the wallet's own")
            assertFalse(walletHolds(held, ""), "an empty address was called the wallet's own")
            assertFalse(walletHolds(null, OTHER_ACCOUNT_UNIFIED), "no account list yet must answer no, never throw")
        }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `the app's send chokepoint also asks the account list`() {
        val module = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/di/UseCaseModule.kt")
        val call =
            Regex("""CreateChunkedMessageProposalUseCase\((.*?)\n\s*\)""", RegexOption.DOT_MATCHES_ALL).find(module)
                ?: throw AssertionError("fixture: the call that builds CreateChunkedMessageProposalUseCase")
        val factoryStart = module.lastIndexOf("factory {", call.range.first)
        assertTrue(factoryStart >= 0, "fixture: the factory block around the call")
        val factory = module.substring(factoryStart, call.range.last + 1)
        val wiring = call.groupValues[1].lines().firstOrNull { "isOwnAddress =" in it } ?: ""
        assertTrue("isSelfAddress" in wiring, "the registry must stay in the own-address check: $wiring")
        assertTrue(
            "walletHoldsAddress" in wiring,
            "the own-address check asks only the self registry, not the addresses the wallet's accounts hold: $wiring",
        )
        assertTrue("allAccounts" in factory, "the own-address check must read AccountDataSource.allAccounts: $factory")
    }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `New Chat refuses an own address the registry does not know, and a tap anyway does nothing`() =
        runBlocking {
            val vm = buildVm()
            vm.ready().onRecipientChange(OWN_UNKNOWN_TO_REGISTRY)
            vm.ready().onMessageChange("synthetic hello to myself")
            writes.clear()

            val form = vm.ready()
            assertTrue(form.isOwnAddress, "the form does not say this address belongs to this wallet")
            assertFalse(form.isValidAddress, "Send is on for one of the wallet's own addresses")

            form.onSendClick()
            withTimeout(TIMEOUT_MS) {
                while ((vm.state.value as? ZchatComposeState.Ready)?.isSending == true) delay(POLL_MS)
            }

            assertEquals(0, proposals.probedUris.size, "a send to an own address was priced")
            assertEquals(0, proposals.proposedUris.size, "a send to an own address was proposed")
            assertEquals(0, proposals.recording.submittedProposals.size, "a send to an own address was paid")
            assertEquals(emptyList(), writes.toList(), "a refused send wrote to the chat store (a mode, a conversation id)")
            assertTrue(vm.state.value is ZchatComposeState.Ready, "the form must stay: ${vm.state.value}")
            assertEquals(null, NewChatPriceQuestion.open(vm), "an own address was asked a price")
        }

    /** Control, green before and after: a contact's address stays sendable. */
    @Test
    fun `control - a contact's address is not the wallet's own`() =
        runBlocking {
            val vm = buildVm()
            vm.ready().onRecipientChange(PEER_ADDRESS)
            vm.ready().onMessageChange("synthetic hello")

            assertFalse(vm.ready().isOwnAddress)
            assertTrue(vm.ready().isValidAddress)
        }

    // --------------------------------------------------------------- harness

    /** `walletHoldsAddress(accounts, address)` next to WalletAccount, by reflection (it does not exist at the base). */
    private fun walletHolds(
        held: List<WalletAccount>?,
        address: String,
    ): Boolean {
        val function =
            runCatching { Class.forName("co.electriccoin.zcash.ui.common.model.WalletAccountKt") }
                .getOrNull()
                ?.methods
                ?.firstOrNull { it.name == "walletHoldsAddress" && it.parameterCount == 2 }
                ?: throw AssertionError(
                    "there is no walletHoldsAddress(accounts, address): only the self registry says which addresses are " +
                        "the wallet's own, so an address it never saw is paid like a contact's",
                )
        return function.invoke(null, held, address) as Boolean
    }

    /** New Chat over a chokepoint whose own-address answer knows [OWN_UNKNOWN_TO_REGISTRY]; the registry knows nothing. */
    private suspend fun buildVm(): ZchatComposeVM {
        val synchronizerProvider = AnsweringSynchronizerProvider()
        val getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accounts)
        val vm =
            ZchatComposeVM(
                contactBook = EmptyContactBook(),
                getSelectedWalletAccount = getSelectedWalletAccount,
                getDefaultUnifiedAddress = GetDefaultUnifiedAddressUseCase(synchronizerProvider, getSelectedWalletAccount),
                createChunkedMessageProposal =
                    OwnAddressChokepointProbe.useCase(
                        accounts = accounts,
                        proposals = proposals,
                        biometrics = Zc101SuspendingBiometricRepository().apply { approve() },
                        isOwnAddress = { it == OWN_UNKNOWN_TO_REGISTRY },
                    ),
                navigationRouter = navigationRouter,
                prefillZchat = PrefillZchatUseCase(),
                transactionRepository = EmptyTransactionRepository(),
                zchatPreferences = emptyRegistry(),
                validateAddress = ValidateAddressUseCase(synchronizerProvider),
            )
        vms += vm
        return vm
    }

    private fun ZchatComposeVM.ready(): ZchatComposeState.Ready =
        state.value as? ZchatComposeState.Ready
            ?: error("VM state was ${state.value}, expected Ready")

    /** A Shielded peer, the cost notice accepted, an empty self registry; every call that is not a read is logged. */
    private fun emptyRegistry(): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "EmptySelfRegistry"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "isSelfAddress" -> false
                "getConversationModeOrNull", "getConversationMode" -> ConversationMode.VAULT
                "hasAcknowledgedMessageCost" -> true
                "needsConversationInit" -> true
                "getOrCreateConversationIdForMessage", "getOrCreateConversationId" -> {
                    writes += method.name
                    "SYNTH602" to true
                }
                else -> {
                    if (READS.none { method.name.startsWith(it) }) writes += method.name
                    nothingStored(method.returnType)
                }
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
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
        const val OTHER_ACCOUNT_UNIFIED = "usynthetic-other-account"
        const val OTHER_ACCOUNT_TRANSPARENT = "tsynthetic-other-account"
        val READS = listOf("get", "is", "has", "needs", "can", "should", "peek", "count", "find")

        /** Bare-address shaped and longer than 100 chars, as the screen requires. Synthetic. */
        val PEER_ADDRESS = "u1synthetic" + "peer".repeat(25)
        val SELF_ADDRESS = "u1synthetic" + "self".repeat(25)
        val OWN_UNKNOWN_TO_REGISTRY = "u1synthetic" + "mine".repeat(25)

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))
    }
}
