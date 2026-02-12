package co.electriccoin.zcash.ui.screen.notificationsettings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun NotificationSettingsScreen() {
    val vm = koinViewModel<NotificationSettingsVM>()
    val state by vm.state.collectAsStateWithLifecycle()

    // Refresh permission state when resuming (user may have changed permissions in system settings)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refreshPermissions()
        }
    }

    BackHandler { state.onBack() }
    NotificationSettingsView(state = state)
}

@Serializable
data object NotificationSettingsArgs
