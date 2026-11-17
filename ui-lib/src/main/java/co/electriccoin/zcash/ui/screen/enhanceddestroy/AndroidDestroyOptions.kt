package co.electriccoin.zcash.ui.screen.enhanceddestroy

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.screen.enhanceddestroy.view.DestroyOptionsView
import org.koin.androidx.compose.koinViewModel

/** DESTROY-ADV-C (ZC-722): the screen "What Destroy All erases", kept out of screenshots like the Destroy screens. */
@Composable
fun AndroidDestroyOptions() {
    if (shouldSecureScreen) {
        SecureScreen()
    }
    val context = LocalContext.current
    val viewModel = koinViewModel<DestroyOptionsVM>()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.notice) {
        state.notice?.let { notice ->
            Toast.makeText(context, context.getString(notice), Toast.LENGTH_LONG).show()
            viewModel.onNoticeShown()
        }
    }

    DestroyOptionsView(
        state = state,
        onBack = { viewModel.onBack() },
        onToggleAdvanced = { viewModel.onToggleAdvanced() },
        onChooseEverything = { viewModel.onChooseEverything() },
        onChooseKeep = { viewModel.onChooseKeep() },
        onTickContacts = { viewModel.onTickContacts(it) },
        onSave = { viewModel.onSave() },
        onSetPin = { viewModel.onSetPin() },
        onPinChange = { viewModel.onPinChange(it) },
        onPinConfirm = { viewModel.onPinConfirm() },
        onPinDismiss = { viewModel.onPinDismiss() },
    )
}
