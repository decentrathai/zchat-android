package co.electriccoin.zcash.ui.screen.reviewtransaction

import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
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
import co.electriccoin.zcash.ui.common.usecase.ZC101_LABEL_INTENDED_A
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
import co.electriccoin.zcash.ui.common.usecase.describeSettled
import co.electriccoin.zcash.ui.common.usecase.syntheticRegularSendProposal
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import kotlin.test.Test

/**
 * ZC-101 (HIGH, money-loss) — the CONFIRM-SCREEN binding for the send review screen, both render
 * paths: `createState` (regular send) and `createZip321State` (payment request).
 *
 * The use-case-level tests (SubmitProposalUseCaseToctouTest) pin that `SubmitProposalUseCase`
 * settles the proposal PASSED to it. These tests go one level up and pin WHAT the review screen
 * passes: the proposal instance ITS RENDER DISPLAYED, captured in the confirm button's onClick
 * closure at render time. They are GREEN locks at HEAD — a regression that re-reads the shared
 * `transactionProposal` cell at click time (or anywhere before the submit seam) settles the
 * overwriting proposal B instead of the displayed proposal A, and fails here. The use-case tests
 * alone CANNOT catch that revert: they always receive an explicit proposal argument.
 *
 * Real VM + real use cases + real ZashiProposalRepositoryImpl; the recording ProposalDataSource is
 * the settlement seam. Main is an unconfined test dispatcher so viewModelScope work runs
 * deterministically on the test thread.
 *
 * All addresses/memos/keys/amounts are SYNTHETIC placeholders — nothing here parses or validates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewTransactionVMToctouTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Harness(
        val dataSource: Zc101RecordingProposalDataSource,
        val zashiRepository: ZashiProposalRepositoryImpl,
        val biometrics: Zc101SuspendingBiometricRepository,
        /** Construct the VM only AFTER the proposal cell is staged. */
        val newVm: () -> ReviewTransactionVM,
    )

    private suspend fun harness(): Harness {
        val account = syntheticZashiAccount()
        val accountDataSource = Zc101FakeAccountDataSource(account)
        val dataSource = Zc101RecordingProposalDataSource()
        val zashiRepository =
            ZashiProposalRepositoryImpl(
                accountDataSource = accountDataSource,
                proposalDataSource = dataSource,
                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
            )
        val keystoneRepository = Zc101FakeKeystoneProposalRepository()
        val biometrics = Zc101SuspendingBiometricRepository()
        val metadataRepository = Zc101FakeMetadataRepository()
        val swapRepository = Zc101FakeSwapRepository()
        val navigationRouter = Zc101RecordingNavigationRouter()
        val submitProposal =
            SubmitProposalUseCase(
                navigationRouter = navigationRouter,
                accountDataSource = accountDataSource,
                zashiProposalRepository = zashiRepository,
                keystoneProposalRepository = keystoneRepository,
                biometricRepository = biometrics,
                swapRepository = swapRepository,
                metadataRepository = metadataRepository,
                processSwapTransaction =
                    ProcessSwapTransactionUseCase(
                        metadataRepository = metadataRepository,
                        swapDataSource = Zc101FakeSwapDataSource(),
                    ),
                prefillSend = PrefillSendUseCase(),
            )
        val observeProposal =
            ObserveProposalUseCase(
                keystoneProposalRepository = keystoneRepository,
                zashiProposalRepository = zashiRepository,
                accountDataSource = accountDataSource,
            )
        return Harness(
            dataSource = dataSource,
            zashiRepository = zashiRepository,
            biometrics = biometrics,
            newVm = {
                ReviewTransactionVM(
                    getWalletAccounts = GetWalletAccountsUseCase(accountDataSource),
                    observeContactByAddress = ObserveContactByAddressUseCase(Zc101FakeAddressBookRepository()),
                    observeSelectedWalletAccount = ObserveSelectedWalletAccountUseCase(accountDataSource),
                    observeProposal = observeProposal,
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
                    submitProposal = submitProposal,
                )
            },
        )
    }

    private suspend fun awaitRender(states: ReceiveChannel<ReviewTransactionState?>): ReviewTransactionState =
        withTimeout(AWAIT_MS) {
            var state: ReviewTransactionState? = states.receive()
            while (state == null) {
                state = states.receive()
            }
            state
        }

    /**
     * The shared A/B pattern for both render paths:
     *
     *   1. the send flow created proposal A (cell := A); the review screen renders A;
     *   2. the confirm callback bound into THAT render is captured;
     *   3. an inbound dust auto-ack overwrites the singleton cell with proposal B;
     *   4. the tap from A's render lands and the user authenticates.
     *
     * INVARIANT: settlement receives A — the proposal the user was LOOKING AT when they confirmed —
     * regardless of what the shared cell holds by then.
     */
    private suspend fun CoroutineScope.lockConfirmBinding(displayedA: SendTransactionProposal) {
        val h = harness()
        val dustB = syntheticZip321Proposal("usynthetic-dust-peer", amount = 1_000L, memo = "synthetic-ack")

        // 1. Cell := A; the review screen renders it.
        h.zashiRepository.transactionProposal.value = displayedA
        val vm = h.newVm()
        val states = Channel<ReviewTransactionState?>(Channel.UNLIMITED)
        val collector = launch { vm.state.collect { states.trySend(it) } }
        val rendered = awaitRender(states)

        // 2. Capture the confirm callback bound into THIS render.
        val confirmDisplayedA = rendered.primaryButton.onClick

        // 3. The dust auto-ack overwrites the singleton cell before the tap lands. (The
        //    overwrite is effective the moment the cell is set — a cell re-read regression
        //    picks up B whether or not a re-render is awaited.)
        h.zashiRepository.transactionProposal.value = dustB

        // 4. The tap from A's render lands; the user authenticates.
        confirmDisplayedA()
        withTimeout(AWAIT_MS) { h.biometrics.promptShown.await() }
        h.biometrics.approve()
        val settled = withTimeout(AWAIT_MS) { h.dataSource.firstSettlement.await() }

        assertEquals("exactly one proposal must reach settlement", 1, h.dataSource.submittedProposals.size)
        assertEquals(
            "the confirm closure must submit the proposal instance its render DISPLAYED — " +
                "re-reading the shared cell at click (or post-biometric) time settles the " +
                "dust auto-ack while the user believes they authorized their own payment",
            ZC101_LABEL_INTENDED_A,
            describeSettled(settled, displayedA, dustB),
        )

        collector.cancel()
    }

    /** GREEN lock — the `createState` (regular send) render path. */
    @Test
    fun `regular send - Confirm submits the proposal its render displayed even after the cell is overwritten`() =
        runBlocking {
            lockConfirmBinding(
                syntheticRegularSendProposal(
                    destination = "usynthetic-payment-peer",
                    amount = 5_000_000L,
                    memo = "synthetic-payment",
                )
            )
        }

    /** GREEN lock — the `createZip321State` (payment request) render path. */
    @Test
    fun `payment request - Confirm submits the proposal its render displayed even after the cell is overwritten`() =
        runBlocking {
            lockConfirmBinding(
                syntheticZip321Proposal(
                    destination = "usynthetic-payment-peer",
                    amount = 5_000_000L,
                    memo = "synthetic-payment",
                )
            )
        }

    private companion object {
        const val AWAIT_MS = 10_000L
    }
}
