package co.electriccoin.zcash.ui.screen.balances.spendable

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSourceImpl
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.ShortfallReason
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetTransactionsUseCase
import co.electriccoin.zcash.ui.common.usecase.ShieldFundsUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeApplicationStateProvider
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeMetadataRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsContext
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsVM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The balance figures this fix added to explanatory copy honour the hide-balances setting (review
 * follow-up). The Spendable sheet's rows already hid their values, but its message, which now names the
 * confirming and sealed-Orchard amounts, and the shortfall sheet's description did not. Each now comes
 * with a hidden variant the view shows while balances are hidden: every balance figure is the
 * placeholder, and what a send needs (not a balance) stays.
 *
 * Figures are checked in the resource tree ([Zatoshi] arguments) and as the plain text the confirming
 * explanation writes. Owner state: 443,002 zatoshi of Ironwood, 139,000 of sealed Orchard. All addresses
 * are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenBalanceReasonCopyTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun the_spendable_sheet_hides_the_confirming_and_orchard_figures() {
        val state = spendableState(ironwood = WalletBalance(Zatoshi(0), Zatoshi(443_002), Zatoshi(0)))

        val shown = state.message.toString()
        val hidden = state.hiddenMessage.toString()
        assertTrue(shown.contains("0.00443002") && shown.contains("0.00139"), shown)
        assertFalse(hidden.contains("0.00443002") || hidden.contains("0.00139"), hidden)
    }

    @Test
    fun the_spendable_sheet_hides_the_sealed_orchard_figure() {
        val state = spendableState(ironwood = WalletBalance(Zatoshi(443_002), Zatoshi(0), Zatoshi(0)))

        assertTrue(state.message.toString().contains(figure(139_000)), state.message.toString())
        assertFalse(state.hiddenMessage.toString().contains(figure(139_000)), state.hiddenMessage.toString())
    }

    @Test
    fun the_shortfall_sheet_hides_the_balance_figures_but_keeps_what_the_send_needs() {
        val shortfalls =
            listOf(
                ShortfallReason.OrchardStranded(
                    orchard = Zatoshi(139_000),
                    have = Zatoshi(443_002),
                    need = Zatoshi(587_002),
                ),
                ShortfallReason.NotEnough(have = Zatoshi(443_002), need = Zatoshi(587_002), pending = Zatoshi(1_000)),
                ShortfallReason.StillConfirming(pending = Zatoshi(443_002), need = Zatoshi(587_002)),
            )

        shortfalls.forEach { shortfall ->
            val state =
                checkNotNull(
                    InsufficientFundsVM(
                        InsufficientFundsArgs.of(InsufficientFundsContext.PAYMENT, shortfall),
                        Zc101RecordingNavigationRouter(),
                    ).state.value
                )
            val shown = state.description.toString()
            val hidden = state.hiddenDescription.toString()

            assertTrue(shown.contains(figure(443_002)), "$shortfall: $shown")
            BALANCE_FIGURES.forEach { assertFalse(hidden.contains(figure(it)), "$shortfall: $hidden") }
            assertTrue(hidden.contains(figure(587_002)), "what the send needs is not a balance: $hidden")
        }
    }

    // --------------------------------------------------------------------------------- harness

    private class NoTransactions : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(null)

        override suspend fun getMemos(transaction: Transaction): List<String> = emptyList()

        override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)

        override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)

        override suspend fun getTransactions(): List<Transaction> = emptyList()

        override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()
    }

    private fun spendableState(ironwood: WalletBalance): SpendableBalanceState {
        val accountDataSource = Zc101FakeAccountDataSource(runBlocking { account(ironwood) })
        val router = Zc101RecordingNavigationRouter()
        val vm =
            SpendableBalanceVM(
                accountDataSource = accountDataSource,
                getTransactions = GetTransactionsUseCase(NoTransactions(), Zc101FakeMetadataRepository()),
                navigationRouter = router,
                shieldFunds =
                    ShieldFundsUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository =
                            ZashiProposalRepositoryImpl(
                                accountDataSource = accountDataSource,
                                proposalDataSource = Zc101RecordingProposalDataSource(),
                                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                            ),
                        navigationRouter = router,
                        accountDataSource = accountDataSource,
                        navigateToError = NavigateToErrorUseCase(router),
                        messageAvailabilityDataSource =
                            MessageAvailabilityDataSourceImpl(Zc101FakeApplicationStateProvider()),
                    ),
            )
        return checkNotNull(vm.state.value)
    }

    private companion object {
        /** Every balance figure the cases above use; 587,002 is what the send needs. */
        val BALANCE_FIGURES = listOf(443_002L, 139_000L, 1_000L)

        /** How a [Zatoshi] argument appears in a resource tree. */
        fun figure(zatoshi: Long) = "Zatoshi(value=$zatoshi)"

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun account(ironwood: WalletBalance) =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(139_000), ironwood),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
