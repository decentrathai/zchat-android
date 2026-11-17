package co.electriccoin.zcash.ui.screen.walletstartup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.EncryptedPreferencesUnavailableException
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProviderImpl
import co.electriccoin.zcash.ui.common.provider.SynchronizerStartupFailureGuard
import co.electriccoin.zcash.ui.common.provider.WalletBackupConsentStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupRemindMeCountStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupRemindMeTimestampStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.usecase.GetPersistableWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.OnUserSavedWalletBackupUseCase
import co.electriccoin.zcash.ui.common.usecase.RemindWalletBackupLaterUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletBackupData
import co.electriccoin.zcash.ui.common.usecase.WalletBackupMessageUseCase
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackup
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupState
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * R2-3 (a), p2-1c123d30c227 — the store that holds the recovery phrase fails to open with a phone-security
 * (Keystore) error.
 *
 * AndroidPreferenceProvider.newEncrypted builds the Keystore master key and opens the EncryptedSharedPreferences
 * file with no handling. The first reader of the wallet at start-up is the SDK's WalletCoordinator (its own
 * GlobalScope + Main scope, no exception handler), so the raw GeneralSecurityException / ProviderException /
 * IOException goes to the thread's uncaught-exception handler. The RD-4 guard only intercepts exceptions thrown
 * by Zcash SDK code, so Android killed ZCHAT on every launch while the Keystore failed, with no message; the
 * phrase backup screen crashed the same way.
 *
 * Required: the failure is a typed "seed store unreadable" result that shows the P2f start-up screen (with Try
 * again) instead of a crash, on every launch; the phrase backup says the phrase cannot be read right now; and
 * the seed store file is never cleared, renamed or rewritten on that path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeedStoreKeystoreErrorTest {
    private val keystoreErrors: List<() -> Exception> =
        listOf(
            { GeneralSecurityException("Keystore operation failed") },
            { ProviderException("Keystore operation failed") },
            { IOException("keyset read failed") },
            { KeyStoreException("the master key exists but is unusable") },
        )

    // What the encrypted file looks like on disk: the Tink keysets and ciphertext, never the phrase itself.
    private val seedStoreOnDisk: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-1",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-2",
            "AQx1-encrypted-key" to "AUz9-encrypted-wallet",
        )

    /** One launch of ZCHAT against the same phone (same [context]), whose Keystore fails with [error]. */
    private inner class Launch(
        val context: RecordingContext,
        val filename: String,
        error: () -> Exception,
    ) {
        val seedStore =
            EncryptedPreferenceProvider(context, filename) { ctx, name ->
                // Like Tink: read the raw file for its keyset, then fail on the Keystore.
                ctx.getSharedPreferences(name, 0).all
                throw error()
            }
        val walletProvider: PersistableWalletProvider = PersistableWalletProviderImpl(seedStore)
    }

    private fun phoneWithSeedStore(filename: String): RecordingContext =
        RecordingContext().apply {
            (getSharedPreferences(filename, 0) as RecordingPrefs).entries.putAll(seedStoreOnDisk)
        }

    private fun assertSeedStoreUntouched(
        context: RecordingContext,
        filename: String,
        launch: String,
    ) {
        val file = context.files.getValue(filename)
        assertEquals("$launch: the seed store file was edited", 0, file.editCalls)
        assertEquals("$launch: the seed store content changed", seedStoreOnDisk, file.entries)
        assertTrue("$launch: a preferences file was deleted: ${context.deletedFiles}", context.deletedFiles.isEmpty())
        assertTrue("$launch: a preferences file was moved: ${context.movedFiles}", context.movedFiles.isEmpty())
        assertEquals("$launch: another preferences file was written", setOf(filename), context.files.keys)
    }

    @Test
    fun `a Keystore error on the seed store is a typed start-up result with Try again on three launches`() =
        runBlocking {
            keystoreErrors.forEach { error ->
                val filename = "seed-store-${UUID.randomUUID()}"
                val phone = phoneWithSeedStore(filename)
                repeat(3) { index ->
                    val name = "${error().javaClass.simpleName} launch ${index + 1}"
                    val launch = Launch(phone, filename, error)
                    val check =
                        StartupStorageCheck(
                            openSeedStore = { launch.walletProvider.getPersistableWallet() },
                            openChatStores = {},
                            ioDispatcher = Dispatchers.Unconfined,
                        )

                    val outcome =
                        recordingCrashes { crashes ->
                            runCatching { check.run() }.also { assertTrue("$name: crashed: $crashes", crashes.isEmpty()) }
                        }

                    assertTrue("$name: opening the seed store threw ${outcome.exceptionOrNull()}", outcome.isSuccess)
                    val state = check.state.value
                    assertTrue("$name: expected SeedStoreUnreadable, got $state", state is StartupStorageCheck.State.SeedStoreUnreadable)
                    val cause = (state as StartupStorageCheck.State.SeedStoreUnreadable).cause
                    assertTrue(
                        "$name: the cause is not the typed seed-store error: $cause",
                        cause.causeChain().any { it is EncryptedPreferencesUnavailableException },
                    )
                    assertSame(
                        "$name: the start-up screen is not shown for it",
                        cause,
                        startupFailureCause(synchronizerError = null, storage = state),
                    )
                    assertEquals(
                        "$name: the main app must not open over an unreadable seed store",
                        SecretState.LOADING,
                        gatedSecretState(SecretState.READY, state),
                    )
                    assertEquals(
                        "$name: the start-up screen must explain the unreadable phrase and offer Try again",
                        WalletStartupRepair.Offer.SeedStoreUnreadable,
                        repair(launch.walletProvider).offerFor(cause),
                    )
                    assertSeedStoreUntouched(phone, filename, name)
                }
            }
        }

    @Test
    fun `the SDK reading an unreadable seed store at start-up shows the start-up screen instead of crashing`() =
        runBlocking {
            val filename = "seed-store-${UUID.randomUUID()}"
            val phone = phoneWithSeedStore(filename)
            repeat(3) { index ->
                val launch = Launch(phone, filename) { ProviderException("Keystore operation failed") }
                // What WalletCoordinator does on every launch: collect the saved wallet.
                val thrown = runCatching { launch.walletProvider.persistableWallet.first() }.exceptionOrNull()
                assertNotNull("launch ${index + 1}: reading the unreadable seed store did not fail", thrown)

                val passedOn = mutableListOf<Throwable>()
                val reported = mutableListOf<Throwable>()
                val guard =
                    SynchronizerStartupFailureGuard(
                        delegate = { _, e -> passedOn += e },
                        isSynchronizerStarting = { true },
                        onStartupFailure = { reported += it },
                    )
                guard.uncaughtException(Thread.currentThread(), thrown!!)

                assertTrue(
                    "launch ${index + 1}: the seed-store error went to Android's crash handler (the app is killed)",
                    passedOn.isEmpty(),
                )
                assertEquals("launch ${index + 1}: the start-up screen was not told", listOf(thrown), reported)
                assertSeedStoreUntouched(phone, filename, "launch ${index + 1}")
            }
        }

    @Test
    fun `Back up recovery phrase says the phrase cannot be read right now instead of crashing`() {
        val main = FakeMainThread()
        try {
            val filename = "seed-store-${UUID.randomUUID()}"
            val phone = phoneWithSeedStore(filename)
            val launch = Launch(phone, filename) { KeyStoreException("the master key exists but is unusable") }
            recordingCrashes { crashes ->
                val viewModel = main.viewModel { backupViewModel(launch.walletProvider) }

                val state: WalletBackupState? =
                    runBlocking { withTimeoutOrNull(5_000) { viewModel.state.first { it != null } } }

                assertTrue("the phrase backup crashed: $crashes", crashes.isEmpty())
                assertNotNull("the phrase backup never showed anything (endless spinner)", state)
                assertNotNull("the phrase backup must say the phrase cannot be read right now", state!!.unreadableMessage)
                assertFalse("the reveal button must not wait forever", state.primaryButton.isLoading)
                assertFalse("there is no phrase to reveal", state.primaryButton.isEnabled)
            }
            assertSeedStoreUntouched(phone, filename, "backup screen")
        } finally {
            main.stop()
        }
    }

    /**
     * Round 2, run 2 at fbc7fc344: the backup-screen test above left its view model running on the stand-in main
     * thread after it unset Dispatchers.Main. Work still queued on that thread then reached Dispatchers.Main with
     * nothing behind it, and kotlinx-coroutines-test reported the crash at the start of the next runTest in the
     * JVM (WalletTabSweepCardTest.the_dismissal_key_is_per_account_and_stable). When the stand-in main thread
     * stops, no coroutine of this test may be left alive and no work may be left on that thread.
     */
    @Test
    fun `the backup screen test leaves nothing running on the main thread when it ends`() {
        val main = FakeMainThread()
        val viewModel: WalletBackupViewModel
        try {
            val filename = "seed-store-${UUID.randomUUID()}"
            val launch = Launch(phoneWithSeedStore(filename), filename) { KeyStoreException("unusable") }
            viewModel = main.viewModel { backupViewModel(launch.walletProvider) }
            runBlocking { withTimeoutOrNull(5_000) { viewModel.state.first { it != null } } }
        } finally {
            main.stop()
        }
        assertFalse(
            "the backup screen's coroutines were still alive after the test unset Dispatchers.Main",
            viewModel.viewModelScope.coroutineContext.job.isActive,
        )
        // Cancelled is not enough: a cancelled flowOn(IO) producer still finishing on the IO pool resumes its
        // parent on Dispatchers.Main when it completes (round 3, run 1 at 9382d0fa5, coroutine ProducerCoroutine).
        assertTrue(
            "the backup screen's coroutines had not finished when the test unset Dispatchers.Main",
            viewModel.viewModelScope.coroutineContext.job.isCompleted,
        )
        assertTrue(
            "work was still queued on the stand-in main thread after the test unset Dispatchers.Main",
            main.isDrained,
        )
    }

    /**
     * A stand-in main thread for one test: Dispatchers.Main runs on it from construction until [stop]. View models
     * made with [viewModel] belong to it.
     */
    private class FakeMainThread {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "seed-store-test-main") }
        private val dispatcher = executor.asCoroutineDispatcher()
        private val viewModels = ViewModelStore()
        private val scopes = mutableListOf<Job>()

        init {
            Dispatchers.setMain(dispatcher)
        }

        fun <T : ViewModel> viewModel(create: () -> T): T {
            val made = create()
            scopes += made.viewModelScope.coroutineContext.job
            return ViewModelProvider(
                viewModels,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <V : ViewModel> create(modelClass: Class<V>): V = made as V
                },
            )[made.javaClass]
        }

        val isDrained: Boolean get() = executor.isTerminated

        /**
         * Ends every view model made here (cancelling their coroutines), waits until those coroutines have
         * finished (including children still completing on the IO pool, which resume their parents on
         * Dispatchers.Main), lets the thread finish what is queued, and only then unsets Dispatchers.Main, so
         * nothing from this test can reach Dispatchers.Main after it is unset.
         */
        fun stop() {
            try {
                viewModels.clear()
                runBlocking {
                    withTimeout(STOP_TIMEOUT_SECONDS * 1_000) { scopes.joinAll() }
                }
                executor.shutdown()
                check(executor.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "the stand-in main thread was still busy ${STOP_TIMEOUT_SECONDS}s after its view models ended"
                }
            } finally {
                Dispatchers.resetMain()
                dispatcher.close()
            }
        }

        private companion object {
            const val STOP_TIMEOUT_SECONDS = 10L
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────────────────────────

    private fun repair(walletProvider: PersistableWalletProvider) =
        WalletStartupRepair(
            persistableWalletProvider = walletProvider,
            walletRestoringStateProvider = NoRestoringState(),
            walletDataDatabase =
                object : WalletDataDatabase {
                    override fun setAside(network: ZcashNetwork): Boolean = error("the wallet data must not be touched")
                },
            appRestarter = {},
        )

    private fun backupViewModel(walletProvider: PersistableWalletProvider): WalletBackupViewModel {
        val navigationRouter = unusedDependency<NavigationRouter>()
        return WalletBackupViewModel(
            walletBackupMessageUseCase =
                object : WalletBackupMessageUseCase {
                    override fun observe(): Flow<WalletBackupData> = flowOf()
                },
            getPersistableWallet = GetPersistableWalletUseCase(walletProvider),
            args = WalletBackup(isOpenedFromSeedBackupInfo = false),
            navigationRouter = navigationRouter,
            onUserSavedWalletBackup =
                OnUserSavedWalletBackupUseCase(navigationRouter, unusedDependency<WalletBackupFlagStorageProvider>()),
            remindWalletBackupLater =
                RemindWalletBackupLaterUseCase(
                    navigationRouter,
                    unusedDependency<WalletBackupConsentStorageProvider>(),
                    unusedDependency<WalletBackupRemindMeCountStorageProvider>(),
                    unusedDependency<WalletBackupRemindMeTimestampStorageProvider>(),
                ),
            biometricRepository = unusedDependency<BiometricRepository>(),
        )
    }

    private class NoRestoringState : WalletRestoringStateProvider {
        override suspend fun get(): WalletRestoringState = WalletRestoringState.NONE

        override suspend fun store(value: WalletRestoringState) = Unit

        override fun observe(): Flow<WalletRestoringState> = flowOf(WalletRestoringState.NONE)

        override suspend fun clear() = Unit
    }
}
