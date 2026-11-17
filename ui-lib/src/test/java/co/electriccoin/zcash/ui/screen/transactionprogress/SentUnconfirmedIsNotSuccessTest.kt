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
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R2-6 (a), p2-cfef5773a20d: a send whose outcome is [SubmitResult.SentUnconfirmed] must not be shown as a
 * success.
 *
 * SentUnconfirmed means the settlement call threw AFTER the SDK may have broadcast: the coins may be on their
 * way, or not. At 566b866d2 TransactionProgressVM maps it to [SuccessfulTransactionState] — the same type the
 * screen paints with the success gradient and the check mark — only with other words. The user sees a success
 * screen for a payment nobody has confirmed.
 *
 * What it must be instead: its own warning state (painted like the connection-issue screen), titled
 * "Sent — Not Confirmed", with a way to look at the transaction, and every exit (Back, Close) routed to the
 * transaction history — never back to a filled Send form, which invites paying twice. Shielding through this
 * screen (Keystone) gets the same state.
 *
 * Drives the real TransactionProgressVM over the real observe/view use cases; the proposal repositories are
 * fakes holding the proposal and the submit state. The state is read by property name, so this file compiles
 * against the code before the fix. All ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SentUnconfirmedIsNotSuccessTest {
    private val vms = mutableListOf<TransactionProgressVM>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        endViewModelsThenResetMain(vms)
    }

    @Test
    fun `a send that is sent but not confirmed is a warning, not the success screen`() =
        runBlocking {
            val harness = harness(sendProposal(), SubmitResult.SentUnconfirmed(listOf(TXID), "synthetic refresh failure"))
            val state = harness.state()

            assertNotSuccessOrFailure(state)
            assertEquals(stringRes(R.string.send_confirmation_unconfirmed_title), state.property<StringResource>("title"))
            assertEquals(stringRes(R.string.send_confirmation_unconfirmed_subtitle), state.property<StringResource>("text"))
        }

    @Test
    fun `sent but not confirmed keeps View Transaction and every exit goes to the history, never the Send form`() =
        runBlocking {
            val harness = harness(sendProposal(), SubmitResult.SentUnconfirmed(listOf(TXID), "synthetic refresh failure"))
            val state = harness.state()
            assertNotSuccessOrFailure(state)

            val viewTransaction = assertNotNull(state.property<(() -> Unit)?>("onViewTransactionClick"), "View Transaction")
            viewTransaction()
            assertEquals(listOf("replaceAll"), harness.router.commands, "View Transaction opens the transaction")

            harness.router.commands.clear()
            state.property<() -> Unit>("onCloseClick")()
            state.onBack()
            assertEquals(listOf("backToRoot", "backToRoot"), harness.router.commands)
            assertFalse("back" in harness.router.commands, "an exit returned to the filled Send form")
        }

    /** The common case: the settlement threw before any id was returned. The history is still one tap away. */
    @Test
    fun `sent but not confirmed without an id still offers the transactions and never the Send form`() =
        runBlocking {
            val harness = harness(sendProposal(), SubmitResult.SentUnconfirmed(emptyList(), "synthetic refresh failure"))
            val state = harness.state()
            assertNotSuccessOrFailure(state)

            val viewTransaction = assertNotNull(state.property<(() -> Unit)?>("onViewTransactionClick"), "View Transaction")
            viewTransaction()
            state.onBack()
            assertTrue(harness.router.commands.isNotEmpty())
            assertFalse("back" in harness.router.commands, "an exit returned to the filled Send form")
        }

    @Test
    fun `a shielding that is sent but not confirmed shows the same state`() =
        runBlocking {
            val harness =
                harness(
                    ShieldTransactionProposal(newDistinctSdkProposal()),
                    SubmitResult.SentUnconfirmed(emptyList(), "synthetic refresh failure"),
                )
            val state = harness.state()

            assertNotSuccessOrFailure(state)
            assertEquals(stringRes(R.string.send_confirmation_unconfirmed_title), state.property<StringResource>("title"))
        }

    /** Control, green before and after: a confirmed send is still the success screen. */
    @Test
    fun `control - a confirmed send is still a success`() =
        runBlocking {
            val harness = harness(sendProposal(), SubmitResult.Success(listOf(TXID)))
            assertTrue(harness.state() is SuccessfulTransactionState)
        }

    // --------------------------------------------------------------- harness

    private fun assertNotSuccessOrFailure(state: TransactionProgressState) {
        assertFalse(
            state is SuccessfulTransactionState,
            "SentUnconfirmed is rendered as the success screen (${state::class.simpleName})",
        )
        assertFalse(state is FailureTransactionState, "SentUnconfirmed is rendered as 'Send Failed — try again'")
        assertFalse(state is SendingTransactionState, "SentUnconfirmed left the screen on 'Sending'")
        assertFalse(state is MultipleFailuresTransactionState)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> TransactionProgressState.property(name: String): T {
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        val method =
            javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
                ?: throw AssertionError("${javaClass.simpleName} has no '$name'")
        return method.invoke(this) as T
    }

    private class Harness(
        val vm: TransactionProgressVM,
        val router: Zc101RecordingNavigationRouter,
    ) {
        suspend fun state(): TransactionProgressState =
            withTimeout(TIMEOUT_MS) { vm.state.first { it != null && it !is SendingTransactionState } }!!
    }

    private suspend fun sendProposal(): TransactionProposal = syntheticZip321Proposal(PEER, amount = 5_000_000L, memo = "synthetic")

    private suspend fun harness(
        proposal: TransactionProposal,
        result: SubmitResult,
    ): Harness {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val keystone = Zc101FakeKeystoneProposalRepository()
        val zashi = holdingRepository(proposal, SubmitProposalState.Result(result))
        val router = Zc101RecordingNavigationRouter()
        val context = ContextWrapper(null)
        val vm =
            TransactionProgressVM(
                observeTransactionProposal = ObserveProposalUseCase(keystone, zashi, accounts),
                observeTransactionSubmitState = ObserveTransactionSubmitStateUseCase(keystone, zashi, accounts),
                getTransactionProposal = GetProposalUseCase(keystone, zashi, accounts),
                copyToClipboardUseCase = CopyToClipboardUseCase(context),
                sendEmailUseCase = SendEmailUseCase(context, GetSupportUseCase(context, unused<ConfigurationProvider>())),
                cancelKeystoneProposalFlow =
                    CancelProposalFlowUseCase(
                        zashiProposalRepository = zashi,
                        keystoneProposalRepository = keystone,
                        navigationRouter = router,
                        observeClearSend = ObserveClearSendUseCase(),
                        accountDataSource = accounts,
                        swapRepository = unused<SwapRepository>(),
                    ),
                viewTransactionsAfterSuccessfulProposal =
                    ViewTransactionsAfterSuccessfulProposalUseCase(keystone, zashi, router),
                viewTransactionDetailAfterSuccessfulProposal =
                    ViewTransactionDetailAfterSuccessfulProposalUseCase(keystone, zashi, router),
            )
        vms += vm
        return Harness(vm, router)
    }

    /** The Zashi repository as the progress screen sees it: the proposal and its settled submit state. */
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
                "getTransactionProposal" -> proposalFlow
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
        const val PEER = "usyntheticprogresspeer00001"
        const val TXID = "synthetic-unconfirmed-txid"
        const val TIMEOUT_MS = 10_000L
    }
}
