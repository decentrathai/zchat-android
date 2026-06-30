package co.electriccoin.zcash.app

import androidx.lifecycle.ProcessLifecycleOwner
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.crash.android.GlobalCrashReporter
import co.electriccoin.zcash.crash.android.di.CrashReportersProvider
import co.electriccoin.zcash.crash.android.di.crashProviderModule
import co.electriccoin.zcash.di.addressBookModule
import co.electriccoin.zcash.di.coreModule
import co.electriccoin.zcash.di.dataSourceModule
import co.electriccoin.zcash.di.mapperModule
import co.electriccoin.zcash.di.metadataModule
import co.electriccoin.zcash.di.providerModule
import co.electriccoin.zcash.di.repositoryModule
import co.electriccoin.zcash.di.useCaseModule
import co.electriccoin.zcash.di.viewModelModule
import co.electriccoin.zcash.spackle.StrictModeCompat
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.ActivityProvider
import co.electriccoin.zcash.ui.common.provider.CrashReportingStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ApplicationStateRepository
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.WalletSnapshotRepository
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf

class ZcashApplication : CoroutineApplication() {
    private val flexaRepository by inject<FlexaRepository>()
    private val getAvailableCrashReporters: CrashReportersProvider by inject()
    private val homeMessageCacheRepository: HomeMessageCacheRepository by inject()
    private val walletSnapshotRepository: WalletSnapshotRepository by inject()
    private val crashReportingStorageProvider: CrashReportingStorageProvider by inject()
    private val applicationStateRepository: ApplicationStateRepository by inject {
        parametersOf(ProcessLifecycleOwner.get().lifecycle)
    }
    private val synchronizerProvider: SynchronizerProvider by inject()
    private val navigateToError: NavigateToErrorUseCase by inject()

    override fun onCreate() {
        super.onCreate()

        // Track the foreground activity so app-singleton components (e.g. BiometricRepository) can
        // launch in-task instead of via the app context (which forces NEW_TASK and renders the
        // translucent biometric host over a black void — the "dark screen on Send" report).
        registerActivityLifecycleCallbacks(ActivityProvider)

        configureLogging()

        configureStrictMode()

        startKoin {
            androidLogger()
            androidContext(this@ZcashApplication)
            modules(
                coreModule,
                providerModule,
                crashProviderModule,
                dataSourceModule,
                repositoryModule,
                addressBookModule,
                metadataModule,
                useCaseModule,
                mapperModule,
                viewModelModule
            )
        }

        // Since analytics will need disk IO internally, we want this to be registered after strict
        // mode is configured to ensure none of that IO happens on the main thread
        configureAnalytics()

        flexaRepository.init()
        homeMessageCacheRepository.init()
        walletSnapshotRepository.init()
        applicationStateRepository.init()
        observeSynchronizerError()
        observeForegroundForNostrInbox()
    }

    /**
     * Revive the NOSTR inbox when the app returns to the foreground. A relay can stop delivering events
     * with no CLOSED frame (common after background/Doze suspends the keep-alive ping), so the pool's
     * socket-death reconnect never fires and inbound DMs/reactions stall until a process restart. Kicking
     * the inbox on every foreground recovers it in-process; the call is throttled + staleness-guarded
     * inside the inbox/pool, so it never churns a healthy, actively-delivering connection.
     */
    private fun observeForegroundForNostrInbox() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    co.electriccoin.zcash.ui.nostr.NostrChatBridge.refreshInbox()
                }
            }
        )
    }

    private fun observeSynchronizerError() {
        applicationScope.launch {
            synchronizerProvider.synchronizer
                .map { it?.initializationError }
                .collect {
                    if (it == Synchronizer.InitializationError.TOR_NOT_AVAILABLE) {
                        navigateToError(ErrorArgs.SynchronizerTorInitError)
                    }
                }
        }
    }

    private fun configureLogging() {
        Twig.initialize(applicationContext)
        Twig.info { "Starting application…" }

        if (!BuildConfig.DEBUG) {
            // In release builds, logs should be stripped by R8 rules
            Twig.assertLoggingStripped()
        }
    }

    private fun configureStrictMode() {
        if (BuildConfig.DEBUG) {
            StrictModeCompat.enableStrictMode(BuildConfig.IS_STRICT_MODE_CRASH_ENABLED)
        }
    }

    private fun configureAnalytics() {
        if (GlobalCrashReporter.register(this, getAvailableCrashReporters())) {
            applicationScope.launch {
                crashReportingStorageProvider.observe().collect {
                    Twig.debug { "Is crashlytics enabled: $it" }
                    if (it == true) {
                        GlobalCrashReporter.enable()
                    } else {
                        GlobalCrashReporter.disableAndDelete()
                    }
                }
            }
        }
    }
}
