package co.electriccoin.zcash.ui.screen.connectkeystone

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.KeystoneAvailability
import co.electriccoin.zcash.ui.screen.connectkeystone.model.ConnectKeystoneState
import co.electriccoin.zcash.ui.screen.connectkeystone.view.ConnectKeystoneView
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystoneSignInRequest
import org.koin.compose.koinInject

@Composable
fun AndroidConnectKeystone() {
    val navigationRouter = koinInject<NavigationRouter>()

    // K0-1 (2.13.0): connecting a new Keystone is closed. The destination stays registered (a restored back stack
    // must not crash), but it draws nothing and leaves at once.
    if (!KeystoneAvailability.NEW_CONNECTIONS) {
        LaunchedEffect(Unit) { navigationRouter.back() }
        return
    }

    BackHandler {
        navigationRouter.back()
    }

    ConnectKeystoneView(
        state =
            ConnectKeystoneState(
                onViewKeystoneTutorialClicked = {
                    // do nothing
                },
                onBackClick = {
                    navigationRouter.back()
                },
                onContinueClick = {
                    navigationRouter.forward(ScanKeystoneSignInRequest)
                },
            )
    )
}
