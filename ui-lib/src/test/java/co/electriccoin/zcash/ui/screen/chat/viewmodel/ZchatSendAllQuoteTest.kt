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
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatZip317ProposalDataSource
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.usecase.zip321Amounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The New-Chat Send All is a price the SDK gave, shown to the zatoshi, and the only amount the send
 * may propose (C-1, C-2, C-8, C-9).
 *
 * A real ZchatComposeVM on the JVM (the RetestGap07 idiom: the main dispatcher is unconfined, so pricing
 * that never suspends lands before the next line). Probes are priced by [ChatZip317ProposalDataSource]
 * over the wallet's Ironwood notes. The owner's post-NU6.3 wallet: 443,002 Ironwood, 139,000 sealed
 * Orchard. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZchatSendAllQuoteTest {
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

    /** An existing Vault contact: everything minus the platform fee and the SDK's fee, to the zatoshi. */
    @Test
    fun an_existing_contact_gets_everything_minus_the_real_fees() =
        runBlocking {
            val state = sendAllTo(prefs(mapOf("getConversationModeOrNull" to ConversationMode.VAULT)))

            assertFalse(state.isZeroAmount)
            assertEquals("0.00428002 ZEC", state.totalAmountDisplay)
            assertEquals("0.00015 ZEC", state.feeDisplay, "the SDK proposal's fee, not a '~' estimate")
            assertTrue(
                state.sendAllAmountDisplay.contains("Recipient gets: 0.00427002 ZEC"),
                state.sendAllAmountDisplay,
            )
            assertFalse(state.sendAllAmountDisplay.contains("handshake"), state.sendAllAmountDisplay)
            // Round 9 R9-1 (REVIEW_7C F11): Available is what can go to others (443,002 Ironwood), not the sealed Orchard too.
            assertEquals(String.format("%.5f ZEC", IRONWOOD / 100_000_000.0), state.availableBalanceDisplay)
        }

    /** C-8: a new contact keeps exactly the consent-dialog quote and the screen says so. */
    @Test
    fun a_new_contact_keeps_the_handshake_quote_and_says_so() =
        runBlocking {
            val state = sendAllTo(prefs(emptyMap()))

            assertEquals("0.00411002 ZEC", state.totalAmountDisplay)
            assertTrue(
                state.sendAllAmountDisplay.contains("Recipient gets: 0.00410002 ZEC"),
                state.sendAllAmountDisplay,
            )
            assertTrue(
                state.sendAllAmountDisplay.contains("Kept for the secure handshake: 0.00017 ZEC"),
                state.sendAllAmountDisplay,
            )
            assertTrue(state.sendAllNote.contains("Kept for the secure handshake: 0.00017 ZEC"), state.sendAllNote)
        }

    @Test
    fun a_contact_whose_key_exchange_is_complete_keeps_nothing() =
        runBlocking {
            val state = sendAllTo(prefs(mapOf("isE2EKeyExchangeComplete" to true)))

            // Round 8 P4 (F6): a completed key exchange means the contact has a chat. New Chat sends it nothing and
            // prices nothing (Send opens the chat), so no handshake reserve is kept either.
            assertTrue(state.opensExistingChat, "a contact with a completed key exchange is not handed to its chat")
            assertTrue(proposalDataSource.probedUris.isEmpty(), "a Send All was priced for a contact that has a chat")
            assertFalse(state.sendAllAmountDisplay.contains("handshake"), state.sendAllAmountDisplay)
        }

    /**
     * Never a 0 fill. 20,000 of Ironwood cannot cover the fees and the handshake reserve; the sealed
     * Orchard is what makes the balance look bigger, so that is the reason shown, with its number.
     */
    @Test
    fun too_little_ironwood_says_why_and_offers_nothing() =
        runBlocking {
            accountDataSource.account.value = account(ironwood = 20_000)
            proposalDataSource.ironwoodNotes = listOf(20_000L)

            val state = sendAllTo(prefs(emptyMap()))

            assertTrue(state.isZeroAmount, "Send stays disabled")
            assertTrue(state.sendAllNote.startsWith("0.00139 ZEC is sealed in Orchard"), state.sendAllNote)
            assertEquals("", state.sendAllAmountDisplay)
            assertTrue(proposalDataSource.probedUris.isEmpty(), "nothing to price")
        }

    /** The price follows the wallet: a balance change re-prices, it never keeps showing the old figure. */
    @Test
    fun a_balance_change_reprices() =
        runBlocking {
            val vm = buildVm(prefs(mapOf("getConversationModeOrNull" to ConversationMode.VAULT)))
            enterSendAll(vm)
            assertEquals("0.00428002 ZEC", vm.ready().totalAmountDisplay)

            proposalDataSource.ironwoodNotes = listOf(300_000L)
            accountDataSource.account.value = account(ironwood = 300_000)

            val repriced =
                withTimeout(SEND_TIMEOUT_MS) {
                    vm.state.first {
                        it is ZchatComposeState.Ready && it.totalAmountDisplay != "0.00428002 ZEC"
                    } as ZchatComposeState.Ready
                }
            assertEquals("0.00285 ZEC", repriced.totalAmountDisplay)
        }

    /**
     * C-9: at the tap the use case proposes the DISPLAYED output 0. When that proposal is not the quoted
     * one (here: its fee differs), nothing is authorised or submitted, the screen does not go to the
     * error state, and it asks for a new tap.
     */
    @Test
    fun a_proposal_that_no_longer_matches_sends_nothing_and_asks_for_a_new_tap() =
        runBlocking {
            val vm =
                buildVm(
                    prefs(
                        mapOf(
                            "getConversationModeOrNull" to ConversationMode.VAULT,
                            "hasAcknowledgedMessageCost" to true,
                            "getOrCreateConversationId" to ("SYNTH001" to true),
                            "getOrCreateConversationIdForMessage" to ("SYNTH001" to true),
                        )
                    )
                )
            enterSendAll(vm)
            val shown = vm.ready()
            assertEquals("0.00428002 ZEC", shown.totalAmountDisplay)

            shown.onSendClick()
            NewChatPriceQuestion.payIfAsked(vm)
            val after =
                withTimeout(SEND_TIMEOUT_MS) {
                    vm.state.first {
                        it is ZchatComposeState.Ready && !it.isSending && it.sendAllNote.contains("Amount updated")
                    } as ZchatComposeState.Ready
                }

            assertEquals(1, proposalDataSource.proposedUris.size, "exactly one real proposal, at the tap")
            assertEquals(
                427_002L,
                zip321Amounts(proposalDataSource.proposedUris.single()).first(),
                "the proposal is for the output 0 that was on screen",
            )
            assertTrue(proposalDataSource.recording.submittedProposals.isEmpty(), "nothing was submitted")
            assertFalse("replace" in navigationRouter.commands, "the chat must not open as if it was sent")
            assertEquals("0.00428002 ZEC", after.totalAmountDisplay, "priced again, shown again")
            assertTrue(after.sendAllNote.contains("tap Send again"), after.sendAllNote)
        }

    // --------------------------------------------------------------- harness

    private suspend fun sendAllTo(zchatPreferences: ZchatPreferences): ZchatComposeState.Ready {
        val vm = buildVm(zchatPreferences)
        enterSendAll(vm)
        return vm.ready()
    }

    private fun enterSendAll(vm: ZchatComposeVM) {
        vm.ready().onRecipientChange(PEER_ADDRESS)
        vm.ready().onMessageChange("hi")
        vm.ready().onAmountSelect(MessageAmount.SEND_ALL)
    }

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
        const val IRONWOOD = 443_002L
        const val ORCHARD = 139_000L
        const val SEND_TIMEOUT_MS = 10_000L

        /** Bare-address shaped and longer than 100 chars, as the screen requires. Synthetic. */
        val PEER_ADDRESS = "u1synthetic" + "peer".repeat(25)
        val SELF_ADDRESS = "u1synthetic" + "self".repeat(25)

        private fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        fun account(ironwood: Long): ZashiAccount =
            runBlocking {
                ZashiAccount(
                    sdkAccount = AccountFixture.new(),
                    unified =
                        UnifiedInfo(
                            WalletAddress.Unified.new("usynthetic-self"),
                            balance(ORCHARD),
                            balance(ironwood),
                        ),
                    sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                    transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                    isSelected = true,
                )
            }
    }
}
