package co.electriccoin.zcash.ui.screen.transactionprogress

import android.content.ContextWrapper
import co.electriccoin.zcash.configuration.api.ConfigurationProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.tex.FakeTexTor
import co.electriccoin.zcash.ui.common.tex.FakeTexWallet
import co.electriccoin.zcash.ui.common.tex.TexApi
import co.electriccoin.zcash.ui.common.tex.txid
import co.electriccoin.zcash.ui.common.usecase.CancelProposalFlowUseCase
import co.electriccoin.zcash.ui.common.usecase.ClearableZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.CountingSwapRepository
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
import co.electriccoin.zcash.ui.common.usecase.zc540Unused
import co.electriccoin.zcash.ui.design.util.StringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * ZC-540 (fix-all 2.13.0, train T6a; phone evidence of 5 Oct 2026): the send result screen is never a dead end after
 * View Transaction, Back.
 *
 * At 0470d6e2d View Transaction clears both proposal repositories and opens the transaction's detail above the result
 * screen (on the Wallet tab `replaceAll` finds no start destination to pop to). Back from the detail returns to the
 * result screen, whose state is the one it had: nothing can rebuild it without a proposal. For a send the node refused
 * but the wallet stored ("Send Not Confirmed", and "Not Sent" once the chain proves it) Close, the arrow and system
 * BACK all run CancelProposalFlowUseCase, which waits for a proposal for ever. Nothing happens; the user must
 * force-stop the app.
 *
 * Wanted: each of those exits leaves the screen (to the root: the Send entry under it is popped with it, so the old
 * form cannot be sent again) and asks for no Send-form clear, which nothing would collect before the next Send form
 * opened, perhaps one filled by Send again.
 *
 * The real TransactionProgressVM over the real use cases and a repository with the real `clear` and
 * `getTransactionProposal` ([ClearableZashiProposalRepository]; the holding proxy of the neighbour tests returns a
 * constant and ignores `clear`, which is why none of them saw this). Each case presses the exit of the state the
 * screen SHOWS after View Transaction. The checker is the real TexSendMonitor over the F3 fakes, as in
 * WalletSendProgressFollowsTheChainTest. Every txid is synthetic and used by this class only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloseAfterViewTransactionStillLeavesTheSendResultScreenTest {
    private val vms = mutableListOf<TransactionProgressVM>()
    private val collectors = CoroutineScope(Dispatchers.Default + Job())

    private companion object {
        const val PEER = "usyntheticzc540resultpeer0001"
        const val E = 4_000L
        const val TIMEOUT_MS = 10_000L
        const val LEAVE_WAIT_MS = 3_000L
        const val SETTLE_MS = 300L
        const val NO_CLEAR_GRACE_MS = 500L
        const val POLL_MS = 10L
        val REFUSED_CLOSE: String = txid(0x41)
        val REFUSED_BACK: String = txid(0x43)
        val DEAD_CLOSE: String = txid(0x45)
        val DEAD_BACK: String = txid(0x47)
        val WAITING: String = txid(0x49)
        val REFUSED_CONTROL: String = txid(0x4B)
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
    fun `Close on a Send Not Confirmed screen leaves it after View Transaction and Back`() =
        runBlocking {
            val harness = harness(SubmitResult.Failure(listOf(REFUSED_CLOSE), code = -26, description = "x"))
            harness.awaitTitle("send_confirmation_failure_stored_title")

            harness.viewTransactionThenBack().exit("onCloseClick")

            harness.assertLeftToTheRoot("Close")
        }

    @Test
    fun `the back arrow and system BACK on a Send Not Confirmed screen leave it after View Transaction and Back`() =
        runBlocking {
            val harness = harness(SubmitResult.Failure(listOf(REFUSED_BACK), code = -26, description = "x"))
            harness.awaitTitle("send_confirmation_failure_stored_title")

            harness.viewTransactionThenBack().onBack()

            harness.assertLeftToTheRoot("Back")
        }

    @Test
    fun `Close on the Not Sent screen leaves it after View Transaction and Back`() =
        runBlocking {
            checkerPasses(listOf(plain(DEAD_CLOSE)), E + 10, E + 11)
            val harness = harness(SubmitResult.Failure(listOf(DEAD_CLOSE), code = -26, description = "x"))
            harness.awaitTitle("send_confirmation_not_sent_title")

            harness.viewTransactionThenBack().exit("onCloseClick")

            harness.assertLeftToTheRoot("Close")
        }

    @Test
    fun `the back arrow and system BACK on the Not Sent screen leave it after View Transaction and Back`() =
        runBlocking {
            checkerPasses(listOf(plain(DEAD_BACK)), E + 10, E + 11)
            val harness = harness(SubmitResult.Failure(listOf(DEAD_BACK), code = -26, description = "x"))
            harness.awaitTitle("send_confirmation_not_sent_title")

            harness.viewTransactionThenBack().onBack()

            harness.assertLeftToTheRoot("Back")
        }

    /**
     * Control, green before and after: since ZC-597 the exits of "Waiting for the Network" go to the history for
     * every result, which never waited for a proposal.
     */
    @Test
    fun `control - Close and Back on the Waiting for the Network screen leave it after View Transaction and Back`() =
        runBlocking {
            checkerPasses(listOf(plain(WAITING)), E - 20)
            val harness = harness(SubmitResult.Failure(listOf(WAITING), code = -26, description = "x"))
            harness.awaitTitle("send_confirmation_waiting_title")

            val shown = harness.viewTransactionThenBack()
            shown.exit("onCloseClick")
            shown.onBack()

            assertEquals(listOf("replaceAll", "backToRoot", "backToRoot"), harness.router.commands)
            harness.assertNoClearRequested()
        }

    /**
     * Control: with no View Transaction first, Close on Send Not Confirmed leaves the screen too. Until 14 Oct it pinned
     * "clears the form and goes back to Send"; ZC-611 (T7b triage 13 Oct) sends that exit to the history, because the
     * SDK may still broadcast the stored transaction and an emptied Send form invites a second payment (W-10).
     */
    @Test
    fun `control - without View Transaction first, Close on Send Not Confirmed goes to the history and asks for no clear`() =
        runBlocking {
            val harness = harness(SubmitResult.Failure(listOf(REFUSED_CONTROL), code = -26, description = "x"))
            val state = harness.awaitTitle("send_confirmation_failure_stored_title")

            state.exit("onCloseClick")

            harness.awaitCommands("Close", listOf("backToRoot"))
            harness.assertNoClearRequested()
            assertEquals(listOf("backToRoot"), harness.router.commands)
        }

    // --------------------------------------------------------------- the checker

    private fun plain(txId: String): Any =
        TexApi.facts(
            txId = txId,
            mined = null,
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

    private fun res(name: String): Int =
        try {
            R.string::class.java.getField(name).getInt(null)
        } catch (e: NoSuchFieldException) {
            fail("R.string.$name does not exist: $e")
        }

    private fun TransactionProgressState.property(name: String): Any? =
        javaClass.methods
            .firstOrNull { it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } && it.parameterCount == 0 }
            ?.invoke(this)

    @Suppress("UNCHECKED_CAST")
    private fun TransactionProgressState.exit(name: String) {
        val exit = property(name) as (() -> Unit)? ?: fail("${javaClass.simpleName} has no '$name'")
        exit()
    }

    private inner class Harness(
        val vm: TransactionProgressVM,
        val router: Zc101RecordingNavigationRouter,
        val clears: AtomicInteger,
    ) {
        suspend fun awaitTitle(name: String): TransactionProgressState {
            val wanted = res(name)
            return try {
                withTimeout(TIMEOUT_MS) {
                    vm.state.first { (it?.property("title") as? StringResource.ByResource)?.resource == wanted }
                }!!
            } catch (_: TimeoutCancellationException) {
                val shown = vm.state.value
                fail("the screen never showed $name; it shows ${shown?.javaClass?.simpleName} ${shown?.property("title")}")
            }
        }

        /**
         * Taps View Transaction on the screen shown, and returns what the screen shows once the user is back on it
         * (the detail opened above it; Back from the detail pops only the detail).
         */
        suspend fun viewTransactionThenBack(): TransactionProgressState {
            vm.state.value!!.exit("onViewTransactionClick")
            assertEquals(listOf("replaceAll"), router.commands, "View Transaction opens the transaction")
            delay(SETTLE_MS)
            return vm.state.value ?: fail("the result screen shows nothing after View Transaction")
        }

        suspend fun awaitCommands(
            exit: String,
            expected: List<String>,
        ) {
            try {
                withTimeout(LEAVE_WAIT_MS) { while (router.commands.size < expected.size) delay(POLL_MS) }
            } catch (_: TimeoutCancellationException) {
                fail(
                    "$exit did nothing for ${LEAVE_WAIT_MS / 1_000} s: the screen was not left " +
                        "(router: ${router.commands}, wanted: $expected)",
                )
            }
        }

        suspend fun assertLeftToTheRoot(exit: String) {
            awaitCommands(exit, listOf("replaceAll", "backToRoot"))
            assertEquals(listOf("replaceAll", "backToRoot"), router.commands, "$exit leaves to the root, once")
            assertNoClearRequested()
        }

        suspend fun assertNoClearRequested() {
            delay(NO_CLEAR_GRACE_MS)
            assertEquals(0, clears.get(), "a Send-form clear was requested though no Send form is reached")
        }
    }

    private suspend fun sendProposal(): TransactionProposal = syntheticZip321Proposal(PEER, amount = 1_000L, memo = "synthetic")

    private suspend fun harness(result: SubmitResult): Harness {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val keystone = Zc101FakeKeystoneProposalRepository()
        val zashi = ClearableZashiProposalRepository(sendProposal(), SubmitProposalState.Result(result))
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
                    SendEmailUseCase(context, GetSupportUseCase(context, zc540Unused<ConfigurationProvider>())),
                cancelKeystoneProposalFlow =
                    CancelProposalFlowUseCase(
                        zashiProposalRepository = zashi,
                        keystoneProposalRepository = keystone,
                        navigationRouter = router,
                        observeClearSend = observeClearSend,
                        accountDataSource = accounts,
                        swapRepository = CountingSwapRepository(),
                    ),
                viewTransactionsAfterSuccessfulProposal = ViewTransactionsAfterSuccessfulProposalUseCase(keystone, zashi, router),
                viewTransactionDetailAfterSuccessfulProposal =
                    ViewTransactionDetailAfterSuccessfulProposalUseCase(keystone, zashi, router),
            )
        vms += vm
        // The screen collects the state for as long as it is on the stack, under the detail too.
        collectors.launch { vm.state.collect { } }
        return Harness(vm, router, clears)
    }
}
