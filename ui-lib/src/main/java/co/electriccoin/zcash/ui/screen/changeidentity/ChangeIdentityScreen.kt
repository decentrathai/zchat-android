package co.electriccoin.zcash.ui.screen.changeidentity

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun ChangeIdentityScreen() {
    val viewModel = koinViewModel<ChangeIdentityVM>()
    val state = viewModel.state.collectAsStateWithLifecycle().value

    BackHandler { state.onBack() }

    ChangeIdentityView(state = state)
}

@Serializable
data object ChangeIdentityArgs
