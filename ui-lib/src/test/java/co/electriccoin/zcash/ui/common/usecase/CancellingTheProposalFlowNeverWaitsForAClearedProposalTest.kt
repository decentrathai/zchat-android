package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.keystone.RouteRecordingNavigationRouter
import co.electriccoin.zcash.ui.keystone.TwoAccountDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Clock

/**
 * ZC-540 (fix-all 2.13.0, train T6a): leaving a send flow never waits for a proposal that is already gone.
 *
 * At 0470d6e2d CancelProposalFlowUseCase reads the proposal with `getTransactionProposal()`, which is
 * `transactionProposal.filterNotNull().first()` in both repositories. After View Transaction on the send result screen
 * (ViewTransactionDetailAfterSuccessfulProposalUseCase clears both repositories) that read waits for ever, so the
 * clear and the navigation behind it never run: Close, the arrow and system BACK of the result screen do nothing.
 *
 * Wanted: the use case reads the proposal as it is now. A live proposal keeps every branch as today (a swap goes back
 * to Swap, a pay to Pay, anything else asks the Send form to clear when told and goes back to Send). No proposal means
 * the flow was already finished: both repositories are cleared, the Send form is NOT asked to clear (no form is
 * reached, and a clear nobody collects would wipe a later Send-again prefill), and the screen is left: to the root
 * when the caller wanted the form cleared (the result screen's exit), back to Send otherwise.
 *
 * The real use case over repositories with the real semantics ([ClearableZashiProposalRepository],
 * [ClearableKeystoneProposalRepository]). Every address is synthetic.
 */
class CancellingTheProposalFlowNeverWaitsForAClearedProposalTest {
    private val collectors = CoroutineScope(Dispatchers.Default + Job())

    @After
    fun tearDown() {
        collectors.cancel()
    }

    @Test
    fun `with no proposal left, the exit that clears the form leaves to the root and asks for no clear`() =
        runBlocking {
            val harness = harness(zashiProposal = null)

            harness.invokeInTime(clearSendForm = true)

            assertEquals(listOf("backToRoot"), harness.router.commands)
            harness.assertClears(0, "a clear nobody will collect until the next Send form was requested")
            assertEquals(listOf(1, 1), listOf(harness.zashi.clears, harness.keystone.clears), "both repositories are cleared")
        }

    @Test
    fun `with no proposal left, the exit that keeps the form goes back to Send`() =
        runBlocking {
            val harness = harness(zashiProposal = null)

            harness.invokeInTime(clearSendForm = false)

            assertEquals(listOf("backTo(Send)"), harness.router.commands)
            harness.assertClears(0, "the form was asked to clear")
        }

    @Test
    fun `a Keystone account with no proposal left leaves to the root too`() =
        runBlocking {
            val harness = harness(zashiProposal = null, accounts = TwoAccountDataSource.keystoneSelected())

            harness.invokeInTime(clearSendForm = true)

            assertEquals(listOf("backToRoot"), harness.router.commands)
            harness.assertClears(0, "a clear nobody will collect until the next Send form was requested")
        }

    /** Control, green before and after: a live send clears the form when asked and returns to Send. */
    @Test
    fun `control - a live send proposal asks the form to clear and goes back to Send`() =
        runBlocking {
            val harness = harness(zashiProposal = syntheticRegularSendProposal(PEER, amount = 1_000L, memo = ""))

            harness.invokeInTime(clearSendForm = true)

            assertEquals(listOf("backTo(Send)"), harness.router.commands)
            harness.assertClears(1, "the Send form was not asked to clear exactly once")
            assertNull(harness.zashi.transactionProposal.value, "the proposal is cleared")
            assertEquals(0, harness.swap.quoteClears, "a plain send cleared a swap quote")
        }

    /** Control, green before and after: Back from Review keeps the form. */
    @Test
    fun `control - a live send proposal keeps the form when not asked to clear it`() =
        runBlocking {
            val harness = harness(zashiProposal = syntheticRegularSendProposal(PEER, amount = 1_000L, memo = ""))

            harness.invokeInTime(clearSendForm = false)

            assertEquals(listOf("backTo(Send)"), harness.router.commands)
            harness.assertClears(0, "the form was asked to clear")
        }

    /** Control, green before and after. */
    @Test
    fun `control - a live swap goes back to Swap and drops its quote`() =
        runBlocking {
            val harness = harness(zashiProposal = syntheticSwapProposal(Zc101FakeSwapQuote(Clock.System.now())))

            harness.invokeInTime(clearSendForm = true)

            assertEquals(listOf("backTo(SwapArgs)"), harness.router.commands)
            assertEquals(1, harness.swap.quoteClears)
            harness.assertClears(0, "a swap asked the Send form to clear")
        }

    /** Control, green before and after. */
    @Test
    fun `control - a live cross-chain payment goes back to Pay and drops its quote`() =
        runBlocking {
            val proposal =
                ExactOutputSwapTransactionProposal(
                    destination = WalletAddress.Transparent.new("tsynthetic-zc540-pay-deposit"),
                    amount = Zatoshi(100_000_000),
                    memo = Memo(""),
                    proposal = newDistinctSdkProposal(),
                    quote = Zc101FakeSwapQuote(Clock.System.now(), SwapMode.EXACT_OUTPUT),
                )
            val harness = harness(zashiProposal = proposal)

            harness.invokeInTime(clearSendForm = true)

            assertEquals(listOf("backTo(PayArgs)"), harness.router.commands)
            assertEquals(1, harness.swap.quoteClears)
            harness.assertClears(0, "a cross-chain payment asked the Send form to clear")
        }

