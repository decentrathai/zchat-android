package co.electriccoin.zcash.ui.screen.viewingkeyexport

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.di.koinActivityViewModel

@Composable
fun AndroidViewingKeyExport() {
    val viewModel = koinActivityViewModel<ViewingKeyExportVM>()
    val state = viewModel.state.collectAsStateWithLifecycle().value

    ViewingKeyExportView(state = state)
}
