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
import co.electriccoin.zcash.global.MainProcess
import co.electriccoin.zcash.spackle.StrictModeCompat
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.spackle.process.ProcessNameCompat
import co.electriccoin.zcash.ui.common.provider.ActivityProvider
import co.electriccoin.zcash.ui.common.provider.CrashReportingStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ApplicationStateRepository
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.WalletSnapshotRepository
import co.electriccoin.zcash.ui.common.tex.TexSendMonitor
import co.electriccoin.zcash.ui.screen.ai.AiAccountAccess
import co.electriccoin.zcash.ui.screen.ai.AiPreferences
import co.electriccoin.zcash.ui.screen.chat.util.DestroyWipe
import co.electriccoin.zcash.ui.screen.chat.util.ResetWipe
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
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
        // ZC-692-S: a Destroy All that was cut off is finished here, before anything of the app opens a store.
        DestroyWipe.atProcessStart(this)
        ResetWipe.atProcessStart(this)

        // Track the foreground activity so app-singleton components (e.g. BiometricRepository) can
        // launch in-task instead of via the app context (which forces NEW_TASK and renders the
        // translucent biometric host over a black void — the "dark screen on Send" report).
        registerActivityLifecycleCallbacks(ActivityProvider)

        configureLogging()
        // ZC-706: the crash-report process only writes the report; the app does not start there.
        val processName = runCatching { ProcessNameCompat.getProcessName(this) }.getOrNull()
        if (!MainProcess.isMain(processName, packageName)) return
        // ZC-090-R: a Reset that was finished (or a record that was not valid) no longer needs its alarm and job.
        ResetWipe.cancelWakeAfterThePass(this)

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
        observeForegroundForAiAccount()
        get<TexSendMonitor>().startForProcess() // F3 (fix-all 2.13.0): the TEX checker and its automatic recovery.
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

    /**
     * AI-KEY-S (spec 4.3): when ZChat comes to the foreground, the saved AI key without a verdict is asked about, at
     * most once a day (the rule is AiAccountAccess's). Never from a background start, and only when the AI store's file
     * exists: a phone that never used AI gets no file and no call.
     */
    private fun observeForegroundForAiAccount() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        // The AI tab says what is wrong; a failure here must not stop the app.
                        @Suppress("TooGenericExceptionCaught", "SwallowedException")
                        try {
                            if (AiPreferences.isOnThisPhone(this@ZcashApplication)) get<AiAccountAccess>().onForeground()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Twig.warn { "The AI account check at the foreground failed: ${e.javaClass.simpleName}" }
                        }
                    }
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
