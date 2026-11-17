package co.electriccoin.zcash.ui.screen.transactionprogress

import android.content.ContextWrapper
import co.electriccoin.zcash.configuration.api.ConfigurationProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.CancelProposalFlowUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSupportUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveClearSendUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveTransactionSubmitStateUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewTransactionDetailAfterSuccessfulProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewTransactionsAfterSuccessfulProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.newDistinctSdkProposal
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Round 8 D3 (REVIEW_7C F30 = round7c REPORT #17, sweep #28 / O-2): a Wallet Send whose submit ended in
 * `SubmitResult.Failure(txIds = [T])` never goes back to a filled Send form.
 *
 * A Failure that names a transaction id means the SDK stored T (it is in the wallet and may still reach the
 * network). At 68455b60d the failure screen's Back and Close both run `onBackToSendForm` →
 * `CancelProposalFlowUseCase(clearSendForm = false)`, which returns to the Send form with the same payee, amount
 * and memo still filled in, under "There was an error attempting to send coins. Try it again, please." One more
 * tap builds T2 and pays a second time while T may still land (v2.12.4 and upstream do the same).
 *
 * Wanted (option (a) of round7c C9/REVIEW.md): with a stored transaction, neither exit returns to a filled Send form
 * (since ZC-611 both go to the history and ask for no clear; before, they cleared the form and returned to it), and the
 * screen says the send is not confirmed and may still go out
 * ("check your transaction history before you send again"), with View Transaction kept. With no id, nothing was
 * stored, so today's behaviour stays (form kept, "try it again"). A shielding keeps its own wording.
 *
 * Drives the real TransactionProgressVM over the real CancelProposalFlowUseCase and ObserveClearSendUseCase; the
 * proposal repositories are fakes holding the proposal and the submit state. New string ids are looked up by
 * name, so this file compiles before they exist. All ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoredFailureClearsSendFormTest {
    private val vms = mutableListOf<TransactionProgressVM>()
    private val collectors = CoroutineScope(Dispatchers.Default + Job())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        endViewModelsThenResetMain(vms, collectors)
    }

    // ZC-611 (T7b triage 13 Oct): until 14 Oct the two cases below pinned "the exit clears the Send form and returns to
    // it". An emptied form under a send the SDK may still broadcast invites a second payment (owner rule W-10, ZC-596):
    // both exits now go to the history (backToRoot, which also pops the Send entry) and ask for no clear.
    @Test
    fun `Back after a Failure that names a stored transaction goes to the history and never returns to the Send form`() =
        runBlocking {
            val harness = harness(sendProposal(), SubmitResult.Failure(listOf(TXID), code = -26, description = "x"))
            val state = harness.failureState()

            state.onBack()

            harness.assertWentToTheHistoryOnly("Back")
        }

    @Test
    fun `Close after a Failure that names a stored transaction goes to the history and never returns to the Send form`() =
        runBlocking {
            val harness = harness(sendProposal(), SubmitResult.Failure(listOf(TXID), code = 18, description = "x"))
            val state = harness.failureState()

            state.onCloseClick()

            harness.assertWentToTheHistoryOnly("Close")
        }

    @Test
    fun `a Failure that names a stored transaction says it may still be sent, never try again`() =
        runBlocking {
            val harness = harness(sendProposal(), SubmitResult.Failure(listOf(TXID), code = -26, description = "x"))
            val state = harness.failureState()

            assertNotEquals(
                stringRes(R.string.send_confirmation_failure_subtitle),
                state.text,
                "a stored transaction is shown as 'Send failed, try it again'",
            )
            assertEquals(stringRes(stringId("send_confirmation_failure_stored_subtitle")), state.text)
            assertEquals(stringRes(stringId("send_confirmation_failure_stored_title")), state.title)

            val viewTransaction = assertNotNull(state.onViewTransactionClick, "View Transaction is kept")
            viewTransaction()
            assertEquals(listOf("replaceAll"), harness.router.commands, "View Transaction opens the stored transaction")
        }

    /** Control, green before and after: nothing was stored, so the form and the "try it again" text stay. */
    @Test
    fun `control - a Failure with no stored transaction keeps the form and the try-again text`() =
        runBlocking {
            val harness = harness(sendProposal(), SubmitResult.Failure(emptyList(), code = 0, description = "x"))
            val state = harness.failureState()

            assertEquals(stringRes(R.string.send_confirmation_failure_title), state.title)
            assertEquals(stringRes(R.string.send_confirmation_failure_subtitle), state.text)
            assertNull(state.onViewTransactionClick)

            state.onBack()
            state.onCloseClick()
            harness.assertNoClearRequested()
            assertEquals(listOf("backTo", "backTo"), harness.router.commands)
        }

    /** Control, green before and after: a shielding keeps its own wording and exit. */
    @Test
    fun `control - a shielding Failure with an id keeps the shielding text and the old exit`() =
        runBlocking {
            val harness =
                harness(
                    ShieldTransactionProposal(newDistinctSdkProposal()),
                    SubmitResult.Failure(listOf(TXID), code = -26, description = "x"),
                )
            val state = harness.failureState()

            assertEquals(stringRes(R.string.send_confirmation_failure_title_transparent), state.title)
            assertEquals(stringRes(R.string.send_confirmation_failure_subtitle_transparent), state.text)

            state.onBack()
            harness.assertNoClearRequested()
        }

    // --------------------------------------------------------------- harness

    private fun stringId(name: String): Int =
        try {
            R.string::class.java.getField(name).getInt(null)
        } catch (e: NoSuchFieldException) {
            fail("R.string.$name does not exist: $e")
        }

    private inner class Harness(
        val vm: TransactionProgressVM,
        val router: Zc101RecordingNavigationRouter,
        val clears: AtomicInteger,
    ) {
        suspend fun failureState(): FailureTransactionState {
            val state = withTimeout(TIMEOUT_MS) { vm.state.first { it != null && it !is SendingTransactionState } }
            return state as? FailureTransactionState
                ?: fail("expected the Failure screen, got ${state?.javaClass?.simpleName}")
        }

        /** ZC-611: [exit] went to the history only, and, a moment later, nothing asked the Send form to clear. */
        suspend fun assertWentToTheHistoryOnly(exit: String) {
            try {
                withTimeout(CLEAR_WAIT_MS) { while (router.commands.isEmpty()) delay(POLL_MS) }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                fail("$exit after a Failure with a stored transaction went nowhere")
            }
            delay(NO_CLEAR_GRACE_MS)
            assertEquals(
                listOf("backToRoot"),
                router.commands,
                "$exit after a Failure with a stored transaction did not go to the history",
            )
            assertEquals(0, clears.get(), "$exit emptied the Send form of a send the SDK may still broadcast")
        }

        suspend fun assertNoClearRequested() {
            withTimeout(TIMEOUT_MS) { while ("backTo" !in router.commands) delay(POLL_MS) }
            delay(NO_CLEAR_GRACE_MS)
            assertEquals(0, clears.get(), "the Send form was asked to clear")
        }
    }

    private suspend fun sendProposal(): TransactionProposal =
        syntheticZip321Proposal(PEER, amount = 5_000_000L, memo = "synthetic")

    private suspend fun harness(
        proposal: TransactionProposal,
        result: SubmitResult,
    ): Harness {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val keystone = Zc101FakeKeystoneProposalRepository()
        val zashi = holdingRepository(proposal, SubmitProposalState.Result(result))
        val router = Zc101RecordingNavigationRouter()
        val context = ContextWrapper(null)
        val observeClearSend = ObserveClearSendUseCase()
        val clears = AtomicInteger()
        collectors.launch { observeClearSend().collect { clears.incrementAndGet() } }
        val vm =
            TransactionProgressVM(
                observeTransactionProposal = ObserveProposalUseCase(keystone, zashi, accounts),
                observeTransactionSubmitState = ObserveTransactionSubmitStateUseCase(keystone, zashi, accounts),
                getTransactionProposal = GetProposalUseCase(keystone, zashi, accounts),
                copyToClipboardUseCase = CopyToClipboardUseCase(context),
                sendEmailUseCase =
                    SendEmailUseCase(context, GetSupportUseCase(context, unused<ConfigurationProvider>())),
                cancelKeystoneProposalFlow =
                    CancelProposalFlowUseCase(
                        zashiProposalRepository = zashi,
                        keystoneProposalRepository = keystone,
                        navigationRouter = router,
                        observeClearSend = observeClearSend,
                        accountDataSource = accounts,
                        swapRepository = unused<SwapRepository>(),
                    ),
                viewTransactionsAfterSuccessfulProposal =
                    ViewTransactionsAfterSuccessfulProposalUseCase(keystone, zashi, router),
                viewTransactionDetailAfterSuccessfulProposal =
                    ViewTransactionDetailAfterSuccessfulProposalUseCase(keystone, zashi, router),
            )
        vms += vm
        return Harness(vm, router, clears)
    }

    /**
     * The Zashi repository as the progress screen sees it: the proposal and its settled submit state. The suspend
     * `getTransactionProposal()` (it takes a Continuation) returns the proposal itself, as CancelProposalFlowUseCase
     * reads it; the property getter of the same JVM name returns the flow.
     */
    private fun holdingRepository(
        proposal: TransactionProposal,
        submitState: SubmitProposalState,
    ): ZashiProposalRepository {
        val proposalFlow = MutableStateFlow<TransactionProposal?>(proposal)
        val stateFlow = MutableStateFlow<SubmitProposalState?>(submitState)
        return Proxy.newProxyInstance(
            ZashiProposalRepository::class.java.classLoader,
            arrayOf(ZashiProposalRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getTransactionProposal" -> if (args?.lastOrNull() is Continuation<*>) proposal else proposalFlow
                "getSubmitState" -> stateFlow
                "clear" -> Unit
                "toString" -> "HoldingZashiProposalRepository"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> throw UnsupportedOperationException("unexpected repository call ${method.name}")
            }
        } as ZashiProposalRepository
    }

    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { p, method, args ->
            when (method.name) {
                "toString" -> "Unused${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> throw UnsupportedOperationException("unexpected call ${method.name}")
            }
        } as T

    private companion object {
        const val PEER = "usyntheticstoredfailurepeer1"
        const val TXID = "synthetic-stored-txid-T"
        const val TIMEOUT_MS = 10_000L
        const val CLEAR_WAIT_MS = 3_000L
        const val NO_CLEAR_GRACE_MS = 500L
        const val POLL_MS = 10L
    }
}
