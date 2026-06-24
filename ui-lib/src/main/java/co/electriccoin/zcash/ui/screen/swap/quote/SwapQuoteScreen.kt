package co.electriccoin.zcash.ui.screen.swap.quote

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun SwapQuoteScreen() {
    val vm = koinViewModel<SwapQuoteVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    // SWAP-1: top-level back handler in the host dialog window, always registered. When state != null the
    // inner sheet's own BackHandler wins because it lives on the nested bottom-sheet dialog's window (the
    // focused window the OS routes back to), not this outer host-dialog window — so the two never both fire
    // for one press. When state == null the sheet body (and its BackHandler) isn't composed, so without this
    // a back press would dead-end on the blank dialog (dialogComposable sets dismissOnBackPress=false).
    // onBackSafe() falls through to cancel.
    BackHandler { vm.onBackSafe() }
    SwapQuoteView(state)
}

@Serializable
data object SwapQuoteArgs
