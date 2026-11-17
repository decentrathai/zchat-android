@file:Suppress("DEPRECATION")

package co.electriccoin.zcash.ui

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.call.FullScreenIntentPrompt
import co.electriccoin.zcash.ui.common.compose.BindCompLocalProvider
import co.electriccoin.zcash.ui.common.compose.DisableScreenTimeout
import co.electriccoin.zcash.ui.common.notification.InAppNotificationBanner
import co.electriccoin.zcash.ui.common.notification.InAppNotificationManager
import co.electriccoin.zcash.ui.common.extension.setContentCompat
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationUIState
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationViewModel
import co.electriccoin.zcash.ui.common.viewmodel.OldHomeViewModel
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.component.ConfigurationOverride
import co.electriccoin.zcash.ui.design.component.Override
import co.electriccoin.zcash.ui.design.theme.ThemeMode
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.ScreenTimeoutVM
import co.electriccoin.zcash.ui.screen.settings.datasource.AppLanguageDataSource
import co.electriccoin.zcash.ui.screen.settings.datasource.AppLocaleOverride
import co.electriccoin.zcash.ui.screen.settings.datasource.ThemePreferenceDataSource
import co.electriccoin.zcash.ui.screen.settings.model.ThemePreference
import co.electriccoin.zcash.ui.screen.authentication.AuthenticationUseCase
import co.electriccoin.zcash.ui.screen.authentication.RETRY_TRIGGER_DELAY
import co.electriccoin.zcash.ui.screen.authentication.WrapAuthentication
import co.electriccoin.zcash.ui.screen.authentication.view.AnimationConstants
import co.electriccoin.zcash.ui.screen.authentication.view.WelcomeAnimationAutostart
import co.electriccoin.zcash.ui.screen.chat.ChatDetail
import co.electriccoin.zcash.ui.screen.update.UpdateCheckOverlay
import co.electriccoin.zcash.ui.screen.scan.thirdparty.ThirdPartyScan
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.service.SyncForegroundService
import co.electriccoin.zcash.ui.screen.walletstartup.StartupStorageCheck
import co.electriccoin.zcash.ui.screen.walletstartup.isStartupStillLoading
import co.electriccoin.zcash.ui.screen.warning.viewmodel.StorageCheckViewModel
import co.electriccoin.zcash.ui.screen.whatsnew.upgrade.PostUpgradeNotice
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSource
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import co.electriccoin.zcash.work.WorkIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("TooManyFunctions")
class MainActivity : FragmentActivity() {
    private val oldHomeViewModel by viewModel<OldHomeViewModel>()

    val walletViewModel by viewModel<WalletViewModel>()

    val storageCheckViewModel by viewModel<StorageCheckViewModel>()

    internal val authenticationViewModel by viewModel<AuthenticationViewModel>()

    lateinit var navControllerForTesting: NavHostController

    val configurationOverrideFlow = MutableStateFlow<ConfigurationOverride?>(null)

