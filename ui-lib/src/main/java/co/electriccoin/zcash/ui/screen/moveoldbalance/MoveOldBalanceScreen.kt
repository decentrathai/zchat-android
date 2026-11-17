package co.electriccoin.zcash.ui.screen.moveoldbalance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun MoveOldBalanceScreen() {
    val vm = koinViewModel<MoveOldBalanceVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    MoveOldBalanceView(state)
}

/** The "Move old balance" sheet: the consented move of the sealed Orchard balance to Ironwood. */
@Serializable
data object MoveOldBalanceArgs
