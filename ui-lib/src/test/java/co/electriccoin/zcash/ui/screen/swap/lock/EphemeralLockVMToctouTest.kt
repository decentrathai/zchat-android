package co.electriccoin.zcash.ui.screen.swap.lock

import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitIncreaseEphemeralGapLimitUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeEphemeralAddressRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.common.usecase.syntheticGapLimitProposal
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
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
import org.junit.Assert.assertSame
import org.junit.Before
import kotlin.test.Test

/**
 * ZC-101 (HIGH, money-loss) — the CONFIRM-SCREEN binding for the ephemeral gap-limit lock sheet.
 *
 * The use-case-level test (SubmitIncreaseEphemeralGapLimitUseCaseToctouTest) pins that the use
 * case settles the proposal PASSED to it. This test goes one level up and pins WHAT the lock
 * sheet passes: EphemeralLockVM's `.map` builds the rendered state and must bind the proposal
 * instance THAT render displays into the confirm button's onClick closure. GREEN lock at HEAD —
 * a regression that re-reads the shared `transactionProposal` cell at click time settles the
 * overwriting proposal B instead of the displayed gap-limit proposal A, and fails here. (The
 * flow is currently dormant in the UI, but the mapping is live code and stays pinned.)
 *
 * Real VM + real ObserveProposalUseCase + real ZashiProposalRepositoryImpl + real
 * SubmitIncreaseEphemeralGapLimitUseCase; the recording ProposalDataSource is the settlement
 * seam. Main is an unconfined test dispatcher so viewModelScope work runs deterministically on
 * the test thread.
 *
 * All addresses/memos/keys/amounts are SYNTHETIC placeholders — nothing here parses or validates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EphemeralLockVMToctouTest {

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
        val newVm: () -> EphemeralLockVM,
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
        val navigationRouter = Zc101RecordingNavigationRouter()
        val submitIncreaseEphemeralGapLimit =
            SubmitIncreaseEphemeralGapLimitUseCase(
                navigationRouter = navigationRouter,
                accountDataSource = accountDataSource,
                zashiProposalRepository = zashiRepository,
                biometricRepository = biometrics,
                ephemeralAddressRepository = Zc101FakeEphemeralAddressRepository(),
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
                EphemeralLockVM(
                    observeProposal = observeProposal,
                    navigationRouter = navigationRouter,
                    submitIncreaseEphemeralGapLimit = submitIncreaseEphemeralGapLimit,
                    zashiProposalRepository = zashiRepository,
                    keystoneProposalRepository = keystoneRepository,
                )
            },
        )
    }

    private suspend fun awaitRender(states: ReceiveChannel<EphemeralLockState?>): EphemeralLockState =
        withTimeout(AWAIT_MS) {
            var state: EphemeralLockState? = states.receive()
            while (state == null) {
                state = states.receive()
            }
            state
        }

    /**
     * GREEN lock. The A/B pattern against EphemeralLockVM's `.map` state builder:
     *
     *   1. the gap-limit flow created proposal A (cell := A); the lock sheet renders A;
     *   2. the confirm callback bound into THAT render is captured;
     *   3. an inbound dust auto-ack overwrites the singleton cell with proposal B;
     *   4. the tap from A's render lands and the user authenticates.
     *
     * INVARIANT: settlement receives A — the gap-limit proposal the sheet displayed — regardless
     * of what the shared cell holds by then.
     */
    @Test
    fun `gap-limit confirm submits the proposal its render displayed even after the cell is overwritten`() =
        runBlocking {
            val h = harness()

            val gapLimitA = syntheticGapLimitProposal()
            val dustB = syntheticZip321Proposal("usynthetic-dust-peer", amount = 1_000L, memo = "synthetic-ack")

            // 1. Cell := A; the lock sheet renders it.
            h.zashiRepository.transactionProposal.value = gapLimitA
            val vm = h.newVm()
            val states = Channel<EphemeralLockState?>(Channel.UNLIMITED)
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
            assertSame(
                "the confirm closure must submit the gap-limit proposal instance its render " +
                    "DISPLAYED (A) — re-reading the shared cell at click time would settle the " +
                    "dust auto-ack instead",
                gapLimitA.proposal,
                settled,
            )

            collector.cancel()
        }

    private companion object {
        const val AWAIT_MS = 10_000L
    }
}