    private val navigationRouter: NavigationRouter by inject()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Twig.debug { "POST_NOTIFICATIONS permission granted: $granted" }
        }

    private val themePreferenceDataSource: ThemePreferenceDataSource by inject()

    private val appLanguageDataSource: AppLanguageDataSource by inject()

    /** Below API 33: the language this activity was attached with ("" = System default). */
    private var languageTagAtAttach: String = ""

    private val startupStorageCheck: StartupStorageCheck by inject()

    private val postUpgradeNotice: PostUpgradeNotice by inject()

    private val persistableWalletProvider: PersistableWalletProvider by inject()

    private val walletSnapshotDataSource: WalletSnapshotDataSource by inject()

    /**
     * ZC-1103: below API 33 Android has no per-app language, so the activity is attached with the stored choice
     * (AppLocaleOverride.wrap); from API 33 Android applies it itself and wrap returns the context unchanged.
     */
    override fun attachBaseContext(newBase: Context) {
        if (AppLocaleOverride.isOwnOverride) languageTagAtAttach = AppLocaleOverride.storedTag(newBase)
        super.attachBaseContext(AppLocaleOverride.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Twig.debug { "Activity state: Create" }

        // FLAG_SECURE is managed per-screen via SecureScreen() composable
        // in ObserveScreenSecurityFlag (reference-counting system).
        // Do NOT set it globally here — it breaks CameraX preview on scan screen.

        setAllowedScreenOrientation()

        // R2-3: open the recovery phrase store and the chat stores (off the main thread) before anything that
        // needs them is shown; a phone-security error there becomes the start-up screen, not a crash.
        lifecycleScope.launch { startupStorageCheck.run() }

        setupSplashScreen()

        setupUiContent()

        monitorForBackgroundSync()

        observePostUpgrade()

        observeAppLanguage()

        // ZC-1901: the notification ask waits for app access (AuthenticationForAppAccess); asked here, its dialog
        // took the focus and the system ended the app-access prompt.

        handleDeepLinkIntent(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        handleDeepLinkIntent(intent)
    }

    /**
     * Handle deep link intents from notifications or external sources.
     * - Notification deep links include EXTRA_NAVIGATE_TO_CONVERSATION to open a specific chat.
     * - URI-based deep links (intent.data) open the third-party scanner.
     *
     * Uses lifecycleScope + STARTED to ensure the navigation graph is ready before
     * sending commands. This prevents cold-start deep links from being silently dropped
     * when the NavigationRouter channel has no receiver yet.
     */
    private fun handleDeepLinkIntent(intent: Intent) {
        // ZC-1938: the wallet never answers a call. Answer opens IncomingCallActivity (not exported) or the
        // CallActionReceiver broadcast; MainActivity is reachable from other apps through the exported launcher alias.
        // B8 — deep link to the Requests sheet (a contact-request notification/banner tap). Arm the
        // singleton signal BEFORE navigating so the chat-list screen opens the sheet once its request list
        // has seeded. NEVER route to ChatDetail(claimedAddress) — that unverified address is a ghost chat.
        if (intent.getBooleanExtra(SyncForegroundService.EXTRA_OPEN_REQUESTS, false)) {
            intent.removeExtra(SyncForegroundService.EXTRA_OPEN_REQUESTS)
            co.electriccoin.zcash.ui.nostr.NostrChatBridge.armOpenRequestsSheet()
            var handledReq = false
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    if (handledReq) return@repeatOnLifecycle
                    if (walletViewModel.secretState.value != SecretState.READY) {
                        walletViewModel.secretState.first { it == SecretState.READY }
                    }
                    handledReq = true
                    delay(300)
                    navigationRouter.forward(co.electriccoin.zcash.ui.screen.chat.ChatList)
                }
            }
            return
        }
        // Handle notification deep link to specific conversation
        val peerAddress = intent.getStringExtra(SyncForegroundService.EXTRA_NAVIGATE_TO_CONVERSATION)
        if (peerAddress != null) {
            // Clear the extra immediately so it doesn't re-trigger on config change
            intent.removeExtra(SyncForegroundService.EXTRA_NAVIGATE_TO_CONVERSATION)
            Twig.debug { "Deep link: navigating to conversation with ${peerAddress.take(12)}..." }
            // Defer navigation until lifecycle is STARTED (nav graph is collecting)
            // AND wallet is ready. Without a wallet, nav graph doesn't have chat routes.
            // Boolean guard prevents duplicate navigation if lifecycle rapidly cycles
            // STARTED -> STOPPED -> STARTED within the delay window.
            var handled = false
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    if (handled) return@repeatOnLifecycle
                    // Wait for wallet to be ready before navigating
                    if (walletViewModel.secretState.value != SecretState.READY) {
                        Twig.debug { "Deep link: wallet not ready, waiting..." }
                        walletViewModel.secretState.first { it == SecretState.READY }
                    }
                    handled = true
                    // Small delay to ensure the nav host has started collecting
                    delay(300)
                    navigationRouter.forward(ChatDetail(peerAddress))
                }
            }
            return
        }

        // Handle an inbound SHARE (another app shared an image/text INTO ZCHAT via the OS share sheet).
        // MUST come BEFORE the intent.data (zcash:) / third-party-scan branch: a hostile intent that
        // carries BOTH a data URI and an EXTRA_STREAM should be treated as a share, not a scan deep link.
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            handleShareIntent(intent)
            return
        }

        // Handle URI-based deep links (e.g., zcash: URIs for scanning)
        // Use same lifecycle gating as notification deep links to prevent
        // CONFLATED channel from overwriting the command before collector starts.
        if (intent.data != null) {
            var handled = false
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    if (handled) return@repeatOnLifecycle
                    if (walletViewModel.secretState.value != SecretState.READY) {
                        Twig.debug { "Deep link: wallet not ready for URI, waiting..." }
                        walletViewModel.secretState.first { it == SecretState.READY }
                    }
                    handled = true
                    delay(300)
                    navigationRouter.forward(ThirdPartyScan)
                }
            }
        }
    }

    /**
     * Handle an inbound ACTION_SEND / ACTION_SEND_MULTIPLE (the OS share sheet targeting ZCHAT).
     *
     * 1) Copy any image stream(s) into our own cache WHILE the caller's read grant is alive (the grant
     *    dies the instant the sharing Activity is gone), off the main thread. Text needs no copy.
     * 2) Stash the result in [PendingShareStore] and route to the [SharePicker] once the wallet is READY
     *    and the nav graph is collecting. If the wallet never becomes ready (shared before onboarding /
     *    while locked), we time out with a toast rather than hanging forever.
     *
     * Clear the SEND action extras up front so a config change / re-delivery doesn't double-handle them.
     */
    private fun handleShareIntent(intent: Intent) {
        // Snapshot immediately; the Intent object is reused across config changes.
        val shareIntent = Intent(intent)
        // Neutralize the LIVE launch intent so a config change (rotation/theme/locale) that re-runs
        // onCreate doesn't re-handle this same share (second SharePicker + second cache copy). Mirror
        // the removeExtra pattern the other deep-link branches use.
        intent.action = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.removeExtra(Intent.EXTRA_TEXT)
        setIntent(intent)
        lifecycleScope.launch {
            // Copy streams off the main thread (disk I/O). onWarn collects one user-facing message.
            var warning: String? = null
            val pending = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                co.electriccoin.zcash.ui.screen.chat.share.PendingShareStore.fromSendIntent(
                    context = applicationContext,
                    intent = shareIntent,
                ) { warning = it }
            }
            warning?.let { Twig.debug { "Share intent warning: $it" } }
            if (pending == null) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    warning ?: "Nothing to share into ZCHAT.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            warning?.let {
                android.widget.Toast.makeText(this@MainActivity, it, android.widget.Toast.LENGTH_LONG).show()
            }
            co.electriccoin.zcash.ui.screen.chat.share.PendingShareStore.setPending(pending)

            // Wait (bounded) for the wallet to be READY before navigating, so a share that arrives before
            // unlock/onboarding doesn't hang and doesn't get dropped by the CONFLATED nav channel. On
            // timeout, tell the user instead of leaving them staring at nothing. secretState is a
            // process-wide StateFlow (not lifecycle-bound), so awaiting it directly is safe even while the
            // activity is briefly STOPPED; the nav command itself is CONFLATED so it survives until the
            // NavHost starts collecting.
            val ready = withTimeoutOrNull(SHARE_READY_TIMEOUT) {
                if (walletViewModel.secretState.value != SecretState.READY) {
                    walletViewModel.secretState.first { it == SecretState.READY }
                }
                true
            }
            if (ready == true) {
                // Small delay so the nav host is collecting before we push (matches the deep-link paths).
                delay(300)
                navigationRouter.forward(co.electriccoin.zcash.ui.screen.chat.SharePicker)
            } else {
                co.electriccoin.zcash.ui.screen.chat.share.PendingShareStore.clearPending()
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Unlock ZCHAT first, then share again.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onStart() {
        Twig.debug { "Activity state: Start" }
        // ZC-1103, API 33+: a language chosen in Android's own app settings shows in More's Language row.
        AppLocaleOverride.runningTag(this)?.let(appLanguageDataSource::adoptRunningTag)
        authenticationViewModel.runAuthenticationRequiredCheck()
        super.onStart()
    }

    override fun onStop() {
        Twig.debug { "Activity state: Stop" }
        authenticationViewModel.persistGoToBackgroundTime(System.currentTimeMillis())
        super.onStop()
    }

    /** ZC-1103, below API 33: a new language choice recreates the activity, which attaches with it. */
    private fun observeAppLanguage() {
        if (!AppLocaleOverride.isOwnOverride) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appLanguageDataSource.appLanguage.collect { language ->
                    if (language.tag != languageTagAtAttach) recreate()
                }
            }
        }
    }

    /**
     * Sets whether the screen rotation is enabled or screen orientation is locked in the portrait mode.
     */
    @SuppressLint("SourceLockedOrientationActivity")
    private fun setAllowedScreenOrientation() {
        requestedOrientation =
            if (BuildConfig.IS_SCREEN_ROTATION_ENABLED) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
    }

    private fun setupSplashScreen() {
        val splashScreen = installSplashScreen()
        val start = SystemClock.elapsedRealtime().milliseconds

        splashScreen.setKeepOnScreenCondition {
            if (SPLASH_SCREEN_DELAY > Duration.ZERO) {
                val now = SystemClock.elapsedRealtime().milliseconds

                // This delay is for debug purposes only; do not enable for production usage.
                if (now - start < SPLASH_SCREEN_DELAY) {
                    return@setKeepOnScreenCondition true
                }
            }

            // R2-3: never hold the splash over a storage error; the start-up screen explains it.
            isStartupStillLoading(walletViewModel.secretState.value, startupStorageCheck.state.value)
        }
    }

    private fun setupUiContent() {
        // Turn off the decor fitting system windows, which allows us to handle insets,
        // including IME animations, and go edge-to-edge.
        // This also sets up the initial system bar style based on the platform theme
        enableEdgeToEdge()
        setContentCompat {
            Override(configurationOverrideFlow) {
                val isHideBalances by oldHomeViewModel.isHideBalances.collectAsStateWithLifecycle()
                val themePreference by themePreferenceDataSource.themePreference.collectAsStateWithLifecycle()
                val themeMode = themePreference.toThemeMode()
                ZcashTheme(
                    themeMode = themeMode,
                    balancesAvailable = isHideBalances == false
                ) {
                    // ZC-1467: the keyboard padding wraps the app's screens and the access screen, not the whole
                    // surface, so a keyboard left open by the chat no longer squeezes the call overlay into half the
                    // screen. The drawing order is unchanged: screens, then the call overlay, then the access screen.
                    BlankSurface(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                    ) {
                        BindCompLocalProvider {
                            Box {
                                Box(Modifier.fillMaxSize().imePadding()) {
                                    MainContent()
                                    InAppNotificationOverlay()
                                    UpdateCheckOverlay()
                                }
                                co.electriccoin.zcash.ui.call.CallOverlay()
                                // ZC-1912: Android 14+ with the full-screen intent denied: ask once to allow it.
                                FullScreenIntentPrompt()
                            }
                            Box(Modifier.fillMaxSize().imePadding(), propagateMinConstraints = true) {
                                AuthenticationForAppAccess()
                                ScreenTimeoutHandle()
                            }
                        }
                    }
                }
            }

            // Force collection to improve performance; sync can start happening while
            // the user is going through the backup flow.
            walletViewModel.synchronizer.collectAsStateWithLifecycle()
        }
    }

    @Composable
    private fun AuthenticationForAppAccess() {
        val authState = authenticationViewModel.appAccessAuthenticationResultState.collectAsStateWithLifecycle().value
        val animateAppAccess = authenticationViewModel.showWelcomeAnimation.collectAsStateWithLifecycle().value
        val authFailed = authenticationViewModel.authFailed.collectAsStateWithLifecycle().value

        // ZC-1901: ask for notifications once, after app access succeeded or is not required, so the permission
        // dialog never takes the focus from the app-access prompt.
        LaunchedEffect(authState) {
            if (authenticationViewModel.notificationPermissionGate.shouldAskNow(authState)) {
                requestNotificationPermission()
            }
        }

        if (animateAppAccess) {
            WelcomeAnimationAutostart(
                delay = AnimationConstants.INITIAL_DELAY.milliseconds,
                showAuthLogo = authFailed,
                onRetry = {
                    authenticationViewModel.resetAuthenticationResult()
                    authenticationViewModel.authenticate(
                        activity = this,
                        initialAuthSystemWindowDelay = RETRY_TRIGGER_DELAY.milliseconds,
                        useCase = AuthenticationUseCase.AppAccess
                    )
                }
            )
        }

        when (authState) {
            AuthenticationUIState.Initial -> {
                Twig.debug { "Authentication initial state" }
                // Wait for the state update
            }

            AuthenticationUIState.NotRequired -> {
                Twig.debug { "App access authentication NOT required - welcome animation only" }
                // Wait until the welcome animation finishes then mark it was shown
                LaunchedEffect(key1 = authenticationViewModel.showWelcomeAnimation) {
                    delay(AnimationConstants.together())
                    authenticationViewModel.setWelcomeAnimationDisplayed()
                }
            }

            AuthenticationUIState.Required -> {
                Twig.debug { "App access authentication required" }

                // Check and trigger app access authentication if required
                // Note that the Welcome animation is part of its logic
                WrapAuthentication(
                    onSuccess = {
                        lifecycleScope.launch {
                            // Wait until the welcome animation finishes, then mark it as presented to the user
                            delay((AnimationConstants.durationOnly()).milliseconds)
                            authenticationViewModel.appAccessAuthentication.value = AuthenticationUIState.Successful
                        }
                    },
                    onCancel = {
                        authenticationViewModel.setAuthFailed()
                    },
                    onFail = {
                        authenticationViewModel.setAuthFailed()
                    },
                    useCase = AuthenticationUseCase.AppAccess
                )
            }

            AuthenticationUIState.Successful -> {
                Twig.debug { "Authentication successful - entering the app" }
                // No action is needed - the main app content is laid out now
            }
        }
    }

    @Composable
    private fun MainContent() {
        val secretState by walletViewModel.secretState.collectAsStateWithLifecycle()
        RootNavGraph(secretState, walletViewModel, storageCheckViewModel)
    }

    @Composable
    private fun InAppNotificationOverlay() {
        val inAppNotificationManager: InAppNotificationManager = org.koin.compose.koinInject()
        InAppNotificationBanner(
            manager = inAppNotificationManager,
            onTap = { notif ->
                if (notif.openRequests) {
                    // B8 — request banner: open the Requests sheet on the chat list, never a ghost ChatDetail.
                    co.electriccoin.zcash.ui.nostr.NostrChatBridge.armOpenRequestsSheet()
                    navigationRouter.forward(co.electriccoin.zcash.ui.screen.chat.ChatList)
                } else {
                    navigationRouter.forward(ChatDetail(notif.peerAddress))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
        )
    }

    @Composable
    private fun ScreenTimeoutHandle() {
        val vm = koinViewModel<ScreenTimeoutVM>()
        val isScreenTimeoutDisabled by vm.isScreenTimeoutDisabled.collectAsStateWithLifecycle()

        if (isScreenTimeoutDisabled == true) {
            DisableScreenTimeout()
        }
    }

    /**
     * Bridge 2: once the start-up storage check has passed, stamp the version and decide whether What's New
     * and the catch-up bar are due (an upgrade from 2.12.4 or older with a wallet); then feed wallet
     * snapshots so the SDK's fully-scanned signal clears the bar. RootNavGraph opens What's New.
     */
    private fun observePostUpgrade() {
        lifecycleScope.launch(Dispatchers.IO) {
            walletViewModel.secretState.first { it != SecretState.LOADING }
            val walletExists =
                try {
                    persistableWalletProvider.getPersistableWallet() != null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Unknown this launch: decide nothing and store nothing; the next launch decides.
                    Twig.warn(e) { "Post-upgrade check skipped: the wallet could not be read" }
                    return@launch
                }
            try {
                postUpgradeNotice.onLaunch(walletExists)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.warn(e) { "Post-upgrade state could not be read or stored" }
                return@launch
            }
            walletSnapshotDataSource.observe().filterNotNull().collect { snapshot ->
                postUpgradeNotice.onWalletSnapshot(snapshot.status, snapshot.progress.decimal)
            }
        }
    }

    private fun monitorForBackgroundSync() {
        val isEnableBackgroundSyncFlow =
            run {
                val isSecretReadyFlow = walletViewModel.secretState.map { it == SecretState.READY }
                val isBackgroundSyncEnabledFlow = oldHomeViewModel.isBackgroundSyncEnabled.filterNotNull()

                isSecretReadyFlow.combine(isBackgroundSyncEnabledFlow) { isSecretReady, isBackgroundSyncEnabled ->
                    isSecretReady && isBackgroundSyncEnabled
                }
            }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                isEnableBackgroundSyncFlow.collect { isEnableBackgroundSync ->
                    if (isEnableBackgroundSync) {
                        WorkIds.enableBackgroundSynchronization(application)
                    } else {
                        WorkIds.disableBackgroundSynchronization(application)
                    }
                }
            }
        }

        // Start foreground sync service when wallet is ready
        lifecycleScope.launch {
            walletViewModel.secretState.collect { secretState ->
                if (secretState == SecretState.READY) {
                    Twig.debug { "Wallet ready - starting foreground sync service" }
                    co.electriccoin.zcash.ui.service.SyncForegroundService.start(applicationContext)
                    // Phone finding of train T6b: Android can stop the service in the background; the app's return to the
                    // foreground (NostrChatBridge.refreshInbox) starts it again through this while a wallet is ready.
                    val appContext = applicationContext
                    NostrChatBridge.registerInboxStarter {
                        runCatching { SyncForegroundService.start(appContext) }
                            .onFailure { Twig.warn(it) { "Relay inbox not started at foreground" } }
                    }
                } else {
                    NostrChatBridge.unregisterInboxStarter()
                }
            }
        }
    }

    companion object {
        @VisibleForTesting
        internal val SPLASH_SCREEN_DELAY = 0.seconds

        // Upper bound for how long a pending share waits for the wallet to unlock/finish onboarding
        // before we give up and toast, so a share received on a locked/uninitialised app never hangs.
        private val SHARE_READY_TIMEOUT = 60.seconds
    }
}
