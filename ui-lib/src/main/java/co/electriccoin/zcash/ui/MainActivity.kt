@file:Suppress("DEPRECATION")

package co.electriccoin.zcash.ui

import android.annotation.SuppressLint
import android.Manifest
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
import co.electriccoin.zcash.ui.service.SyncForegroundService
import co.electriccoin.zcash.ui.screen.warning.viewmodel.StorageCheckViewModel
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Twig.debug { "Activity state: Create" }

        // FLAG_SECURE is managed per-screen via SecureScreen() composable
        // in ObserveScreenSecurityFlag (reference-counting system).
        // Do NOT set it globally here — it breaks CameraX preview on scan screen.

        setAllowedScreenOrientation()

        setupSplashScreen()

        setupUiContent()

        monitorForBackgroundSync()

        requestNotificationPermission()

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
        // Accept an incoming call launched from the call notification's "Answer" action. Launching
        // the activity (vs. a BroadcastReceiver→startActivity, which Android 10+ blocks with "can't
        // open from notification") reliably surfaces the in-call UI; accept the active call here.
        if (intent.getBooleanExtra(co.electriccoin.zcash.ui.call.CallNotificationController.EXTRA_ACCEPT_CALL, false)) {
            // Clear the extra immediately so it doesn't re-trigger on config change.
            intent.removeExtra(co.electriccoin.zcash.ui.call.CallNotificationController.EXTRA_ACCEPT_CALL)
            Twig.debug { "Deep link: accept incoming call requested" }
            // On a COLD start the VoiceCallManager hasn't registered yet (the foreground service
            // registers it asynchronously in startNostrInbox), and the RING that drives it to
            // Ringing only arrives via NIP-17 afterwards — so firing acceptIncoming() synchronously
            // here is a no-op and the tap is dropped. Defer with a PLAIN lifecycleScope.launch (NOT
            // repeatOnLifecycle): a one-shot wait that survives the user backgrounding the app
            // mid-wait (repeatOnLifecycle would cancel-and-abandon it), bounded by the timeout, and
            // cancelled only on activity destroy. Waits for the manager to register AND reach Ringing.
            lifecycleScope.launch {
                withTimeoutOrNull(ACCEPT_CALL_WAIT_TIMEOUT) {
                    val manager = co.electriccoin.zcash.ui.call.CallController.current.value
                        ?: co.electriccoin.zcash.ui.call.CallController.current.first { it != null }
                    // acceptIncoming() no-ops unless the state machine is Ringing; on cold start
                    // the RING re-arrives over NOSTR shortly after the manager registers.
                    manager?.state?.first {
                        it is co.electriccoin.zcash.ui.call.VoiceCallManager.CallState.Ringing
                    }
                    runCatching { manager?.acceptIncoming() }
                } ?: Twig.debug { "Deep link: accept-call timed out (no ringing call) — ignoring" }
            }
        }
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
        authenticationViewModel.runAuthenticationRequiredCheck()
        super.onStart()
    }

    override fun onStop() {
        Twig.debug { "Activity state: Stop" }
        authenticationViewModel.persistGoToBackgroundTime(System.currentTimeMillis())
        super.onStop()
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

            SecretState.LOADING == walletViewModel.secretState.value
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
                    BlankSurface(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .imePadding()
                    ) {
                        BindCompLocalProvider {
                            Box {
                                MainContent()
                                InAppNotificationOverlay()
                                UpdateCheckOverlay()
                                co.electriccoin.zcash.ui.call.CallOverlay()
                            }
                            AuthenticationForAppAccess()
                            ScreenTimeoutHandle()
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
                }
            }
        }
    }

    companion object {
        @VisibleForTesting
        internal val SPLASH_SCREEN_DELAY = 0.seconds

        // Upper bound for how long we wait, after a cold-start "Answer" tap, for the VoiceCallManager
        // to register and the call to start ringing before giving up. Matches the call-setup horizon.
        private val ACCEPT_CALL_WAIT_TIMEOUT = 45.seconds

        // Upper bound for how long a pending share waits for the wallet to unlock/finish onboarding
        // before we give up and toast, so a share received on a locked/uninitialised app never hangs.
        private val SHARE_READY_TIMEOUT = 60.seconds
    }
}
