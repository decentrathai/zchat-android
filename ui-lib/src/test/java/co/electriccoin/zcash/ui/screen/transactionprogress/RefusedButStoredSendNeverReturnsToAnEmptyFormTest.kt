package co.electriccoin.zcash.ui.screen.transactionprogress

import android.content.ContextWrapper
import co.electriccoin.zcash.configuration.api.ConfigurationProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.tex.FakeTexTor
import co.electriccoin.zcash.ui.common.tex.FakeTexWallet
import co.electriccoin.zcash.ui.common.tex.TexApi
import co.electriccoin.zcash.ui.common.tex.TexSendMonitor
import co.electriccoin.zcash.ui.common.tex.txid
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
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.design.util.StringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-596 and ZC-597 (fix-all 2.13.0, train T6a): a Wallet send the node refused but the wallet stored never leaves the
 * user on an empty Send form while it can still be mined.
 *
 * `SubmitResult.Failure` WITH transaction ids means the wallet created and stored the transaction. The SDK submits a
 * stored transaction again on every sync until it expires, so it can still be mined. At dcbb7693e the progress screen
 * gives such a result the exit "clear the Send form and go back to it" on two screens that say nothing was refused:
 * - "Sending…" while the checker has not judged the new id (up to 90 s; ZC-417, ZC-596): Back, the only exit;
 * - "Waiting for the Network … Until then, do not send it again" (F5, ZC-597): Close and Back.
 * An empty form with no warning reads as "cancelled"; the user enters the payment again and both can be mined.
 *
 * Wanted: while a send can still be mined every exit of the progress screen goes to the history (`backToRoot`, which
 * also drops the Send entry), and nothing asks the Send form to clear. A send PROVEN never mined (the checker's Not
 * Sent verdict: nothing was paid) keeps today's exit to an emptied Send form.
 *
 * The harness is OfflineWalletSendShowsNoOldErrorFirstTest's: the real TransactionProgressVM over the real use cases,
 * the real TexSendMonitor over the F3 fakes with passes run by hand. Every txid is synthetic and used by this class
 * only. Nothing here builds, quotes or submits a payment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefusedButStoredSendNeverReturnsToAnEmptyFormTest {
    private val vms = mutableListOf<TransactionProgressVM>()
    private val collectors = CoroutineScope(Dispatchers.Default + Job())

    private companion object {
        const val PEER = "usyntheticzc596progresspeer01"
        const val E = 4_000L
        const val TIMEOUT_MS = 10_000L
        const val WATCH_MS = 1_000L
        const val NO_CLEAR_GRACE_MS = 500L
        const val POLL_MS = 10L
        val OTHER: String = txid(0x3A)
        val UNJUDGED: String = txid(0x3C)
        val WAITING: String = txid(0x3E)
        val DEAD: String = txid(0x4A)
        val UNCHECKED: String = txid(0x4C)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        setCheckerRunning(true)
    }

    @After
    fun tearDown() {
        setCheckerRunning(false)
        endViewModelsThenResetMain(vms, collectors)
    }

    @Test
    fun `awaiting the first verdict - Back on Sending goes to the history and never empties the Send form`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(OTHER, mined = E - 30)), E)
            val harness = harness(sendProposal(), refusedButStored(UNJUDGED))

            harness.firstShown()
            delay(WATCH_MS)
            val state = harness.vm.state.value!!
            assertTrue(state is SendingTransactionState, "the screen shows ${state.describe()} instead of Sending")
            state.onBack()

            harness.assertWentToTheHistoryOnly(exits = 1)
        }

    @Test
    fun `Waiting for the Network - Close and Back go to the history and never empty the Send form`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(WAITING, mined = null)), E - 20)
            val harness = harness(sendProposal(), refusedButStored(WAITING))

            val state = harness.awaitState("Waiting for the Network") { it.title() == res("send_confirmation_waiting_title") }
            state.property<() -> Unit>("onCloseClick")()
            state.onBack()

            harness.assertWentToTheHistoryOnly(exits = 2)
        }

    /**
     * ZC-611 (T7b triage 13 Oct): the "Send Not Confirmed" screen, which a stored Failure shows when no checker judges
     * it (none running, or a check that does not know the id), still had the exit "clear the Send form and go back to
     * it" at 828ed7c0a, although the SDK may still broadcast the stored transaction.
     */
    @Test
    fun `Send Not Confirmed - Close and Back go to the history and never empty the Send form`() =
        runBlocking<Unit> {
            setCheckerRunning(false)
            val harness = harness(sendProposal(), refusedButStored(UNCHECKED))

            val state =
                harness.awaitState("Send Not Confirmed") { it.title() == res("send_confirmation_failure_stored_title") }
            state.property<() -> Unit>("onCloseClick")()
            state.onBack()

            harness.assertWentToTheHistoryOnly(exits = 2)
        }

    /** Control, green before and after: nothing can be mined any more, so today's exit (ZC-400 rules the rest) stays. */
    @Test
    fun `control - proven never mined says Not Sent and its exits still return to an emptied Send form`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(DEAD, mined = null)), E + 10, E + 11)
            val harness = harness(sendProposal(), refusedButStored(DEAD))

            val state = harness.awaitState("Not Sent") { it.title() == res("send_confirmation_not_sent_title") }
            state.onBack()
            harness.awaitClearRequested("Back", 1)
            state.property<() -> Unit>("onCloseClick")()
            harness.awaitClearRequested("Close", 2)
            assertTrue("backTo" in harness.router.commands, "the exit no longer returns to the Send screen")
            assertTrue("backToRoot" !in harness.router.commands, "an exit of a send that was never paid went to the history")
        }

    private fun refusedButStored(txId: String) = SubmitResult.Failure(listOf(txId), code = -26, description = "x")

    // --------------------------------------------------------------- the checker

    /** `TexSendMonitor.checkerRunning` (a companion property; its backing field is static on the class). */
    private fun setCheckerRunning(value: Boolean) {
        TexSendMonitor::class.java
            .getDeclaredField("checkerRunning")
            .apply { isAccessible = true }
            .setBoolean(null, value)
    }

    private fun plain(
        txId: String,
        mined: Long?,
    ): Any =
        TexApi.facts(
            txId = txId,
            mined = mined,
            expiry = E,
            outputs = listOf(TexApi.output(PEER, isOwn = false, kind = "OTHER")),
        )

    /** A checker pass over [transactions] at each of [tips]; the check it publishes is the process's latest. */
    private suspend fun checkerPasses(
        transactions: List<Any>,
        vararg tips: Long,
    ) {
        val wallet = FakeTexWallet().apply { this.transactions = transactions }
        val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true })
        for (tip in tips) {
            wallet.tip = tip
            TexApi.pass(monitor)
        }
    }

    // --------------------------------------------------------------- harness

    private fun res(name: String): StringResource.ByResource =
        try {
            StringResource.ByResource(R.string::class.java.getField(name).getInt(null), emptyList())
        } catch (e: NoSuchFieldException) {
            fail("R.string.$name does not exist: $e")
        }

    private fun TransactionProgressState.title(): Any? = propertyOrNull("title")

    private fun TransactionProgressState?.describe(): String = "${this?.javaClass?.simpleName} titled ${this?.title()}"

    private fun TransactionProgressState.propertyOrNull(name: String): Any? =
        javaClass.methods
            .firstOrNull { it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } && it.parameterCount == 0 }
            ?.invoke(this)

    @Suppress("UNCHECKED_CAST")
    private fun <T> TransactionProgressState.property(name: String): T =
        propertyOrNull(name) as T? ?: throw AssertionError("${javaClass.simpleName} has no '$name'")

    private inner class Harness(
        val vm: TransactionProgressVM,
        val router: Zc101RecordingNavigationRouter,
        val clears: AtomicInteger,
    ) {
        init {
            collectors.launch { vm.state.collect { } }
        }

        suspend fun firstShown(): TransactionProgressState =
            withTimeout(TIMEOUT_MS) { vm.state.first { it != null } }!!

        suspend fun awaitState(
            what: String,
            predicate: (TransactionProgressState) -> Boolean,
        ): TransactionProgressState =
            try {
                withTimeout(TIMEOUT_MS) { vm.state.first { it != null && predicate(it) } }!!
            } catch (_: TimeoutCancellationException) {
                fail("the screen never showed $what; it shows ${vm.state.value.describe()}")
            }

        /** After [exits] taps: each went to the history, and, a moment later, nothing asked the Send form to clear. */
        suspend fun assertWentToTheHistoryOnly(exits: Int) {
            delay(NO_CLEAR_GRACE_MS)
            assertEquals(
                List(exits) { "backToRoot" },
                router.commands,
                "an exit of a send that may still be mined did not go to the history",
            )
            assertEquals(0, clears.get(), "an exit emptied the Send form of a send that may still be mined")
        }

        suspend fun awaitClearRequested(
            exit: String,
            count: Int,
        ) {
            try {
                withTimeout(TIMEOUT_MS) { while (clears.get() < count) delay(POLL_MS) }
            } catch (_: TimeoutCancellationException) {
                fail("$exit after a send proven never mined left the Send form filled (router: ${router.commands})")
            }
        }
    }

    private suspend fun sendProposal(): TransactionProposal = syntheticZip321Proposal(PEER, amount = 1_000L, memo = "synthetic")

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
                sendEmailUseCase = SendEmailUseCase(context, GetSupportUseCase(context, unused<ConfigurationProvider>())),
                cancelKeystoneProposalFlow =
                    CancelProposalFlowUseCase(
                        zashiProposalRepository = zashi,
                        keystoneProposalRepository = keystone,
                        navigationRouter = router,
                        observeClearSend = observeClearSend,
                        accountDataSource = accounts,
                        swapRepository = unused<SwapRepository>(),
                    ),
                viewTransactionsAfterSuccessfulProposal = ViewTransactionsAfterSuccessfulProposalUseCase(keystone, zashi, router),
                viewTransactionDetailAfterSuccessfulProposal =
                    ViewTransactionDetailAfterSuccessfulProposalUseCase(keystone, zashi, router),
            )
        vms += vm
        return Harness(vm, router, clears)
    }

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
}
