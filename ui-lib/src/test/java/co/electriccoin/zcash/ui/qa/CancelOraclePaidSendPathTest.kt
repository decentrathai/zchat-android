package co.electriccoin.zcash.ui.qa

import android.content.ContextWrapper
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.configuration.api.ConfigurationProvider
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.CancelProposalFlowUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSupportUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveClearSendUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveTransactionSubmitStateUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.ProcessSwapTransactionUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewTransactionDetailAfterSuccessfulProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewTransactionsAfterSuccessfulProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ZC101_PAYMENT_URI
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeMetadataRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.transactionprogress.FailureTransactionState
import co.electriccoin.zcash.ui.screen.transactionprogress.SendingTransactionState
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressVM
import co.electriccoin.zcash.ui.screen.transactionprogress.endViewModelsThenResetMain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Product-path cancel-oracle: REAL [SubmitProposalUseCase] + [ZashiProposalRepositoryImpl]
 * + [CancelProposalFlowUseCase] against the recording settlement seam.
 *
 * Asserts durable attempt / transaction graph for one logical paid send:
 *  - cancel before settlement → zero submits
 *  - cancel then retry approve → exactly one submit
 *  - concurrent double-invoke → at most one submit (inFlight latch)
 *  - CancelProposalFlow after create clears the shared cell
 *  - a cancel after the store, or between the store and its Result, settles nothing more
 *  - after Failure(txIds = [T]) the failure screen's exit clears the Send form, so no filled form can build T2
 *
 * PaymentDiag Log.i is a JVM no-op here; settlement counts are the durable graph.
 * Event-stream oracle coverage lives in [CancelOracleTest].
 */
class CancelOraclePaidSendPathTest {
    private class Harness(
        val dataSource: Zc101RecordingProposalDataSource,
        val repository: ZashiProposalRepositoryImpl,
        val useCase: SubmitProposalUseCase,
        val cancelFlow: CancelProposalFlowUseCase,
        val navigationRouter: Zc101RecordingNavigationRouter,
        val observeClearSend: ObserveClearSendUseCase,
        val keystone: Zc101FakeKeystoneProposalRepository,
        val accountDataSource: Zc101FakeAccountDataSource,
    )

    private suspend fun harness(
        biometrics: BiometricRepository,
        dataSource: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
        settlement: ProposalDataSource = dataSource,
    ): Harness {
        val account = syntheticZashiAccount()
        val repository =
            ZashiProposalRepositoryImpl(
                accountDataSource = Zc101FakeAccountDataSource(account),
                proposalDataSource = settlement,
                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
            )
        val metadataRepository = Zc101FakeMetadataRepository()
        val navigationRouter = Zc101RecordingNavigationRouter()
        val accountDataSource = Zc101FakeAccountDataSource(account)
        val keystone = Zc101FakeKeystoneProposalRepository()
        val swapRepository = Zc101FakeSwapRepository()
        val useCase =
            SubmitProposalUseCase(
                navigationRouter = navigationRouter,
                accountDataSource = accountDataSource,
                zashiProposalRepository = repository,
                keystoneProposalRepository = keystone,
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
        val observeClearSend = ObserveClearSendUseCase()
        val cancelFlow =
            CancelProposalFlowUseCase(
                zashiProposalRepository = repository,
                keystoneProposalRepository = keystone,
                navigationRouter = navigationRouter,
                observeClearSend = observeClearSend,
                accountDataSource = accountDataSource,
                swapRepository = swapRepository,
            )
        return Harness(
            dataSource,
            repository,
            useCase,
            cancelFlow,
            navigationRouter,
            observeClearSend,
            keystone,
            accountDataSource,
        )
    }

    private suspend fun preparePayment(h: Harness) =
        syntheticZip321Proposal(PAYEE, amount = AMOUNT, memo = MEMO).also { payment ->
            h.dataSource.preparedByUri[ZC101_PAYMENT_URI] = payment
            val created = h.repository.createZip321Proposal(ZC101_PAYMENT_URI, allowMultiPayment = true)
            assertSame(payment, created)
        }

    /** Immediate cancel at the biometric gate. */
    private class CancellingBiometricRepository : BiometricRepository {
        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest): Unit = throw BiometricsCancelledException()
    }

