package co.electriccoin.zcash.ui.screen.transactionprogress

import android.content.ContextWrapper
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.configuration.api.ConfigurationProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
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
import co.electriccoin.zcash.ui.common.usecase.newDistinctSdkProposal
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
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
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-417 (fix-all 2.13.0, train T5b; t5b-add SPECS §8): an offline Wallet send never shows the old connection error
 * first.
 *
 * At e0c583fc2 an offline Wallet send returns `GrpcFailure(ids)` at once, while the TEX checker's next pass (which
 * judges every plain send, F5) comes up to a minute later. Until then `chainOutcomeState` has no verdict for the new
 * id, so TransactionProgressVM shows "Connection Issue … will retry in the next few minutes" and only then "Waiting
 * for the Network" (F5 W2 r1, HONOR es). The same for a SentUnconfirmed or a Failure with ids.
 *
 * Wanted: while the process checker runs (`TexSendMonitor.checkerRunning`, set only by `startForProcess`; this class
 * sets it by name), for a Regular or ZIP-321 send not to a TEX address whose result names ids and some unmined id has
 * no verdict in the latest check, the screen stays on the in-progress Sending state, and Back goes to the history
 * for every such result. It ends when the check judges every id (F5's screens) or after `PENDING_MAX_MS` (the VM's
 * `pendingMaxMs`, shortened by name here): then today's screen. A shielding, a TEX send, a result with no ids, or no
 * running checker: today's screen at once.
 *
 * ZC-596 (train T6a): until 5 Oct 2026 case (c) below pinned "Back clears the Send form" for a Failure with ids. That
 * was the defect: the wallet stored the transaction and the SDK submits it again until it expires, so an empty Send
 * form with no warning invites a second payment. Back goes to the history, where the pending send shows.
 *
 * The harness is WalletSendProgressFollowsTheChainTest's: the real TransactionProgressVM over the real use cases, the
 * real TexSendMonitor over the F3 fakes with passes run by hand. Every txid is synthetic and used by this class only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineWalletSendShowsNoOldErrorFirstTest {
    private val vms = mutableListOf<TransactionProgressVM>()
    private val collectors = CoroutineScope(Dispatchers.Default + Job())
    private var defaultPendingMaxMs: Long? = null

    private companion object {
        const val PEER = "usyntheticzc417progresspeer01"
        const val PEER_TEX = "tex1syntheticzc417progresspeer01"
        const val E = 4_000L
        const val TIMEOUT_MS = 10_000L
        const val WATCH_MS = 1_000L
        const val SHORT_PENDING_MS = 1_500L
        const val NO_CLEAR_GRACE_MS = 500L
        const val POLL_MS = 10L
        const val BLOCK_SECONDS = 75L
        val OTHER: String = txid(0x13)
        val UNSEEN_GRPC: String = txid(0x15)
        val THEN_SEEN: String = txid(0x17)
        val UNSEEN_STORED: String = txid(0x19)
        val UNSEEN_UNCONFIRMED: String = txid(0x1B)
        val NEVER_JUDGED: String = txid(0x1D)
        val NOT_RUNNING: String = txid(0x25)
        val SHIELD_UNSEEN: String = txid(0x27)
        val TEX_UNSEEN: String = txid(0x29)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        setCheckerRunning(true)
        defaultPendingMaxMs = pendingMaxMsField()?.getLong(null)
    }

    @After
    fun tearDown() {
        setCheckerRunning(false)
        defaultPendingMaxMs?.let { pendingMaxMsField()?.setLong(null, it) }
        endViewModelsThenResetMain(vms, collectors)
    }

    @Test
    fun `an offline send the checker has not judged shows Sending, never the connection error, and Back goes to the history`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(OTHER, mined = E - 30)), E)
            val harness = harness(sendProposal(), SubmitResult.GrpcFailure(listOf(UNSEEN_GRPC)))

            val state = harness.watch()
            assertTrue(state is SendingTransactionState, "the screen shows ${state.describe()} instead of Sending")
            harness.assertNeverShowed(GrpcFailureTransactionState::class.java, "the connection error")
            assertEquals(res("send_confirmation_sending_title"), state.title())
            state.onBack()
            assertEquals(listOf("backToRoot"), harness.router.commands, "Back did not go to the history")
            assertEquals(0, harness.clears.get(), "Back cleared the Send form of a send that may still go out")
        }

    @Test
    fun `the next pass that sees the send turns Sending into Waiting with the block and the minutes`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(OTHER, mined = E - 30)), E)
            val harness = harness(sendProposal(), SubmitResult.GrpcFailure(listOf(THEN_SEEN)))
            assertTrue(harness.watch() is SendingTransactionState, "the screen shows ${harness.vm.state.value.describe()}")

            checkerPasses(listOf(plain(THEN_SEEN, mined = null)), E - 20)

            val state = harness.awaitState("Waiting for the Network") { it.title() == res("send_confirmation_waiting_title") }
            val minutes = ((E + 10 - (E - 20)) * BLOCK_SECONDS + 59) / 60
            assertEquals(
                StringResource.ByResource(res("send_confirmation_waiting_subtitle").resource, listOf((E + 10).toString(), minutes.toInt())),
                state.text(),
            )
            harness.assertNeverShowed(GrpcFailureTransactionState::class.java, "the connection error")
        }

    @Test
    fun `a stored failure the checker has not judged shows Sending and Back goes to the history, never to an empty form`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(OTHER, mined = E - 30)), E)
            val harness = harness(sendProposal(), SubmitResult.Failure(listOf(UNSEEN_STORED), code = -26, description = "x"))

            val state = harness.watch()
            assertTrue(state is SendingTransactionState, "the screen shows ${state.describe()} instead of Sending")
            harness.assertNeverShowed(FailureTransactionState::class.java, "the stored-failure screen")
            state.onBack()
            delay(NO_CLEAR_GRACE_MS)
            assertEquals(listOf("backToRoot"), harness.router.commands, "Back did not go to the history")
            assertEquals(0, harness.clears.get(), "Back emptied the Send form of a send that may still be mined")
        }

    @Test
    fun `sent but not confirmed the checker has not judged shows Sending and Back goes to the history`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(OTHER, mined = E - 30)), E)
            val harness = harness(sendProposal(), SubmitResult.SentUnconfirmed(listOf(UNSEEN_UNCONFIRMED), "synthetic"))

            val state = harness.watch()
            assertTrue(state is SendingTransactionState, "the screen shows ${state.describe()} instead of Sending")
            harness.assertNeverShowed(SentUnconfirmedTransactionState::class.java, "the not-confirmed screen")
            state.onBack()
            assertEquals(listOf("backToRoot"), harness.router.commands, "Back did not go to the history")
            assertEquals(0, harness.clears.get())
        }

    @Test
    fun `with no verdict by the longest wait the screen falls back to today's screens`() =
        runBlocking<Unit> {
            pendingMaxMsField()?.setLong(null, SHORT_PENDING_MS)
            checkerPasses(listOf(plain(OTHER, mined = E - 30)), E)
            val grpc = harness(sendProposal(), SubmitResult.GrpcFailure(listOf(NEVER_JUDGED)))
            val unconfirmed = harness(sendProposal(), SubmitResult.SentUnconfirmed(listOf(NEVER_JUDGED), "synthetic"))
            val stored = harness(sendProposal(), SubmitResult.Failure(listOf(NEVER_JUDGED), code = -26, description = "x"))

            for (h in listOf(grpc, unconfirmed, stored)) {
                assertTrue(h.firstShown() is SendingTransactionState, "the first screen was ${h.firstShown().describe()}")
            }
            grpc.awaitState("the connection error after the wait") { it is GrpcFailureTransactionState }
            unconfirmed.awaitState("Sent - Not Confirmed after the wait") {
                it.title() == stringRes(R.string.send_confirmation_unconfirmed_title)
            }
            stored.awaitState("Send Not Confirmed after the wait") { it.title() == res("send_confirmation_failure_stored_title") }
        }

    @Test
    fun `control - with no checker running the screens are today's at once`() =
        runBlocking<Unit> {
            setCheckerRunning(false)
            checkerPasses(listOf(plain(OTHER, mined = E - 30)), E)
            val grpc = harness(sendProposal(), SubmitResult.GrpcFailure(listOf(NOT_RUNNING)))
            val stored = harness(sendProposal(), SubmitResult.Failure(listOf(NOT_RUNNING), code = -26, description = "x"))

            assertTrue(grpc.firstShown() is GrpcFailureTransactionState, "the first screen was ${grpc.firstShown().describe()}")
            assertEquals(res("send_confirmation_failure_stored_title"), stored.firstShown().title())
        }

    @Test
    fun `control - a shielding, a TEX send and a result with no ids keep today's screens at once`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(OTHER, mined = E - 30)), E)
            val shielding =
                harness(
                    ShieldTransactionProposal(newDistinctSdkProposal()),
                    SubmitResult.SentUnconfirmed(listOf(SHIELD_UNSEEN), "synthetic"),
                )
            val tex = harness(texProposal(), SubmitResult.GrpcFailure(listOf(TEX_UNSEEN)))
            val noIds = harness(sendProposal(), SubmitResult.GrpcFailure(emptyList()))

            assertEquals(stringRes(R.string.send_confirmation_unconfirmed_title), shielding.firstShown().title())
            assertTrue(tex.firstShown() is GrpcFailureTransactionState, "a TEX send showed ${tex.firstShown().describe()}")
            assertTrue(noIds.firstShown() is GrpcFailureTransactionState, "a send with no ids showed ${noIds.firstShown().describe()}")
        }

    // --------------------------------------------------------------- the new flags, reached by name

    /** `TexSendMonitor.checkerRunning` (a companion property; its backing field is static on the class). */
    private fun setCheckerRunning(value: Boolean) {
        staticField(TexSendMonitor::class.java, "checkerRunning")?.setBoolean(null, value)
    }

    /** `TransactionProgressVM.pendingMaxMs`, the longest wait for a first verdict (PENDING_MAX_MS by default). */
    private fun pendingMaxMsField(): Field? = staticField(TransactionProgressVM::class.java, "pendingMaxMs")

    private fun staticField(
        owner: Class<*>,
        name: String,
    ): Field? =
        try {
            owner.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            null
        }

    // --------------------------------------------------------------- the checker

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

    private fun TransactionProgressState.text(): Any? = propertyOrNull("text")

    private fun TransactionProgressState?.describe(): String = "${this?.javaClass?.simpleName} titled ${this?.title()}"

    private fun TransactionProgressState.propertyOrNull(name: String): Any? =
        javaClass.methods
            .firstOrNull { it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } && it.parameterCount == 0 }
            ?.invoke(this)

    private inner class Harness(
        val vm: TransactionProgressVM,
        val router: Zc101RecordingNavigationRouter,
        val clears: AtomicInteger,
    ) {
        /** Every non-null state the screen showed, in order. */
        val shown = CopyOnWriteArrayList<TransactionProgressState>()

        init {
            collectors.launch { vm.state.collect { if (it != null) shown += it } }
        }

        suspend fun firstShown(): TransactionProgressState {
            withTimeout(TIMEOUT_MS) { while (shown.isEmpty()) delay(POLL_MS) }
            return shown.first()
        }

        /** The screen after [WATCH_MS] (shorter than any wait the VM may hold), once it showed something. */
        suspend fun watch(): TransactionProgressState {
            firstShown()
            delay(WATCH_MS)
            return vm.state.value!!
        }

        fun assertNeverShowed(
            type: Class<out TransactionProgressState>,
            what: String,
        ) {
            assertFalse(shown.any { type.isInstance(it) }, "the screen showed $what first: ${shown.map { it.describe() }}")
        }

        suspend fun awaitState(
            what: String,
            predicate: (TransactionProgressState) -> Boolean,
        ): TransactionProgressState =
            try {
                withTimeout(TIMEOUT_MS) { vm.state.first { it != null && predicate(it) } }!!
            } catch (_: TimeoutCancellationException) {
                fail("the screen never showed $what; it shows ${vm.state.value.describe()}")
            }
    }

    private suspend fun sendProposal(): TransactionProposal = syntheticZip321Proposal(PEER, amount = 1_000L, memo = "synthetic")

    private suspend fun texProposal(): TransactionProposal =
        RegularTransactionProposal(
            destination = WalletAddress.Tex.new(PEER_TEX),
            amount = Zatoshi(1_000L),
            memo = Memo(""),
            proposal = newDistinctSdkProposal(),
        )

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
