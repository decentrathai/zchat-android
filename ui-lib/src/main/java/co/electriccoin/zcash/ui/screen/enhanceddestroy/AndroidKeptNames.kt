package co.electriccoin.zcash.ui.screen.enhanceddestroy

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.screen.enhanceddestroy.view.KeptNamesView
import org.koin.androidx.compose.koinViewModel

/** DESTROY-ADV-N (ZC-722): the screen "Names you kept", kept out of screenshots like the other Destroy screens. */
@Composable
fun AndroidKeptNames() {
    if (shouldSecureScreen) {
        SecureScreen()
    }
    val context = LocalContext.current
    val viewModel = koinViewModel<KeptNamesVM>()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.notice) {
        state.notice?.let { notice ->
            Toast.makeText(context, context.getString(notice), Toast.LENGTH_SHORT).show()
            viewModel.onNoticeShown()
        }
    }

    KeptNamesView(
        state = state,
        onSaveAsContact = viewModel::onSaveAsContact,
        onRemove = viewModel::onRemove,
        onEraseWholeList = viewModel::onEraseWholeList,
        onEraseConfirmed = viewModel::onEraseConfirmed,
        onEraseDismissed = viewModel::onEraseDismissed,
    )
}
