package co.electriccoin.zcash.ui.screen.reviewtransaction

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.CancelProposalFlowUseCase
import co.electriccoin.zcash.ui.common.usecase.GetExchangeRateUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveClearSendUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveContactByAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.ProcessSwapTransactionUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAddressBookRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeMetadataRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.common.usecase.newDistinctSdkProposal
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-056 (T7b triage 13 Oct, SMALL work order; money-path: a claim about how a payment travels): the Review screen of
 * a send to an exchange (TEX, `tex1…`) address shows the card "This payment is shielded".
 *
 * At 00f1a349b `ReviewTransactionVM` sets `isShieldedRecipient = destination !is WalletAddress.Transparent` in both
 * of its states (a regular send and a ZIP-321 request), so a TEX destination counts as shielded. A TEX send is not:
 * its legs are transparent (the funds go through an ephemeral transparent address to the exchange's transparent one).
 *
 * Wanted: the card is shown for a shielded destination only, so neither a transparent nor a TEX address gets it. Only
 * the card goes: the amount and fee rows of the screen stay as they are.
 *
 * The real ReviewTransactionVM over the harness of ReviewTransactionVMToctouTest. Every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TexReviewMakesNoShieldedClaimTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a regular send to a TEX address makes no shielded claim`() =
        runBlocking {
            val state = render(regular(WalletAddress.Tex.new(PEER_TEX)))

            assertFalse("the Review of a send to a TEX address says the payment is shielded", state.isShieldedRecipient)
        }

    @Test
    fun `a payment request to a TEX address makes no shielded claim`() =
        runBlocking {
            val state = render(zip321(WalletAddress.Tex.new(PEER_TEX)))

            assertFalse("the Review of a request to a TEX address says the payment is shielded", state.isShieldedRecipient)
        }

    @Test
    fun `guard - only the card goes, the rows of a TEX Review are those of a shielded send`() =
        runBlocking {
            val tex = render(regular(WalletAddress.Tex.new(PEER_TEX)))
            val unified = render(regular(WalletAddress.Unified.new(PEER_UNIFIED)))

            assertEquals(
                "a TEX Review lost or gained a row",
                unified.items.map { it.javaClass.simpleName },
                tex.items.map { it.javaClass.simpleName },
            )
        }

    @Test
    fun `control - a shielded destination keeps the card and a transparent one has none`() =
        runBlocking {
            val unified = render(regular(WalletAddress.Unified.new(PEER_UNIFIED)))
            val unifiedRequest = render(zip321(WalletAddress.Unified.new(PEER_UNIFIED)))

            assertTrue("a unified address lost the card", unified.isShieldedRecipient)
            assertTrue("a request to a unified address lost the card", unifiedRequest.isShieldedRecipient)
            assertFalse(
                "a transparent address got the card",
                render(regular(WalletAddress.Transparent.new(PEER_TRANSPARENT))).isShieldedRecipient,
            )
        }

    // --------------------------------------------------------------- harness

    private suspend fun regular(destination: WalletAddress): SendTransactionProposal =
        RegularTransactionProposal(
            destination = destination,
            amount = Zatoshi(AMOUNT),
            memo = Memo(""),
            proposal = newDistinctSdkProposal(),
        )

    private suspend fun zip321(destination: WalletAddress): SendTransactionProposal =
        Zip321TransactionProposal(
            destination = destination,
            amount = Zatoshi(AMOUNT),
            memo = Memo(""),
            proposal = newDistinctSdkProposal(),
        )

    /** The first state the Review screen renders for [proposal]. */
    private suspend fun render(proposal: SendTransactionProposal): ReviewTransactionState {
        val account = syntheticZashiAccount()
        val accountDataSource = Zc101FakeAccountDataSource(account)
        val zashiRepository =
            ZashiProposalRepositoryImpl(
                accountDataSource = accountDataSource,
                proposalDataSource = Zc101RecordingProposalDataSource(),
                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
            )
        val keystoneRepository = Zc101FakeKeystoneProposalRepository()
        val metadataRepository = Zc101FakeMetadataRepository()
        val swapRepository = Zc101FakeSwapRepository()
        val navigationRouter = Zc101RecordingNavigationRouter()
        zashiRepository.transactionProposal.value = proposal
        val vm =
            ReviewTransactionVM(
                getWalletAccounts = GetWalletAccountsUseCase(accountDataSource),
                observeContactByAddress = ObserveContactByAddressUseCase(Zc101FakeAddressBookRepository()),
                observeSelectedWalletAccount = ObserveSelectedWalletAccountUseCase(accountDataSource),
                observeProposal =
                    ObserveProposalUseCase(
                        keystoneProposalRepository = keystoneRepository,
                        zashiProposalRepository = zashiRepository,
                        accountDataSource = accountDataSource,
                    ),
                cancelProposalFlow =
                    CancelProposalFlowUseCase(
                        zashiProposalRepository = zashiRepository,
                        keystoneProposalRepository = keystoneRepository,
                        navigationRouter = navigationRouter,
                        observeClearSend = ObserveClearSendUseCase(),
                        accountDataSource = accountDataSource,
                        swapRepository = swapRepository,
                    ),
                getExchangeRate = GetExchangeRateUseCase(Zc101FakeExchangeRateRepository()),
                navigationRouter = navigationRouter,
                submitProposal =
                    SubmitProposalUseCase(
                        navigationRouter = navigationRouter,
                        accountDataSource = accountDataSource,
                        zashiProposalRepository = zashiRepository,
                        keystoneProposalRepository = keystoneRepository,
                        biometricRepository = Zc101SuspendingBiometricRepository(),
                        swapRepository = swapRepository,
                        metadataRepository = metadataRepository,
                        processSwapTransaction =
                            ProcessSwapTransactionUseCase(
                                metadataRepository = metadataRepository,
                                swapDataSource = Zc101FakeSwapDataSource(),
                            ),
                        prefillSend = PrefillSendUseCase(),
                    ),
            )
        return withTimeout(AWAIT_MS) { vm.state.first { it != null } }!!
    }

    private companion object {
        const val AWAIT_MS = 10_000L
        const val AMOUNT = 5_000_000L
        const val PEER_TEX = "tex1syntheticzc056reviewpeer0001"
        const val PEER_TRANSPARENT = "t1syntheticzc056reviewpeer0002"
        const val PEER_UNIFIED = "usyntheticzc056reviewpeer0003"
    }
}
