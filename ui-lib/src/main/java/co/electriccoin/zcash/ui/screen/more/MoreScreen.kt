package co.electriccoin.zcash.ui.screen.more

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun MoreScreen() {
    val vm = koinViewModel<MoreVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                // Re-read the notification-privacy preference when this screen resumes (e.g. after
                // returning from NotificationSettings) so the row reflects the current setting.
                if (event == Lifecycle.Event.ON_RESUME) {
                    vm.refreshNotificationPrivacy()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler { state.onBack() }
    MoreView(state = state)
}

@Serializable
data object MoreArgs