    /** Control, green before and after: a Keystone account reads the Keystone session. */
    @Test
    fun `control - a Keystone account with a live proposal asks the form to clear and goes back to Send`() =
        runBlocking {
            val harness =
                harness(
                    zashiProposal = null,
                    keystoneProposal = syntheticRegularSendProposal(PEER, amount = 1_000L, memo = ""),
                    accounts = TwoAccountDataSource.keystoneSelected(),
                )

            harness.invokeInTime(clearSendForm = true)

            assertEquals(listOf("backTo(Send)"), harness.router.commands)
            harness.assertClears(1, "the Send form was not asked to clear exactly once")
        }

    // --------------------------------------------------------------- harness

    private inner class Harness(
        val useCase: CancelProposalFlowUseCase,
        val zashi: ClearableZashiProposalRepository,
        val keystone: ClearableKeystoneProposalRepository,
        val router: RouteRecordingNavigationRouter,
        val swap: CountingSwapRepository,
        val clearRequests: AtomicInteger,
    ) {
        suspend fun invokeInTime(clearSendForm: Boolean) {
            withTimeoutOrNull(INVOKE_TIMEOUT_MS) { useCase(clearSendForm = clearSendForm) }
                ?: fail(
                    "leaving the send flow was still waiting after ${INVOKE_TIMEOUT_MS / 1_000} s " +
                        "(router: ${router.commands})",
                )
        }

        suspend fun assertClears(
            expected: Int,
            message: String,
        ) {
            withTimeoutOrNull(INVOKE_TIMEOUT_MS) { while (clearRequests.get() < expected) delay(POLL_MS) }
            delay(NO_CLEAR_GRACE_MS)
            assertEquals(expected, clearRequests.get(), message)
        }
    }

    private suspend fun harness(
        zashiProposal: TransactionProposal?,
        keystoneProposal: TransactionProposal? = null,
        accounts: AccountDataSource? = null,
    ): Harness {
        val zashi = ClearableZashiProposalRepository(zashiProposal, null)
        val keystone = ClearableKeystoneProposalRepository(keystoneProposal)
        val router = RouteRecordingNavigationRouter()
        val swap = CountingSwapRepository()
        val observeClearSend = ObserveClearSendUseCase()
        val clearRequests = AtomicInteger()
        collectors.launch { observeClearSend().collect { clearRequests.incrementAndGet() } }
        val useCase =
            CancelProposalFlowUseCase(
                zashiProposalRepository = zashi,
                keystoneProposalRepository = keystone,
                navigationRouter = router,
                observeClearSend = observeClearSend,
                accountDataSource = accounts ?: Zc101FakeAccountDataSource(syntheticZashiAccount()),
                swapRepository = swap,
            )
        return Harness(useCase, zashi, keystone, router, swap, clearRequests)
    }

    private companion object {
        const val PEER = "usyntheticzc540cancelpeer0001"
        const val INVOKE_TIMEOUT_MS = 3_000L
        const val NO_CLEAR_GRACE_MS = 500L
        const val POLL_MS = 10L
    }
}

/** A proxy of [T] that refuses every call: the delegate behind a fake that overrides all the test may reach. */
internal inline fun <reified T> zc540Unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { p, method, args ->
        when (method.name) {
            "toString" -> "Unused${T::class.java.simpleName}"
            "hashCode" -> System.identityHashCode(p)
            "equals" -> p === args?.get(0)
            else -> throw UnsupportedOperationException("unexpected call ${method.name}")
        }
    } as T

/**
 * The Zashi proposal repository with the semantics of ZashiProposalRepositoryImpl that matter after a send:
 * `getTransactionProposal()` waits for a proposal, and `clear()` drops the proposal and the submit state.
 */
internal class ClearableZashiProposalRepository(
    proposal: TransactionProposal?,
    state: SubmitProposalState?,
) : ZashiProposalRepository by zc540Unused<ZashiProposalRepository>() {
    @Volatile
    var clears = 0
        private set

    override val transactionProposal = MutableStateFlow(proposal)
    override val submitState = MutableStateFlow(state)

    override suspend fun getTransactionProposal(): TransactionProposal = transactionProposal.filterNotNull().first()

    override fun clear() {
        clears++
        transactionProposal.value = null
        submitState.value = null
    }
}

/** The Keystone session with the same two semantics of KeystoneProposalRepositoryImpl. */
internal class ClearableKeystoneProposalRepository(
    proposal: TransactionProposal?,
) : KeystoneProposalRepository by zc540Unused<KeystoneProposalRepository>() {
    @Volatile
    var clears = 0
        private set

    override val transactionProposal = MutableStateFlow(proposal)
    override val submitState = MutableStateFlow<SubmitProposalState?>(null)

    override suspend fun getTransactionProposal(): TransactionProposal = transactionProposal.filterNotNull().first()

    override fun clear() {
        clears++
        transactionProposal.value = null
        submitState.value = null
    }
}

internal class CountingSwapRepository : SwapRepository by Zc101FakeSwapRepository() {
    @Volatile
    var quoteClears = 0
        private set

    override fun clearQuote() {
        quoteClears++
    }
}
