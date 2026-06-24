package co.electriccoin.zcash.ui.screen.swap.picker

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SwapAssetPickerScreen(args: SwapAssetPickerArgs) {
    val vm = koinViewModel<SwapAssetPickerVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    // SWAP-2: top-level back handler, always registered. When state != null the inner sheet's own BackHandler
    // wins because it lives on the nested bottom-sheet dialog's window (the focused window the OS routes back
    // to), not this outer host-dialog window — so the two never both fire for one press. When state == null —
    // the transient window while the metadata seed-key derives on a cold open — the sheet body and its
    // BackHandler aren't composed, so without this a back press would dead-end on the blank dialog
    // (dialogComposable sets dismissOnBackPress=false).
    BackHandler { vm.onBackSafe() }
    SwapAssetPickerView(state)
}

@Serializable
data class SwapAssetPickerArgs(
    val chainTicker: String?
)
