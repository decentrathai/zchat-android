package co.electriccoin.zcash.ui.common.provider

import android.app.Application
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * A3 / RD-4 safety net.
 *
 * If the SDK cannot open the wallet database — an SDK 3.0.0 migration of a real 2.12.4 wallet DB that
 * fails, or a data DB that belongs to another seed (InitializeException.SeedNotRelevant) — Synchronizer.new
 * throws inside the SDK's WalletCoordinator. WalletCoordinator runs it in its own
 * `CoroutineScope(GlobalScope.coroutineContext + Dispatchers.Main)` with no exception handler, so the
 * exception is uncaught: the process dies a few seconds after every launch with no message. A user who
 * then clears data or reinstalls loses every chat, and loses the funds if the seed was never backed up.
 *
 * The app must instead surface the failure (so it can show "wallet upgrade failed: do not uninstall, back
 * up your recovery phrase") and must not hand the exception to the thread's uncaught-exception handler,
 * which on Android kills the process.
 *
 * This drives the REAL SDK [WalletCoordinator] and the real [SynchronizerProviderImpl]. On the JVM the
 * native SDK cannot start, so Synchronizer.new fails for a JVM reason rather than a migration error; the
 * propagation path — WalletCoordinator's handler-less scope to the uncaught-exception handler — is the
 * production one. The default uncaught-exception handler is replaced by a recorder for the duration of
 * each test and restored afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SynchronizerInitFailureSurfacesErrorTest {
    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val QUIET_MS = 1_000L
        const val POLL_MS = 20L
        const val STACK_CHARS = 2_000
    }

    private lateinit var mainExecutor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private val ownedJobs = mutableListOf<Job>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    @Before
    fun setUp() {
        mainExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fake-main") }
        mainDispatcher = mainExecutor.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> uncaught += throwable }
    }

    @After
    fun tearDown() {
        ownedJobs.forEach { it.cancel() }
        runBlocking { withTimeout(TIMEOUT_MS) { ownedJobs.joinAll() } }
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        Dispatchers.resetMain()
        mainDispatcher.close()
        assertTrue("the owned Main executor did not terminate", mainExecutor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a Synchronizer_new failure surfaces a startup error instead of an uncaught crash`() =
        runBlocking {
            val walletFlow = MutableStateFlow<PersistableWallet?>(null)
            val provider = provider(walletFlow)
            val observers =
                CoroutineScope(Dispatchers.Default + SupervisorJob()).also {
                    ownedJobs += it.coroutineContext.job
                }
            try {
                // MainActivity keeps the synchronizer collected for the life of the app.
                observers.launch { provider.synchronizer.collect { } }

                // The persisted wallet becomes available (app launch after the update).
                walletFlow.value = persistedWallet()

                val settled = awaitUntil(TIMEOUT_MS) { uncaught.isNotEmpty() || provider.error.value != null }
                assertTrue(
                    "fixture failure: Synchronizer.new neither failed nor surfaced within ${TIMEOUT_MS}ms",
                    settled,
                )

                assertEquals(
                    "Synchronizer.new threw and the exception reached the thread's uncaught-exception " +
                        "handler (on Android: the process is killed on every launch, a silent crash loop): " +
                        uncaught.joinToString { it.stackTraceToString().take(STACK_CHARS) },
                    emptyList<Throwable>(),
                    uncaught.toList(),
                )
                assertNotNull(
                    "the Synchronizer.new failure was not surfaced on SynchronizerProvider.error, so the app " +
                        "cannot show the 'wallet upgrade failed: do not uninstall, back up your seed' screen",
                    provider.error.value,
                )
                assertNotNull("the surfaced error must carry the cause", provider.error.value?.cause)
            } finally {
                observers.coroutineContext.job.cancelAndJoin()
            }
        }

    @Test
    fun `an unrelated coroutine crash still reaches the uncaught-exception handler`() =
        runBlocking {
            provider(MutableStateFlow(null))

            val bug = IllegalStateException("an app bug, not a wallet start-up failure")
            val bugScope = CoroutineScope(Dispatchers.Main)
            ownedJobs += bugScope.coroutineContext.job
            bugScope.launch { throw bug }

            assertTrue(
                "a crash that has nothing to do with the SDK must not be swallowed",
                awaitUntil(TIMEOUT_MS) { uncaught.contains(bug) },
            )
        }

    @Test
    fun `an SDK exception that terminates its thread still reaches the uncaught-exception handler`() =
        runBlocking {
            provider(MutableStateFlow(null))

            val sdkFailure =
                IllegalStateException("thrown by SDK code on a plain thread").apply {
                    stackTrace =
                        arrayOf(
                            StackTraceElement("cash.z.ecc.android.sdk.internal.SomeWorker", "run", "SomeWorker.kt", 1)
                        )
                }
            // A thread that dies from the exception cannot be kept alive by swallowing it.
            Thread({ throw sdkFailure }, "dying-sdk-thread").apply { start() }.join()

            assertTrue(
                "an exception that kills its thread must be passed on, never swallowed",
                awaitUntil(QUIET_MS) { uncaught.contains(sdkFailure) },
            )
        }

    /**
     * P2f item 2 (A3 false positive): with no wallet on the phone (onboarding, or after Delete Wallet) there is
     * no wallet to fail to open. An SDK exception then is an ordinary crash and must not bring up the "Wallet
     * upgrade failed: do not uninstall" screen.
     */
    @Test
    fun `an SDK crash before any wallet exists does not show the wallet start-up error`() =
        runBlocking {
            val provider = provider(MutableStateFlow(null))

            val sdkFailure =
                IllegalStateException("thrown by SDK code while no wallet exists").apply {
                    stackTrace =
                        arrayOf(
                            StackTraceElement("cash.z.ecc.android.sdk.tool.DerivationTool", "derive", "Tool.kt", 1)
                        )
                }
            // The call kotlinx.coroutines makes for an unhandled coroutine exception on a running thread.
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), sdkFailure)

            assertNull(
                "no wallet exists, yet the SDK crash was reported as 'Wallet upgrade failed'",
                provider.error.value,
            )
            assertTrue(
                "with no wallet the crash must reach the uncaught-exception handler like any other",
                uncaught.contains(sdkFailure),
            )
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun provider(walletFlow: MutableStateFlow<PersistableWallet?>) =
        SynchronizerProviderImpl(walletCoordinator(walletFlow)).also { ownScope(it, "scope") }

    private fun ownScope(owner: Any, fieldName: String) {
        val scope =
            owner.javaClass
                .getDeclaredField(fieldName)
                .apply { isAccessible = true }
                .get(owner) as CoroutineScope
        ownedJobs += scope.coroutineContext.job
    }

    private fun walletCoordinator(walletFlow: MutableStateFlow<PersistableWallet?>) =
        WalletCoordinator(
            context = unsafeAllocate(Application::class.java) as Application,
            persistableWallet = walletFlow,
            isTorEnabled = flowOf(false),
            isExchangeRateEnabled = flowOf(false),
            accountName = "ZCHAT",
            keySource = "zashi",
        ).also { ownScope(it, "walletScope") }

    private fun persistedWallet() =
        PersistableWallet(
            network = ZcashNetwork.Mainnet,
            endpoint = LightWalletEndpoint(host = "zec.rocks", port = 443, isSecure = true),
            birthday = BlockHeight.new(3_000_000L),
            // The SDK's public test fixture, so the seed decodes and Synchronizer.new gets past it.
            seedPhrase = SeedPhrase.new(WalletFixture.Alice.seedPhrase),
            walletInitMode = WalletInitMode.ExistingWallet,
        )

    private suspend fun awaitUntil(
        timeoutMs: Long,
        predicate: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(POLL_MS)
        }
        return predicate()
    }
}

private fun unsafeAllocate(clazz: Class<*>): Any {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe =
        unsafeClass
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
    return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, clazz)!!
}
