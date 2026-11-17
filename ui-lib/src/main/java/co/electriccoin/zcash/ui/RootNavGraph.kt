package co.electriccoin.zcash.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import co.electriccoin.zcash.ui.common.compose.LocalActivity
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.LocalKeyboardManager
import co.electriccoin.zcash.ui.design.LocalSheetStateManager
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.enterTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.exitTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.popEnterTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.popExitTransition
import co.electriccoin.zcash.ui.design.util.LocalNavController
import co.electriccoin.zcash.ui.screen.flexa.FlexaViewModel
import co.electriccoin.zcash.ui.screen.walletstartup.StartupStorageCheck
import co.electriccoin.zcash.ui.screen.walletstartup.WalletStartupFailedArgs
import co.electriccoin.zcash.ui.screen.walletstartup.startupFailureCause
import co.electriccoin.zcash.ui.screen.warning.viewmodel.StorageCheckViewModel
import co.electriccoin.zcash.ui.screen.whatsnew.upgrade.PostUpgradeNotice
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun RootNavGraph(
    secretState: SecretState,
    walletViewModel: WalletViewModel,
    storageCheckViewModel: StorageCheckViewModel = koinViewModel(),
) {
    val keyboardManager = LocalKeyboardManager.current
    val flexaViewModel = koinViewModel<FlexaViewModel>()
    val navigationRouter = koinInject<NavigationRouter>()
    val sheetStateManager = LocalSheetStateManager.current
    val applicationStateProvider = koinInject<ApplicationStateProvider>()
    val synchronizerProvider = koinInject<SynchronizerProvider>()
    val startupStorageCheck = koinInject<StartupStorageCheck>()
    val navController = LocalNavController.current
    val activity = LocalActivity.current
    val navigator: Navigator =
        remember(
            activity,
            navController,
            flexaViewModel,
            keyboardManager,
            sheetStateManager,
            applicationStateProvider
        ) {
            NavigatorImpl(
                activity = activity,
                navController = navController,
                flexaViewModel = flexaViewModel,
                keyboardManager = keyboardManager,
                sheetStateManager = sheetStateManager,
                applicationStateProvider = applicationStateProvider
            )
        }

    LaunchedEffect(navigationRouter) {
        navigationRouter.observePipeline().collect {
            navigator.executeCommand(it)
        }
    }

    NavHost(
        navController = navController,
        startDestination = OnboardingGraph,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { enterTransition() },
        exitTransition = { exitTransition() },
        popEnterTransition = { popEnterTransition() },
        popExitTransition = { popExitTransition() }
    ) {
        this.onboardingNavGraph(
            activity = activity,
            navigationRouter = navigationRouter,
            walletViewModel = walletViewModel
        )

        this.walletNavGraph(
            storageCheckViewModel = storageCheckViewModel,
            navigationRouter = navigationRouter
        )
    }

    // Observe the live destination so the READY->main switch below can also fire when the user merely
    // navigates (e.g. presses Android-back), not only when secretState changes.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()

    // READY -> jump to the main app and CLEAR the onboarding graph. Keyed on the current destination too:
    // the "I Need ZEC -> in-app swap" path completes onboarding (secretState=READY) and forwards into the
    // main graph (SwapArgs) in the same breath, so by the time READY propagated this guard saw us already
    // inside MainAppGraph and SKIPPED the popUpTo(OnboardingGraph) -- leaving the onboarding screens
    // orphaned on the back stack. Pressing Android-back then returned to "How ZCHAT Works", where
    // "I Have ZEC - Let's Go" (completeOnboarding(), idempotent once READY) could no longer re-trigger the
    // switch = dead button. Re-evaluating on every destination change means: the moment back-nav lands on
    // an onboarding screen while already READY, we immediately bounce forward to the main app and pop the
    // onboarding graph, so the dead-button state is unreachable.
    LaunchedEffect(secretState, currentBackStackEntry) {
        if (secretState == SecretState.READY &&
            navController.currentDestination?.parent?.route != MainAppGraph::class.qualifiedName
        ) {
            keyboardManager.close()
            navController.navigate(MainAppGraph) {
                popUpTo(OnboardingGraph) {
                    inclusive = true
                }
            }
        }
    }

    // The SDK could not start the wallet (Synchronizer.new failed, e.g. a database migration after an update),
    // or (R2-3) the store with the recovery phrase or the encrypted chat stores could not be opened at start-up.
    // Instead of the crash loop this used to be, show the "do not uninstall, back up your recovery phrase"
    // screen on top of whatever is showing, once.
    val synchronizerError by synchronizerProvider.error.collectAsStateWithLifecycle()
    val startupStorage by startupStorageCheck.state.collectAsStateWithLifecycle()
    val isWalletStartupFailed = startupFailureCause(synchronizerError, startupStorage) != null
    LaunchedEffect(isWalletStartupFailed) {
        if (isWalletStartupFailed) {
            keyboardManager.close()
            navController.navigate(WalletStartupFailedArgs) {
                launchSingleTop = true
            }
        }
    }

    // Bridge 2: after an upgrade from 2.12.4 or older, What's New opens once, inside the main app (so after
    // onboarding and under the app lock, which covers it until unlock), never over the start-up error screen.
    // Closing it clears it; a kill while it shows opens it again at the next launch.
    val postUpgradeNotice = koinInject<PostUpgradeNotice>()
    val isWhatsNewPending by postUpgradeNotice.whatsNewPending.collectAsStateWithLifecycle()
    LaunchedEffect(secretState, currentBackStackEntry, isWhatsNewPending, isWalletStartupFailed) {
        if (isWhatsNewPending &&
            secretState == SecretState.READY &&
            !isWalletStartupFailed &&
            navController.currentDestination?.parent?.route == MainAppGraph::class.qualifiedName &&
            postUpgradeNotice.takeWhatsNewOpenRequest()
        ) {
            keyboardManager.close()
            navController.navigate(NavigationTargets.WHATS_NEW) {
                launchSingleTop = true
            }
        }
    }

    // NONE (wallet reset/deleted) -> show onboarding. Keyed on secretState ONLY (deliberately NOT the
    // destination): during onboarding completion there is a brief NONE-then-READY window where we are
    // momentarily inside the main graph, and reacting to that destination change here would wrongly bounce
    // the user back into onboarding.
    LaunchedEffect(secretState) {
        if (secretState == SecretState.NONE &&
            navController.currentDestination?.parent?.route != OnboardingGraph::class.qualifiedName
        ) {
            keyboardManager.close()
            navController.navigate(OnboardingGraph) {
                popUpTo(MainAppGraph) {
                    inclusive = true
                }
            }
        }
    }
}

@Serializable
data object OnboardingGraph

@Serializable
data object MainAppGraph
