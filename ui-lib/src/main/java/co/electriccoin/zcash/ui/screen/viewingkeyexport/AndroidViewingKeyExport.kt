package co.electriccoin.zcash.ui.screen.viewingkeyexport

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

@Composable
fun AndroidViewingKeyExport() {
    // Navigation-destination scoped (NOT activity scoped): the FVK/IVK/OVK reveal flags must reset
    // when the user leaves this screen so the biometric gate is re-applied on every revisit. An
    // activity-scoped VM would keep the keys revealed for the whole activity lifetime.
    val viewModel = koinViewModel<ViewingKeyExportVM>()
    val state = viewModel.state.collectAsStateWithLifecycle().value

    ViewingKeyExportView(state = state)
}
