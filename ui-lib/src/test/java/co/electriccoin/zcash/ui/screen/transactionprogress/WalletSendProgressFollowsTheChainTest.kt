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
import co.electriccoin.zcash.ui.common.tex.FakeTexTor
import co.electriccoin.zcash.ui.common.tex.FakeTexWallet
import co.electriccoin.zcash.ui.common.tex.TexApi
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
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 W3; harm 5 of §1.1): the Wallet Send progress screen follows the chain.
 *
 * At 3df251d7f "Sent — Not Confirmed" (SentUnconfirmed), "Connection Issue" (GrpcFailure) and "Send Not Confirmed"
 * (Failure with a stored id) never change while the screen is open: the SDK keeps rebroadcasting, the payment is mined
 * or expires, and the user must find the truth in the history.
 *
 * Wanted: the screen reads the TEX checker's published check (TexSendMonitor's process-wide `latestCheck`, filled by
 * every pass; no new constructor parameter), only for a Regular or ZIP-321 proposal that is not to a TEX address, with
 * a result that names ids. All mined: the success screen. All proven never mined (F4's rule: two passes at a rising tip,
 * 10 blocks past the expiry): "Not Sent", nothing was paid. Otherwise: "Waiting for the Network" with the block and the
 * minutes. Only "Not Sent" keeps the result's own exit (a Failure with ids proven never mined clears the Send form);
 * while a send may still be mined every exit goes to the history (ZC-596, ZC-597), and so does the exit of the "Send
 * Not Confirmed" screen that a stored Failure shows without a verdict (ZC-611). No check that knows the id, a
 * shielding, or a result with no ids: today's screen. A TEX send's Partial keeps its own texts (pinned
 * by TexPaidPartNeverSaysFailedTest and TexRefusedPartRecoversByItselfTest).
 *
 * The real TransactionProgressVM (its eight arguments as today) over the real use cases; the checker is the real
 * TexSendMonitor over the F3 fakes, reached by name. Every txid is synthetic and used by this class only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletSendProgressFollowsTheChainTest {
    private val vms = mutableListOf<TransactionProgressVM>()
    private val collectors = CoroutineScope(Dispatchers.Default + Job())

    private companion object {
        const val PEER = "usyntheticf5progresspeer0001"
        const val E = 4_000L
        const val TIMEOUT_MS = 10_000L
        const val CLEAR_WAIT_MS = 3_000L
        const val SETTLE_MS = 300L
        const val POLL_MS = 10L
        const val BLOCK_SECONDS = 75L
        val MINED: String = txid(0xD1)
        val WAITING: String = txid(0xD3)
        val DEAD: String = txid(0xD5)
        val NEVER_SEEN: String = txid(0xD7)
        val SHIELD_MINED: String = txid(0xD9)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        endViewModelsThenResetMain(vms, collectors)
    }

    @Test
    fun `a connection issue whose payment is mined turns into the success screen`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(MINED, mined = E - 30)), E)
            val harness = harness(sendProposal(), SubmitResult.GrpcFailure(listOf(MINED)))

            harness.awaitState("the success screen") { it is SuccessfulTransactionState }
        }

    @Test
    fun `sent but not confirmed says it waits for the network, with the block and the minutes`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(WAITING, mined = null)), E - 20)
            val harness = harness(sendProposal(), SubmitResult.SentUnconfirmed(listOf(WAITING), "synthetic"))

            val state = harness.awaitState("Waiting for the Network") { it.title() == res("send_confirmation_waiting_title") }
            val minutes = ((E + 10 - (E - 20)) * BLOCK_SECONDS + 59) / 60
            assertEquals(
                StringResource.ByResource(res("send_confirmation_waiting_subtitle").resource, listOf((E + 10).toString(), minutes.toInt())),
                state.text(),
            )
            state.property<() -> Unit>("onCloseClick")()
            state.onBack()
            assertEquals(listOf("backToRoot", "backToRoot"), harness.router.commands, "the exits no longer go to the history")
        }

    @Test
    fun `a stored failure proven never mined says Not Sent and still clears the Send form`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(DEAD, mined = null)), E + 10, E + 11)
            val harness = harness(sendProposal(), SubmitResult.Failure(listOf(DEAD), code = -26, description = "x"))

            val state = harness.awaitState("Not Sent") { it.title() == res("send_confirmation_not_sent_title") }
            assertEquals(res("send_confirmation_not_sent_subtitle"), state.text())
            state.onBack()
            harness.awaitClearRequested("Back")
            state.property<() -> Unit>("onCloseClick")()
            harness.awaitClearRequested("Close", 2)
            assertFalse("replaceAll" in harness.router.commands, "an exit opened the transaction instead")
        }

    @Test
    fun `control - a check that never saw the payment keeps today's screens`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(MINED, mined = E - 30)), E)
            val unconfirmed = harness(sendProposal(), SubmitResult.SentUnconfirmed(listOf(NEVER_SEEN), "synthetic"))
            assertEquals(stringRes(R.string.send_confirmation_unconfirmed_title), unconfirmed.settled().title())

            val grpc = harness(sendProposal(), SubmitResult.GrpcFailure(listOf(NEVER_SEEN)))
            assertTrue(grpc.settled() is GrpcFailureTransactionState)

            val stored = harness(sendProposal(), SubmitResult.Failure(listOf(NEVER_SEEN), code = -26, description = "x"))
            assertEquals(res("send_confirmation_failure_stored_title"), stored.settled().title())
        }

    @Test
    fun `control - a shielding keeps its own screen whatever the check says`() =
        runBlocking<Unit> {
            checkerPasses(listOf(plain(SHIELD_MINED, mined = E - 30)), E)
            val harness =
                harness(
                    ShieldTransactionProposal(newDistinctSdkProposal()),
                    SubmitResult.SentUnconfirmed(listOf(SHIELD_MINED), "synthetic"),
                )

            assertEquals(stringRes(R.string.send_confirmation_unconfirmed_title), harness.settled().title())
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
        suspend fun awaitState(
            what: String,
            predicate: (TransactionProgressState) -> Boolean,
        ): TransactionProgressState =
            try {
                withTimeout(TIMEOUT_MS) { vm.state.first { it != null && predicate(it) } }!!
            } catch (_: TimeoutCancellationException) {
                val shown = vm.state.value
                fail("the screen never showed $what; it shows ${shown?.javaClass?.simpleName} titled ${shown?.title()}")
            }

        /** The first settled state, after a moment for a late check to change it. */
        suspend fun settled(): TransactionProgressState {
            withTimeout(TIMEOUT_MS) { vm.state.first { it != null && it !is SendingTransactionState } }
            delay(SETTLE_MS)
            return vm.state.value!!
        }

        suspend fun awaitClearRequested(
            exit: String,
            count: Int = 1,
        ) {
            try {
                withTimeout(CLEAR_WAIT_MS) { while (clears.get() < count) delay(POLL_MS) }
            } catch (_: TimeoutCancellationException) {
                fail("$exit after a stored Failure left the Send form filled (router: ${router.commands})")
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
