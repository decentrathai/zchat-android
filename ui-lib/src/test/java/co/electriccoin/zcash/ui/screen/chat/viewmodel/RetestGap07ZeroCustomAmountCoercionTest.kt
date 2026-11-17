package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.common.model.SynchronizerError
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
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import java.lang.reflect.Proxy
import kotlin.test.Test

/**
 * RED regression pin for #bug-zero-send — ZchatComposeVM.getEffectiveAmountZatoshi()'s else branch
 * (ZchatComposeVM.kt ~line 307):
 *
 *     raw.coerceAtLeast(CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT.value)
 *
 * A 0-value on-chain per-output amount is REJECTED by org.zecdev.zip321 (AmountTooSmall(value=0)) and
 * used to leak a raw exception to the UI. Any NON-free-OPEN, non-SEND_ALL send whose per-output amount
 * resolves to 0 (a "0" / "" / "0.0" custom amount) MUST be coerced up to the dust minimum AT THE SOURCE
 * so the displayed cost and the actual charge agree and no 0-value output is ever proposed.
 *
 * MUTATION that reddens the primary test: drop the `.coerceAtLeast(...)` (return `raw`). A "0" custom
 * amount then surfaces as 0 — state.isZeroAmount == true and the cost strings become "0 ZEC" — instead
 * of the coerced dust minimum.
 *
 * Fixture idiom mirrors GroupReconcileIdentityTest: a REAL ZchatComposeVM on the JVM, main dispatcher =
 * UnconfinedTestDispatcher so init runs synchronously to a Ready state, hand-written fakes for the
 * constructor seams (the money-path deps are never invoked — this test only drives the pure amount
 * computation through the public onAmountSelect/onCustomAmountChange callbacks and reads the resulting
 * ZchatComposeState.Ready). ZchatPreferences (~200 methods) is a dynamic Proxy that throws on any call,
 * proving the coercion path never touches persisted state. The synthetic account carries a ZERO
 * spendable balance, which is exactly what the SEND_ALL exclusion companion needs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetestGap07ZeroCustomAmountCoercionTest {

    private val vms = mutableListOf<ZchatComposeVM>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // Kill the forever-suspended loadInitialState (getSynchronizer parks) + the scanned-address
        // collector so the unconfined scope doesn't leak between tests.
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

    @Test
    fun `custom 0, blank and 0_0 amounts coerce up to the dust minimum, never a 0-value on-chain output`() =
        runBlocking {
            val vm = buildVm()

            // Enter Custom-amount mode, then drive the three inputs that all parse to 0 zatoshi.
            vm.readyState().onAmountSelect(MessageAmount.CUSTOM)

            listOf("0", "", "0.0").forEach { rawInput ->
                vm.readyState().onCustomAmountChange(rawInput)
                val state = vm.readyState()

                // PRIMARY RED-ON-REVERT ANCHOR: with the coerceAtLeast(...) in place the effective
                // per-output amount is the dust minimum (1000 zat), so isZeroAmount is false. Reverting
                // the coerce returns raw == 0 and this flips to true.
                assertFalse(
                    "custom '$rawInput' must coerce to the dust minimum, not surface a 0 amount (isZeroAmount)",
                    state.isZeroAmount
                )
                // The cost display is derived from the coerced amount; a 0-value send would render
                // "0 ZEC" (formatZatoshi's zero branch). Locale-independent: the non-zero branch never
                // produces the literal "0 ZEC".
                assertNotEquals(
                    "custom '$rawInput' messageCost must reflect the dust charge, not 0 ZEC",
                    "0 ZEC",
                    state.messageCost
                )
                assertNotEquals(
                    "custom '$rawInput' totalAmountDisplay must reflect the dust charge, not 0 ZEC",
                    "0 ZEC",
                    state.totalAmountDisplay
                )
            }
        }

    @Test
    fun `SEND_ALL exclusion preserved - a tiny-balance send all stays 0 (fails insufficient), never padded`() =
        runBlocking {
            // Companion guard: the coerce must NOT apply to SEND_ALL. The synthetic account has a ZERO
            // spendable balance, so "Send All" resolves to 0 and MUST stay 0 (so it fails cleanly as
            // InsufficientFunds) rather than being padded above the wallet's balance. This reddens if a
            // future "fix" coerces every branch instead of only the non-SEND_ALL / non-free-OPEN one.
            val vm = buildVm()

            vm.readyState().onAmountSelect(MessageAmount.SEND_ALL)
            val state = vm.readyState()

            assertTrue(
                "a zero-balance Send All must stay 0 (not padded to dust)",
                state.isZeroAmount
            )
            assertEquals(
                "a zero-balance Send All renders as 0 ZEC, surfacing the empty wallet",
                "0 ZEC",
                state.messageCost
            )
        }

    // --------------------------------------------------------------- harness

    /** Real ZchatComposeVM whose init runs to a Ready state on the unconfined main dispatcher. */
    private suspend fun buildVm(): ZchatComposeVM {
        val accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val synchronizerProvider = HangingSynchronizerProvider()
        val getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accountDataSource)
        val vm =
            ZchatComposeVM(
                contactBook = FakeContactBook(),
                getSelectedWalletAccount = getSelectedWalletAccount,
                getDefaultUnifiedAddress =
                    GetDefaultUnifiedAddressUseCase(synchronizerProvider, getSelectedWalletAccount),
                createChunkedMessageProposal =
                    CreateChunkedMessageProposalUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository =
                            ZashiProposalRepositoryImpl(
                                accountDataSource = accountDataSource,
                                proposalDataSource = Zc101RecordingProposalDataSource(),
                                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                            ),
                        accountDataSource = accountDataSource,
                        navigationRouter = Zc101RecordingNavigationRouter(),
                        biometricRepository = Zc101SuspendingBiometricRepository(),
                    ),
                navigationRouter = Zc101RecordingNavigationRouter(),
                prefillZchat = PrefillZchatUseCase(),
                transactionRepository = FakeTransactionRepository(),
                zchatPreferences = throwingPrefs(),
                validateAddress = ValidateAddressUseCase(synchronizerProvider),
            )
        vms += vm
        return vm
    }

    private fun ZchatComposeVM.readyState(): ZchatComposeState.Ready =
        state.value as? ZchatComposeState.Ready
            ?: error("VM state was ${state.value}, expected Ready")

    // ------------------------------------------------------------------ fakes

    /**
     * The coercion path never reads persisted state, so every ZchatPreferences call is a fixture bug —
     * throw loudly. (~200 methods; a dynamic Proxy answers only Object identity methods.)
     */
    private fun throwingPrefs(): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "RetestGap07FakeZchatPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException(
                    "unexpected ZchatPreferences call in the zero-custom-amount path: ${method.name}"
                )
            }
        } as ZchatPreferences

    private class HangingSynchronizerProvider : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        /** Parks loadInitialState's getDefaultUnifiedAddress forever — the address is irrelevant here. */
        override suspend fun getSynchronizer(): Synchronizer = awaitCancellation()

        override fun resetSynchronizer() = Unit
    }

    private class FakeTransactionRepository : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(null)

        override suspend fun getMemos(transaction: Transaction): List<String> = emptyList()

        override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)

        override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)

        override suspend fun getTransactions(): List<Transaction> = emptyList()

        override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()
    }

    private class FakeContactBook : ContactBook {
        override fun addContact(contact: Contact) = Unit

        override fun removeContact(address: String) = Unit

        override fun getContact(address: String): Contact? = null

        override fun getAllContacts(): List<Contact> = emptyList()

        override fun hasContact(address: String): Boolean = false

        override fun updateContactName(address: String, newName: String) = Unit

        override fun clearAll() = Unit
    }
}
