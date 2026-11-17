package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.HandshakeSpendDecision
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.model.decideHandshakeSpend
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatZip317ProposalDataSource
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.Test

/**
 * RED pins for the New-Chat "Send All" budget (ZchatComposeVM).
 *
 * C-1. The owner's phone after NU6.3 held 443,002 zatoshi of spendable Ironwood and 139,000 of sealed
 * Orchard. ZIP 258 lets an Orchard Action pay only its own address, so the only value that can reach
 * a peer is the Ironwood. `observeBalance` budgets Send All from `spendableShieldedBalance`, which adds
 * the Orchard back in, so a one-chunk Send All asks for 582,002 − 1,000 − 15,000 = 566,002 on output 0:
 * 123,000 more than the wallet can pay a peer. The SDK then refuses, and the refusal reached the owner
 * as "please wait, your previous transaction is still confirming" — a wait that never ends.
 *
 * C-8 (owner decision, 2026-09-10). A New-Chat send to a peer whose key ZChat does not hold yet starts
 * in Tunnel, and opening that chat fires the KEX/ZBOOT handshake, an on-chain send of its own. Send All
 * spent everything, so that handshake failed and re-armed. Send All to such a peer must hold back the
 * handshake cost, using the SAME quote the handshake-consent dialog shows (`decideHandshakeSpend`), and
 * must show the user the amount it kept.
 *
 * Both tests bound what LEAVES the wallet rather than an exact figure. The network fee is whatever the
 * SDK proposal says — ZIP 317 charges at least 2 × 5,000 — so the checks are
 * `totalAmountDisplay + minimum fee (+ handshake quote) <= Ironwood available`. They hold for every
 * correct fee the proposal can come back with and fail for any budget that includes Orchard.
 *
 * Harness: the RetestGap07 idiom — a REAL ZchatComposeVM on the JVM with the main dispatcher set to
 * UnconfinedTestDispatcher so init runs synchronously to Ready, and hand-written fakes for the
 * constructor seams. The address lookup parks forever (the amount display does not need it), and
 * ZchatPreferences is a dynamic Proxy that answers "nothing known about this peer" to every query
 * except the ones a scenario sets. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZchatSendAllIronwoodBaseTest {
    private val vms = mutableListOf<ZchatComposeVM>()

    /**
     * The proposal seam handed to the use case. A var so that a ZIP-317-shaped proposal fake can be
     * installed without rewriting the harness.
     */
    private var proposalDataSource: ProposalDataSource = Zc101RecordingProposalDataSource()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // The ZIP-317-shaped proposal fake: ZIP 321 probes are priced like the SDK prices them over the
        // owner's one Ironwood note (the sealed Orchard is never selected for a peer).
        proposalDataSource = ChatZip317ProposalDataSource(ironwoodNotes = listOf(IRONWOOD_AVAILABLE))
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

    // ----------------------------------------------------------------- tests

    /**
     * C-1: a peer the user already talks to in Vault (no handshake follows). Everything Send All puts
     * on the outputs, plus the smallest fee ZIP 317 allows, must fit inside the Ironwood balance.
     *
     * Today: totalAmountDisplay = 0.00567 ZEC (566,002 + 1,000 platform fee), budgeted from
     * Orchard + Ironwood.
     */
    @Test
    fun sendAllNeverExceedsIronwoodMinusFees() =
        runBlocking {
            val vm = buildVm(prefs(mapOf("getConversationModeOrNull" to ConversationMode.VAULT)))

            vm.readyState().onRecipientChange(PEER_ADDRESS)
            vm.readyState().onMessageChange(ONE_CHUNK_MESSAGE)
            vm.readyState().onAmountSelect(MessageAmount.SEND_ALL)
            val state = vm.readyState()

            val leavesWallet = zatoshiIn(state.totalAmountDisplay)
            assertTrue(
                "Send All puts ${state.totalAmountDisplay} on the outputs; with the minimum network fee " +
                    "($MINIMUM_NETWORK_FEE zat) that is ${leavesWallet + MINIMUM_NETWORK_FEE} zat, more than the " +
                    "$IRONWOOD_AVAILABLE zat of Ironwood that can pay a peer (the $ORCHARD_AVAILABLE zat of sealed " +
                    "Orchard cannot)",
                leavesWallet + MINIMUM_NETWORK_FEE <= IRONWOOD_AVAILABLE,
            )
            assertFalse(
                "443,002 zat of spendable Ironwood always covers a Send All; it must offer an amount, not 0",
                state.isZeroAmount,
            )
        }

    /**
     * C-8: a brand-new peer — not in the contact book, no stored mode (so the New-Chat default, Tunnel),
     * no NOSTR or E2E key. The handshake this chat fires next costs the consent-dialog quote, so Send All
     * must leave that much in Ironwood, and must say how much it kept.
     *
     * Today: totalAmountDisplay = 0.00567 ZEC and nothing is kept or shown.
     */
    @Test
    fun sendAllToNewContactKeepsTheHandshakeQuote() =
        runBlocking {
            val handshakeQuote =
                (decideHandshakeSpend(consentGranted = false, pendingPeers = 1) as HandshakeSpendDecision.NeedsConsent)
                    .estimatedCost
                    .value
            val vm = buildVm(prefs(emptyMap()))

            vm.readyState().onRecipientChange(PEER_ADDRESS)
            vm.readyState().onMessageChange(ONE_CHUNK_MESSAGE)
            vm.readyState().onAmountSelect(MessageAmount.SEND_ALL)
            val state = vm.readyState()

            val leavesWallet = zatoshiIn(state.totalAmountDisplay)
            assertTrue(
                "Send All to a new contact puts ${state.totalAmountDisplay} on the outputs; with the minimum " +
                    "network fee ($MINIMUM_NETWORK_FEE zat) and the $handshakeQuote zat handshake quote that needs " +
                    "${leavesWallet + MINIMUM_NETWORK_FEE + handshakeQuote} zat, more than the $IRONWOOD_AVAILABLE " +
                    "zat of Ironwood — the KEX/ZBOOT this chat sends next would have nothing left to spend",
                leavesWallet + MINIMUM_NETWORK_FEE + handshakeQuote <= IRONWOOD_AVAILABLE,
            )
            assertFalse(
                "a new contact still gets a Send All amount (everything minus fees and the handshake quote), not 0",
                state.isZeroAmount,
            )
            // The screen must SHOW the amount it kept. Every figure the VM hands the screen is a string
            // field of the Ready state, and a data class prints all of them in toString().
            assertTrue(
                "the New-Chat screen must show the $handshakeQuote zat kept for the secure handshake; " +
                    "amounts shown: ${zatoshiAmountsIn(state.toString())}",
                zatoshiAmountsIn(state.toString()).any { kotlin.math.abs(it - handshakeQuote) < DISPLAY_ROUNDING_ZAT },
            )
        }

    // --------------------------------------------------------------- harness

    /** Real ZchatComposeVM over the owner's post-NU6.3 balances; init runs to Ready on the unconfined main. */
    private suspend fun buildVm(zchatPreferences: ZchatPreferences): ZchatComposeVM {
        val accountDataSource = Zc101FakeAccountDataSource(ownerAccount())
        val synchronizerProvider = HangingSynchronizerProvider()
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
                navigationRouter = Zc101RecordingNavigationRouter(),
                prefillZchat = PrefillZchatUseCase(),
                transactionRepository = EmptyTransactionRepository(),
                zchatPreferences = zchatPreferences,
                validateAddress = ValidateAddressUseCase(synchronizerProvider),
            )
        vms += vm
        return vm
    }

    private fun ZchatComposeVM.readyState(): ZchatComposeState.Ready =
        state.value as? ZchatComposeState.Ready
            ?: error("VM state was ${state.value}, expected Ready")

    /** The first "<number> ZEC" in [display], in zatoshi. */
    private fun zatoshiIn(display: String): Long =
        zatoshiAmountsIn(display).firstOrNull()
            ?: throw AssertionError("no ZEC amount in '$display'")

    /** Every "<number> ZEC" in [text], in zatoshi (either decimal separator). */
    private fun zatoshiAmountsIn(text: String): List<Long> =
        ZEC_AMOUNT
            .findAll(text)
            .map { BigDecimal(it.groupValues[1].replace(',', '.')).movePointRight(8).toLong() }
            .toList()

    // ------------------------------------------------------------------ fakes

    /**
     * ZchatPreferences as a dynamic Proxy (~200 methods). [answers] maps a method name to its return
     * value; every other query gets the "nothing stored for this peer" answer for its return type.
     */
    private fun prefs(answers: Map<String, Any?>): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when {
                method.name == "toString" -> "ZchatSendAllFakePreferences"
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

    private class HangingSynchronizerProvider : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        /** Parks loadInitialState's address lookup and the async checksum check — neither is needed. */
        override suspend fun getSynchronizer(): Synchronizer = awaitCancellation()

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

        override fun updateContactName(address: String, newName: String) = Unit

        override fun clearAll() = Unit
    }

    private companion object {
        const val IRONWOOD_AVAILABLE = 443_002L
        const val ORCHARD_AVAILABLE = 139_000L

        /** ZIP 317 never charges less than its grace actions × the marginal fee. */
        const val MINIMUM_NETWORK_FEE = Zip317.MARGINAL_FEE_ZATOSHI * Zip317.GRACE_ACTIONS

        /** The VM prints amounts to 5 decimals (1,000 zat); half of that absorbs its rounding. */
        const val DISPLAY_ROUNDING_ZAT = 500L

        /** Passes the VM's fast check (u1 prefix, longer than 100 chars). Synthetic, never a real address. */
        val PEER_ADDRESS = "u1synthetic" + "peer".repeat(25)

        const val ONE_CHUNK_MESSAGE = "hi"

        val ZEC_AMOUNT = Regex("""(\d+(?:[.,]\d+)?)\s*ZEC""")

        private fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        /** The owner's phone after NU6.3: Ironwood 443,002 and sealed Orchard 139,000, both confirmed. */
        suspend fun ownerAccount() =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified =
                    UnifiedInfo(
                        WalletAddress.Unified.new("usynthetic-self"),
                        balance(ORCHARD_AVAILABLE),
                        balance(IRONWOOD_AVAILABLE),
                    ),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