    /**
     * Controllable bio: park → cancel OR approve. Allows cancel-then-retry on a fresh instance
     * pair (one cancel call, one later approve call) without mutating the shared Zc101 fake.
     */
    private class ControllableBiometricRepository : BiometricRepository {
        val promptShown = CompletableDeferred<Unit>()
        private val decision = CompletableDeferred<Boolean>()

        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest) {
            promptShown.complete(Unit)
            if (!decision.await()) throw BiometricsCancelledException()
        }

        fun approve() {
            decision.complete(true)
        }

        fun cancel() {
            decision.complete(false)
        }
    }

    @Test
    fun `cancel before submit yields zero settlements`() =
        runBlocking {
            val h = harness(CancellingBiometricRepository())
            val payment = preparePayment(h)

            h.useCase(payment)

            // Detached submit scope must not fire after cancel.
            delay(SETTLE_GRACE_MS)
            assertEquals(
                "biometric cancel must not reach ProposalDataSource.submitTransaction",
                0,
                h.dataSource.submittedProposals.size,
            )
            // Cell still holds the created proposal (SubmitProposalUseCase cancel is a no-op clear);
            // that is OK — zero submit is the money invariant.
            assertSame(payment, h.repository.transactionProposal.value)
        }

    @Test
    fun `cancel then retry approve yields exactly one settlement`() =
        runBlocking {
            val cancelBio = ControllableBiometricRepository()
            val h = harness(cancelBio)
            val payment = preparePayment(h)

            val cancelJob = launch(Dispatchers.Default) { h.useCase(payment) }
            withTimeout(AWAIT_MS) { cancelBio.promptShown.await() }
            cancelBio.cancel()
            cancelJob.join()
            assertEquals("pre-retry: cancel must submit nothing", 0, h.dataSource.submittedProposals.size)

            // Retry: new use case with approving biometrics, SAME proposal instance (logical send).
            val approveBio = ControllableBiometricRepository()
            val h2 = harnessReuse(h, approveBio)
            val retryJob = launch(Dispatchers.Default) { h2.useCase(payment) }
            withTimeout(AWAIT_MS) { approveBio.promptShown.await() }
            approveBio.approve()
            retryJob.join()
            withTimeout(AWAIT_MS) { h.dataSource.firstSettlement.await() }

            assertEquals(
                "retry after cancel must settle exactly once for this logical send",
                1,
                h.dataSource.submittedProposals.size,
            )
        }

    @Test
    fun `concurrent double invoke settles at most once`() =
        runBlocking {
            val bio = ControllableBiometricRepository()
            val h = harness(bio)
            val payment = preparePayment(h)

            val job1 = launch(Dispatchers.Default) { h.useCase(payment) }
            withTimeout(AWAIT_MS) { bio.promptShown.await() }
            // Second invoke while first is in-flight — inFlight latch must no-op it.
            val job2 = launch(Dispatchers.Default) { h.useCase(payment) }
            delay(100)
            bio.approve()
            job1.join()
            job2.join()
            withTimeout(AWAIT_MS) { h.dataSource.firstSettlement.await() }
            delay(SETTLE_GRACE_MS)

            assertEquals(
                "inFlight latch: overlapping invoke must not double-settle",
                1,
                h.dataSource.submittedProposals.size,
            )
        }

    @Test
    fun `CancelProposalFlow after create clears cell and leaves zero submits`() =
        runBlocking {
            val h = harness(CancellingBiometricRepository())
            preparePayment(h)
            assertTrue(h.repository.transactionProposal.value != null)

            h.cancelFlow(clearSendForm = false)

            assertNull(
                "CancelProposalFlowUseCase must clear the shared proposal cell",
                h.repository.transactionProposal.value,
            )
            assertEquals(0, h.dataSource.submittedProposals.size)
        }

    /**
     * Round 7 (GPT Q5, Grok Q5, H5 — CANCEL-ORACLE-ASSURANCE): the fake has already STORED the transaction (its
     * settlement returned a tx id) when the user's cancel arrives. The cancel must not settle anything again, and
     * the stored result stands. Counts settlement calls and distinct settled proposals, not log lines.
     */
    @Test
    fun `cancel that arrives after the transaction was stored settles nothing more`() =
        runBlocking {
            val bio = ControllableBiometricRepository()
            val h = harness(bio)
            val payment = preparePayment(h)

            settleOnce(h, bio, payment)

            h.cancelFlow(clearSendForm = false)
            delay(SETTLE_GRACE_MS)

            assertEquals("a cancel after the store must not settle again", 1, h.dataSource.submittedProposals.size)
            assertEquals(1, distinctSettled(h))
            assertNull("the cancel clears the shared cell", h.repository.transactionProposal.value)
        }

    /**
     * Round 7c (R6-4 review finding 17, CANCEL-ORACLE-ASSURANCE), closed in round 8 D3 (REVIEW_7C F30).
     *
     * The user's path after a submit that STORED transaction T and then reported `SubmitResult.Failure(txIds =
     * [T])`:
     *
     *  1. the settlement stores T and reports the Failure (the real SubmitProposalUseCase and repository);
     *  2. the user leaves the failure screen by Close, the exit TransactionProgressVM builds for that result (the
     *     real VM over the same repository and CancelProposalFlowUseCase);
     *  3. before D3 that exit was `cancelFlow(clearSendForm = false)` and the Send form stayed filled, so one tap
     *     built a fresh proposal T2 for the same payee, amount and memo and settled it while T could still be
     *     broadcast ("settled 2 time(s)", round7c C9 evidence). D3 made the exit ask the Send form to clear
     *     (`ObserveClearSendUseCase.requestClear()`) and return to it; since ZC-611 (owner rule W-10, ZC-596) it goes
     *     to the history instead (backToRoot, which pops the Send entry with it) and asks for no clear, because an
     *     emptied form under a send the SDK may still broadcast invites a second payment too.
     *
     * Wanted: the exit goes to the history only, nothing asks the Send form to clear, and one logical send settles
     * once.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `after a Failure with stored txIds the failure screen exit goes to the history and the send settles once`() =
        runBlocking {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            var vm: TransactionProgressVM? = null
            try {
                val bio = ControllableBiometricRepository()
                val recorder = Zc101RecordingProposalDataSource()
                val storedFailure = SubmitResult.Failure(txIds = listOf(STORED_TXID), code = 1, description = "x")
                val seam = StoringSeam(recorder, storedFailure)
                val h = harness(bio, recorder, seam)
                val payment = preparePayment(h)
                val progress = progressVm(h).also { vm = it }

                val job = launch(Dispatchers.Default) { h.useCase(payment) }
                withTimeout(AWAIT_MS) { bio.promptShown.await() }
                bio.approve()
                job.join()
                val stored = awaitResult(h)
                assertTrue(
                    "fixture: a Failure that names the stored transaction",
                    stored is SubmitResult.Failure && stored.txIds == listOf(STORED_TXID),
                )
                assertEquals("fixture: T was stored once", 1, recorder.submittedProposals.size)

                // Step 2: the failure screen the user sees for that result, and its Close.
                val screen =
                    withTimeout(AWAIT_MS) {
                        progress.state.first { it != null && it !is SendingTransactionState }
                    }
                val failure =
                    screen as? FailureTransactionState
                        ?: throw AssertionError("fixture: expected the Failure screen, got ${screen?.javaClass?.name}")
                h.navigationRouter.commands.clear()
                failure.onCloseClick()

                // Step 3 (ZC-611, re-pinned 14 Oct: this case pinned D3's "clear the form and return to it"): the exit
                // goes to the history, which pops the Send entry, and asks the Send form for no clear.
                try {
                    withTimeout(CLEAR_WAIT_MS) { while (h.navigationRouter.commands.isEmpty()) delay(ROUTER_POLL_MS) }
                } catch (_: TimeoutCancellationException) {
                    fail("the failure screen's exit went nowhere after a Failure with stored txIds")
                }
                val cleared = withTimeoutOrNull(NO_CLEAR_WAIT_MS) { h.observeClearSend().first() }
                assertNull("the failure screen's exit emptied the Send form of a send the SDK may still broadcast", cleared)
                assertEquals("the exit does not go to the history only", listOf("backToRoot"), h.navigationRouter.commands)
                assertNull("the exit clears the proposal cell", h.repository.transactionProposal.value)

                delay(SETTLE_GRACE_MS)
                assertEquals(
                    "one logical send must settle at most once; it settled ${recorder.submittedProposals.size} time(s)",
                    1,
                    recorder.submittedProposals.size,
                )
            } finally {
                endViewModelsThenResetMain(listOfNotNull(vm))
            }
        }

    /** The real transaction-progress screen over the harness's repository, cancel flow and router. */
    private fun progressVm(h: Harness): TransactionProgressVM {
        val context = ContextWrapper(null)
        return TransactionProgressVM(
            observeTransactionProposal = ObserveProposalUseCase(h.keystone, h.repository, h.accountDataSource),
            observeTransactionSubmitState =
                ObserveTransactionSubmitStateUseCase(h.keystone, h.repository, h.accountDataSource),
            getTransactionProposal = GetProposalUseCase(h.keystone, h.repository, h.accountDataSource),
            copyToClipboardUseCase = CopyToClipboardUseCase(context),
            sendEmailUseCase = SendEmailUseCase(context, GetSupportUseCase(context, unusedConfigurationProvider())),
            cancelKeystoneProposalFlow = h.cancelFlow,
            viewTransactionsAfterSuccessfulProposal =
                ViewTransactionsAfterSuccessfulProposalUseCase(h.keystone, h.repository, h.navigationRouter),
            viewTransactionDetailAfterSuccessfulProposal =
                ViewTransactionDetailAfterSuccessfulProposalUseCase(h.keystone, h.repository, h.navigationRouter),
        )
    }

    private fun unusedConfigurationProvider(): ConfigurationProvider =
        Proxy.newProxyInstance(
            ConfigurationProvider::class.java.classLoader,
            arrayOf(ConfigurationProvider::class.java),
        ) { p, method, args ->
            when (method.name) {
                "toString" -> "UnusedConfigurationProvider"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> throw UnsupportedOperationException("unexpected call ${method.name}")
            }
        } as ConfigurationProvider

    /**
     * Round 7c (R6-4 review finding 17): the cancel arrives in the window after the settlement STORED the
     * transaction and before its Result is published. The cancel must not settle anything, the stored result must
     * still be published (the transaction is not hidden), and the confirm screen has no proposal left to confirm.
     */
    @Test
    fun `cancel between the store and the Result settles nothing more and keeps the stored result`() =
        runBlocking {
            val bio = ControllableBiometricRepository()
            val recorder = Zc101RecordingProposalDataSource()
            val release = CompletableDeferred<Unit>()
            val seam = StoringSeam(recorder, SubmitResult.Success(txIds = listOf(STORED_TXID)), release)
            val h = harness(bio, recorder, seam)
            val payment = preparePayment(h)

            val job = launch(Dispatchers.Default) { h.useCase(payment) }
            withTimeout(AWAIT_MS) { bio.promptShown.await() }
            bio.approve()
            job.join()
            withTimeout(AWAIT_MS) { seam.stored.await() }
            assertTrue(
                "fixture: the Result is not published yet",
                h.repository.submitState.value !is SubmitProposalState.Result,
            )

            h.cancelFlow(clearSendForm = false)
            assertNull("the cancel clears the proposal cell", h.repository.transactionProposal.value)

            release.complete(Unit)
            val published = awaitResult(h)
            delay(SETTLE_GRACE_MS)

            assertEquals("the cancel in the window must not settle again", 1, recorder.submittedProposals.size)
            assertEquals(1, distinctSettled(h))
            assertEquals(
                "the stored transaction's result is still published after the cancel",
                listOf(STORED_TXID),
                (published as? SubmitResult.Success)?.txIds,
            )
            assertNull("nothing is left for the confirm screen to settle", h.repository.transactionProposal.value)
        }

    private suspend fun awaitResult(h: Harness): SubmitResult =
        withTimeout(AWAIT_MS) {
            h.repository.submitState
                .filterIsInstance<SubmitProposalState.Result>()
                .first()
        }.submitResult

    /**
     * The settlement seam for the round 7c cases: records the proposal through [recorder] (the transaction is
     * STORED), then optionally waits for [release] before returning [result]. Delegates everything else.
     */
    private class StoringSeam(
        private val recorder: Zc101RecordingProposalDataSource,
        private val result: SubmitResult,
        private val release: CompletableDeferred<Unit>? = null,
    ) : ProposalDataSource by recorder {
        val stored = CompletableDeferred<Unit>()

        // F4: the 1:1 chat senders now call this overload (to record the stored ids before the submit). This fake
        // reports no stored ids: it settles through its own two-argument submit below, exactly as before F4.
        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored,
        ): SubmitResult = submitTransaction(proposal, usk)

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult {
            recorder.submitTransaction(proposal, usk)
            stored.complete(Unit)
            release?.await()
            return result
        }
    }

    private suspend fun settleOnce(
        h: Harness,
        bio: ControllableBiometricRepository,
        payment: co.electriccoin.zcash.ui.common.datasource.TransactionProposal,
    ) = kotlinx.coroutines.coroutineScope {
        val job = launch(Dispatchers.Default) { h.useCase(payment) }
        withTimeout(AWAIT_MS) { bio.promptShown.await() }
        bio.approve()
        job.join()
        withTimeout(AWAIT_MS) { h.dataSource.firstSettlement.await() }
        val stored =
            withTimeout(AWAIT_MS) {
                h.repository.submitState
                    .filterIsInstance<SubmitProposalState.Result>()
                    .first()
            }
        val storedIds = (stored.submitResult as? SubmitResult.Success)?.txIds.orEmpty()
        assertTrue("fixture: the fake stored a transaction id", storedIds.isNotEmpty())
        assertEquals("fixture: one settlement before the cancel", 1, h.dataSource.submittedProposals.size)
    }

    private fun distinctSettled(h: Harness): Int =
        h.dataSource.submittedProposals
            .map { System.identityHashCode(it) }
            .distinct()
            .size

    @Test
    fun `oracle negative control still fails when product path is green`() {
        // Sanity: product GREEN must not weaken the event-stream negative control.
        val events =
            listOf(
                CancelOracle.Event("proposal_submitted", pid = "1:aaaa:1:Zip321", logicalSendId = "req-1", ts = 1.0),
                CancelOracle.Event("proposal_submitted", pid = "2:aaaa:1:Zip321", logicalSendId = "req-1", ts = 2.0),
            )
        assertEquals(
            CancelOracle.Verdict.FAILED,
            CancelOracle.combine(CancelOracle.evaluateNegativeDoubleSubmit(events, "req-1")),
        )
    }

    /** Rebuild SubmitProposalUseCase against the SAME repository/dataSource with new biometrics. */
    private suspend fun harnessReuse(
        prior: Harness,
        biometrics: BiometricRepository,
    ): Harness {
        val account = syntheticZashiAccount()
        val metadataRepository = Zc101FakeMetadataRepository()
        val accountDataSource = Zc101FakeAccountDataSource(account)
        val useCase =
            SubmitProposalUseCase(
                navigationRouter = prior.navigationRouter,
                accountDataSource = accountDataSource,
                zashiProposalRepository = prior.repository,
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                biometricRepository = biometrics,
                swapRepository = Zc101FakeSwapRepository(),
                metadataRepository = metadataRepository,
                processSwapTransaction =
                    ProcessSwapTransactionUseCase(
                        metadataRepository = metadataRepository,
                        swapDataSource = Zc101FakeSwapDataSource(),
                    ),
                prefillSend = PrefillSendUseCase(),
            )
        return Harness(
            prior.dataSource,
            prior.repository,
            useCase,
            prior.cancelFlow,
            prior.navigationRouter,
            prior.observeClearSend,
            prior.keystone,
            prior.accountDataSource,
        )
    }

    private companion object {
        const val AWAIT_MS = 10_000L
        const val SETTLE_GRACE_MS = 500L
        const val CLEAR_WAIT_MS = 3_000L
        const val NO_CLEAR_WAIT_MS = 1_000L
        const val ROUTER_POLL_MS = 10L
        const val PAYEE = "usynthetic-payment-peer"
        const val AMOUNT = 5_000_000L
        const val MEMO = "synthetic-payment"
        const val STORED_TXID = "synthetic-txid-T"
    }
}
