package co.electriccoin.zcash.ui.screen.walletbackup

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.common.compose.LocalActivity
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun AndroidWalletBackup(args: WalletBackup) {
    val viewModel = koinViewModel<WalletBackupViewModel> { parametersOf(args) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalActivity.current
    DisposableEffect(lifecycleOwner, activity) {
        val observer =
            LifecycleEventObserver { _, event ->
                // SEED-AUTH: leaving the screen or the app hides the words and the QR. A fold/unfold (or another
                // configuration change) recreates the activity and passes ON_STOP too, but nobody else saw the
                // screen, so the words stay.
                if (event == Lifecycle.Event.ON_STOP && !activity.isChangingConfigurations) {
                    viewModel.onScreenStopped()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler { state?.onBack?.invoke() }
    state?.let { WalletBackupView(state = it) }
}

@Serializable
data class WalletBackup(
    val isOpenedFromSeedBackupInfo: Boolean
)
