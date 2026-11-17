package co.electriccoin.zcash.ui.screen.transactionprogress

import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.configuration.api.ConfigurationProvider
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.WalletAccount
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
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-521 (fix-all 2.13.0, train T5b): a test that builds the real TransactionProgressVM has finished that view
 * model's coroutines when it unsets Dispatchers.Main.
 *
 * Since ZC-417 (5f47ef2ca) the screen's state has a child on Dispatchers.Default: `submitStateAndWaitOver`, the wait
 * for the checker's first verdict, is `flowOn(Dispatchers.Default)`. So a cancelled view model is not finished when
 * `viewModelScope.cancel()` returns. That child ends on the Default pool and then resumes its parents, one after the
 * other, through Dispatchers.Main. At 998956906 the seven test classes that build the view model end with
 * `viewModelScope.cancel()` and, at once, `Dispatchers.resetMain()`. When the pool thread loses that race it throws
 * "Dispatchers.Main was accessed when the platform dispatcher was absent and the test dispatcher was unset" out of a
 * completion handler. Nothing catches it, and kotlinx-coroutines-test reports it in the NEXT `runTest` of the JVM as
 * UncaughtExceptionsBeforeTest: DebugFaultSeamsBehaviourTest after CancelOraclePaidSendPathTest, and
 * SeedRevealNeedsDeviceAuthTest or WalletTabSweepCardTest after this package (both full runs at e79fc3d46,
 * train-T5b-2026/ZC-250).
 *
 * Wanted: each of those classes waits until its view models' coroutines have FINISHED, and only then unsets Main. One
 * shared teardown does it, `endViewModelsThenResetMain`, and no class that builds the view model unsets Main itself.
 *
 * No race in this test: the view model handed to each class's own `tearDown()` reads its submit state from a source
 * that, once cancelled, cannot finish until this test releases it (it keeps its pool thread in a `finally`). A
 * `tearDown()` that returns while that hold is on has unset Main under a view model that is still finishing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressTestsEndTheirViewModelsBeforeMainIsUnsetTest {
    @Test
    fun `each progress test class has finished its view models when its tearDown unsets Main`() {
        val leaks = DRIVEN_CLASSES.mapNotNull { leakOf(it) }

        assertTrue(
            leaks.isEmpty(),
            "a view model that is still finishing on the Default pool reaches Dispatchers.Main after its test unset " +
                "it, and the next runTest in the JVM fails with UncaughtExceptionsBeforeTest:\n" +
                leaks.joinToString("\n"),
        )
    }

    /**
     * CancelOraclePaidSendPathTest ends its view model inside one test method, where this class cannot hand it a held
     * view model. So the rule is also read from the sources, for every test file that builds the view model.
     */
    @Test
    fun `no test that builds the progress view model unsets Main by itself`() {
        val builders =
            testJava
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != OWN_FILE }
                .filter { BUILDS_THE_VIEW_MODEL.containsMatchIn(it.readText()) }
                .toList()
        val expected = DRIVEN_CLASSES.map { "${it.simpleName}.kt" } + "CancelOraclePaidSendPathTest.kt"
        assertTrue(
            builders.map { it.name }.containsAll(expected),
            "fixture: the scan of ${testJava.path} found only ${builders.map { it.name }}",
        )

        val offenders =
            builders.flatMap { file ->
                val text = file.readText()
                listOfNotNull(
                    "${file.name} calls Dispatchers.resetMain() itself".takeIf { UNSETS_MAIN.containsMatchIn(text) },
                    "${file.name} does not end through $SHARED_TEARDOWN".takeIf { "$SHARED_TEARDOWN(" !in text },
                )
            }
        assertTrue(
            offenders.isEmpty(),
            "a test that builds a TransactionProgressVM must end through $SHARED_TEARDOWN, which waits for the view " +
                "models' coroutines to finish before it unsets Main:\n" + offenders.joinToString("\n"),
        )
    }

    // --------------------------------------------------------------- one class's own setUp and tearDown

    /**
     * Runs [testClass]'s own `setUp()` and `tearDown()` around one held view model in its `vms`. Returns what leaked,
     * or null when the teardown waited for the view model to finish.
     */
    private fun leakOf(testClass: Class<*>): String? {
        val name = testClass.simpleName
        val test = testClass.getDeclaredConstructor().newInstance()
        val hold = Hold()
        val watcher = CoroutineScope(Dispatchers.Default + Job())
        var vm: TransactionProgressVM? = null
        var mainIsOurs = true
        call(test, "setUp")
        try {
            val held = heldViewModel(hold).also { vm = it }
            viewModelsOf(test) += held
            val scope = held.viewModelScope.coroutineContext.job
            watcher.launch { held.state.collect { hold.onWatched() } }
            if (!hold.awaitStarted()) fail("fixture: the progress screen never collected its submit state")

            var thrown: Throwable? = null
            mainIsOurs = false
            val tearDown =
                thread(name = "zc521-tear-down", isDaemon = true) {
                    try {
                        call(test, "tearDown")
                    } catch (e: Throwable) {
                        thrown = e
                    }
                }
            tearDown.join(HOLD_MS)
            val returnedWhileHeld = !tearDown.isAlive
            if (returnedWhileHeld) {
                // Main is unset under a view model that cannot have finished. Put a stand-in back before that view
                // model is let go, so that this test never throws into a later one.
                Dispatchers.setMain(UnconfinedTestDispatcher())
                mainIsOurs = true
            }
            hold.release()
            tearDown.join(WAIT_MS)
            val finished = runBlocking { withTimeoutOrNull(WAIT_MS) { scope.join() } } != null

            val leak =
                when {
                    thrown != null -> "threw $thrown"
                    returnedWhileHeld -> "unset Dispatchers.Main while its view model's coroutines were still finishing"
                    tearDown.isAlive -> "was still waiting ${WAIT_MS / 1_000}s after its view model could finish"
                    !finished -> "returned, and its view model's coroutines never finished"
                    else -> null
                }
            return leak?.let { "$name.tearDown() $it" }
        } finally {
            // Whatever this test still owns ends here, in the order it asks of the others: cancel, wait, unset Main.
            hold.release()
            val ours = mutableListOf(watcher)
            if (mainIsOurs) vm?.let { ours += it.viewModelScope }
            ours.forEach { it.cancel() }
            runBlocking { withTimeoutOrNull(WAIT_MS) { ours.forEach { it.coroutineContext.job.join() } } }
            if (mainIsOurs) Dispatchers.resetMain()
        }
    }

    private fun call(
        test: Any,
        method: String,
    ) {
        try {
            test.javaClass.getMethod(method).invoke(test)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun viewModelsOf(test: Any): MutableList<TransactionProgressVM> =
        try {
            val field = test.javaClass.getDeclaredField("vms").apply { isAccessible = true }
            field.get(test) as MutableList<TransactionProgressVM>
        } catch (e: NoSuchFieldException) {
            fail("${test.javaClass.simpleName} no longer keeps its view models in `vms` ($e): hand this test its list")
        }

    // --------------------------------------------------------------- the held view model

    /** Lets this test decide when the screen's cancelled submit-state source may finish. */
    private class Hold {
        private val watched = CountDownLatch(1)
        private val collecting = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun onWatched() = watched.countDown()

        fun onCollecting() = collecting.countDown()

        /**
         * True once the watcher has its first value and the submit state is being collected on the pool. The watcher
         * gets that value only after its subscription has started every part of the screen's state on its own thread,
         * so from here on no thread of this test is inside the view model while a `tearDown()` unsets Main.
         */
        fun awaitStarted(): Boolean =
            watched.await(WAIT_MS, TimeUnit.MILLISECONDS) && collecting.await(WAIT_MS, TimeUnit.MILLISECONDS)

        fun release() = released.countDown()

        /** On the pool thread, in the cancelled source's `finally`: stays there until [release]. */
        fun awaitRelease() {
            released.await(HOLD_LIMIT_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * The account source of the screen's SUBMIT STATE only, the part the screen collects on Dispatchers.Default. It
     * names no account and stays open, so the screen's state stays null. Once cancelled it cannot finish until [hold]
     * is released.
     */
    private class HeldAccounts(
        fake: AccountDataSource,
        private val hold: Hold,
    ) : AccountDataSource by fake {
        override val selectedAccount: Flow<WalletAccount?> =
            flow {
                hold.onCollecting()
                try {
                    awaitCancellation()
                } finally {
                    hold.awaitRelease()
                }
            }
    }

    /** The real view model over the real use cases, as the driven classes build it; only its submit state is held. */
    private fun heldViewModel(hold: Hold): TransactionProgressVM =
        runBlocking {
            val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
            val keystone = Zc101FakeKeystoneProposalRepository()
            val zashi = holdingRepository(syntheticZip321Proposal(PEER, amount = 1_000L, memo = "synthetic"))
            val router = Zc101RecordingNavigationRouter()
            val context = ContextWrapper(null)
            TransactionProgressVM(
                observeTransactionProposal = ObserveProposalUseCase(keystone, zashi, accounts),
                observeTransactionSubmitState =
                    ObserveTransactionSubmitStateUseCase(keystone, zashi, HeldAccounts(accounts, hold)),
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
        }

    /** The Zashi repository as the progress screen sees it: the proposal, and a send that is still being submitted. */
    private fun holdingRepository(proposal: TransactionProposal): ZashiProposalRepository {
        val proposalFlow = MutableStateFlow<TransactionProposal?>(proposal)
        val stateFlow = MutableStateFlow<SubmitProposalState?>(SubmitProposalState.Submitting)
        return Proxy.newProxyInstance(
            ZashiProposalRepository::class.java.classLoader,
            arrayOf(ZashiProposalRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getTransactionProposal" -> if (args?.lastOrNull() is Continuation<*>) proposal else proposalFlow
                "getSubmitState" -> stateFlow
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
        const val PEER = "usyntheticzc521progresspeer01"
        const val WAIT_MS = 10_000L

        /** How long a `tearDown()` is watched under the hold. One that does not wait returns at once. */
        const val HOLD_MS = 300L
        const val HOLD_LIMIT_MS = 30_000L
        const val SHARED_TEARDOWN = "endViewModelsThenResetMain"
        const val OWN_FILE = "ProgressTestsEndTheirViewModelsBeforeMainIsUnsetTest.kt"
        val BUILDS_THE_VIEW_MODEL = Regex("""\bTransactionProgressVM\(""")
        val UNSETS_MAIN = Regex("""\bresetMain\(""")

        /** The classes that keep their view models in `vms` and end them in `tearDown()`. */
        val DRIVEN_CLASSES: List<Class<*>> =
            listOf(
                OfflineWalletSendShowsNoOldErrorFirstTest::class.java,
                SentUnconfirmedIsNotSuccessTest::class.java,
                StoredFailureClearsSendFormTest::class.java,
                TexPaidPartNeverSaysFailedTest::class.java,
                TexRefusedPartRecoversByItselfTest::class.java,
                WalletSendProgressFollowsTheChainTest::class.java,
            )

        /** Unit tests run in the module directory; walk upward so a runner started elsewhere works too. */
        val testJava: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/test/java") }
                .firstOrNull { it.resolve("co/electriccoin/zcash/ui/screen/transactionprogress").isDirectory }
                ?: fail("could not locate ui-lib/src/test/java from ${start.path}")
        }
    }
}
