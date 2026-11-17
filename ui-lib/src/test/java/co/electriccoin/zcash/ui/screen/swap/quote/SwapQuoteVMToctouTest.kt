package co.electriccoin.zcash.ui.screen.swap.quote

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.CancelSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.CancelSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.ProcessSwapTransactionUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeApplicationStateProvider
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeMetadataRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapQuote
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.common.usecase.describeSettled
import co.electriccoin.zcash.ui.common.usecase.syntheticSwapProposal
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.design.util.stringRes
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import kotlin.test.Test
import kotlin.time.Clock

/**
 * ZC-101 (HIGH, money-loss) — the CONFIRM-SCREEN binding for the swap quote sheet.
 *
 * The use-case-level tests (SubmitProposalUseCaseToctouTest) pin that `SubmitProposalUseCase`
 * settles the proposal PASSED to it. This test goes one level up and pins WHAT the swap confirm
 * sheet passes: the proposal instance ITS RENDER DISPLAYED — never the raw shared-cell value.
 *
 * The seam is SwapQuoteVM.createState, which renders `proposal as? SwapTransactionProposal` (the
 * DISPLAY) but binds the confirm closure from the SAME render. The two must agree:
 *
 *   - if the cell holds a swap proposal, Confirm submits exactly that instance;
 *   - if the cell holds a NON-swap proposal (an inbound dust auto-ack is a ZIP-321 proposal,
 *     never a SwapTransactionProposal), the sheet displays NO proposal — so Confirm must be a
 *     fail-closed no-op, not a submission of something the user never saw.
 *
 * The cross-type test is RED while createState binds the RAW uncast `proposal` into the closure:
 * a dust auto-ack that lands mid-sheet becomes submittable by a Confirm tap on a sheet that
 * displays no swap proposal at all.
 *
 * Real VM + real ObserveProposalUseCase + real ZashiProposalRepositoryImpl + real
 * SubmitProposalUseCase; the recording ProposalDataSource is the settlement seam. Main is an
 * unconfined test dispatcher so viewModelScope work runs deterministically on the test thread.
 *
 * All addresses/memos/keys/amounts are SYNTHETIC placeholders — nothing here parses or validates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapQuoteVMToctouTest {

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
        val swapRepository: Zc101FakeSwapRepository,
        val navigationRouter: Zc101RecordingNavigationRouter,
        /** Construct the VM only AFTER the quote and the proposal cell are staged. */
        val newVm: () -> SwapQuoteVM,
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
            swapRepository = swapRepository,
            navigationRouter = navigationRouter,
            newVm = {
                SwapQuoteVM(
                    observeProposal = observeProposal,
                    applicationStateProvider = Zc101FakeApplicationStateProvider(),
                    swapRepository = swapRepository,
                    cancelSwapQuote =
                        CancelSwapQuoteUseCase(
                            swapRepository = swapRepository,
                            zashiProposalRepository = zashiRepository,
                            keystoneProposalRepository = keystoneRepository,
                            navigationRouter = navigationRouter,
                        ),
                    cancelSwap =
                        CancelSwapUseCase(
                            swapRepository = swapRepository,
                            navigationRouter = navigationRouter,
                        ),
                    swapQuoteSuccessMapper = SwapQuoteVMMapper(),
                    submitProposal = submitProposal,
                    navigationRouter = navigationRouter,
                )
            },
        )
    }

    /**
     * The fake quote reports a ZERO ZEC fee when the render was built WITHOUT a swap proposal
     * (the display cast yielded null) — the deterministic marker of the post-ack render.
     */
    private fun SwapQuoteState.Success.showsNoSwapProposal(): Boolean =
        items.any {
            it.description == stringRes(R.string.swap_quote_total_fees) && it.title == stringRes(Zatoshi(0))
        }

    private suspend fun awaitRender(
        states: ReceiveChannel<SwapQuoteState?>,
        predicate: (SwapQuoteState.Success) -> Boolean = { true },
    ): SwapQuoteState.Success =
        withTimeout(AWAIT_MS) {
            var state: SwapQuoteState? = states.receive()
            while (state !is SwapQuoteState.Success || !predicate(state)) {
                state = states.receive()
            }
            state
        }

    /**
     * RED at HEAD — the live blocker. The deterministic interleaving:
     *
     *   1. the swap flow staged its quote and created swap proposal A (cell := A); the sheet opens
     *      and renders A;
     *   2. mid-sheet, an inbound dust auto-ack overwrites the singleton cell with its own ZIP-321
     *      proposal B — which is NOT a SwapTransactionProposal;
     *   3. the sheet re-renders: the display cast (`proposal as? SwapTransactionProposal`) yields
     *      null, so this render shows NO swap proposal the user could review;
     *   4. the user taps Confirm on the sheet as currently rendered.
     *
     * INVARIANT (fail closed): that render displayed nothing to authorize, so NOTHING may reach
     * the submit seam. At HEAD createState binds the RAW uncast cell value into the confirm
     * closure, so the tap submits the dust auto-ack the sheet never showed: RED.
     */
    @Test
    fun `a dust auto-ack that lands mid-sheet must not be submittable from the swap confirm sheet`() =
        runBlocking {
            val h = harness()

            val quote = Zc101FakeSwapQuote(timestamp = Clock.System.now())
            val swapA = syntheticSwapProposal(quote)
            val dustB = syntheticZip321Proposal("usynthetic-dust-peer", amount = 1_000L, memo = "synthetic-ack")

            // 1. Quote staged, cell := swap A; the sheet opens and renders A.
            h.swapRepository.quoteState.value = SwapQuoteData.Success(quote)
            h.zashiRepository.transactionProposal.value = swapA
            val vm = h.newVm()

            val states = Channel<SwapQuoteState?>(Channel.UNLIMITED)
            val collector = launch { vm.state.collect { states.trySend(it) } }
            awaitRender(states) { !it.showsNoSwapProposal() }

            // 2. Mid-sheet: the inbound dust auto-ack overwrites the singleton cell.
            h.zashiRepository.transactionProposal.value = dustB

            // 3. The sheet re-renders WITHOUT a displayable swap proposal.
            val renderedAfterAck = awaitRender(states) { it.showsNoSwapProposal() }

            // 4. The user taps Confirm on what is currently rendered.
            renderedAfterAck.primaryButton.onClick()

            // If anything WAS submitted, drive it to settlement so the assertion observes it
            // instead of racing it (fail-closed code never even reaches the biometric prompt).
            val prompted = withTimeoutOrNull(FAIL_CLOSED_GRACE_MS) { h.biometrics.promptShown.await() } != null
            if (prompted) {
                h.biometrics.approve()
                withTimeoutOrNull(AWAIT_MS) { h.dataSource.firstSettlement.await() }
            }

            assertEquals(
                "fail closed: this render displayed NO swap proposal, so Confirm must submit " +
                    "nothing — binding the RAW cell value into the confirm closure lets a " +
                    "mid-sheet dust auto-ack be broadcast by a tap on a sheet that never " +
                    "showed it",
                emptyList<String>(),
                h.dataSource.submittedProposals.map { describeSettled(it, swapA, dustB) },
            )
            assertFalse(
                "fail closed also means no biometric prompt for a proposal that was never displayed",
                prompted,
            )

            collector.cancel()
        }

    /**
     * GREEN lock. The confirm closure must carry the swap proposal instance ITS OWN render
     * displayed — even after the shared cell has moved on to a newer swap proposal. A regression
     * that re-reads the cell (or the latest state) at click time settles B and fails here.
     */
    @Test
    fun `confirm submits the swap proposal instance the sheet rendered - not the current cell value`() =
        runBlocking {
            val h = harness()

            val quote = Zc101FakeSwapQuote(timestamp = Clock.System.now())
            val swapA = syntheticSwapProposal(quote)
            val swapB = syntheticSwapProposal(quote)

            h.swapRepository.quoteState.value = SwapQuoteData.Success(quote)
            h.zashiRepository.transactionProposal.value = swapA
            val vm = h.newVm()

            val states = Channel<SwapQuoteState?>(Channel.UNLIMITED)
            val collector = launch { vm.state.collect { states.trySend(it) } }

            // Capture the confirm callback bound into swap A's render.
            val renderedA = awaitRender(states) { !it.showsNoSwapProposal() }
            val confirmDisplayedA = renderedA.primaryButton.onClick

            // The shared cell moves on to a NEWER swap proposal before the tap lands. (The
            // overwrite is effective the moment the cell is set — no re-render needs to be
            // awaited for a cell re-read regression to pick up B.)
            h.zashiRepository.transactionProposal.value = swapB

            // The tap from A's render lands; the user authenticates.
            confirmDisplayedA()
            withTimeout(AWAIT_MS) { h.biometrics.promptShown.await() }
            h.biometrics.approve()
            val settled = withTimeout(AWAIT_MS) { h.dataSource.firstSettlement.await() }

            assertEquals("exactly one proposal must reach settlement", 1, h.dataSource.submittedProposals.size)
            assertSame(
                "the confirm closure must submit the proposal instance ITS render displayed (A) — " +
                    "re-reading the shared cell at click time would settle B instead",
                swapA.proposal,
                settled,
            )

            collector.cancel()
        }

    private companion object {
        const val AWAIT_MS = 10_000L

        /** Bounded observation window for "the biometric prompt must NEVER appear". */
        const val FAIL_CLOSED_GRACE_MS = 1_500L
    }
}
