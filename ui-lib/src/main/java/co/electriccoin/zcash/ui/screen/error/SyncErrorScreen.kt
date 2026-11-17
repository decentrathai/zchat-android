@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.error

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.NavigationRouter
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncErrorScreen() {
    val navigateToErrorUseCase = koinInject<NavigateToErrorUseCase>()
    // On process death Nav can restore this destination after the transient error args are cleared, so
    // requireCurrentArgs() returns the General fallback (NOT a SyncError). Cast SAFELY and pop, rather
    // than hard-casting General -> SyncError (a launch ClassCastException) — this matters more now that
    // EVERY wallet-sync error routes to this screen.
    val syncArgs = navigateToErrorUseCase.requireCurrentArgs() as? ErrorArgs.SyncError
    if (syncArgs == null) {
        val navigationRouter = koinInject<NavigationRouter>()
        LaunchedEffect(Unit) { navigationRouter.back() }
        return
    }
    val vm = koinViewModel<SyncErrorVM> { parametersOf(syncArgs) }
    val state by vm.state.collectAsStateWithLifecycle()
    SyncErrorView(state = state)
}

@Serializable
data object SyncErrorArgs
