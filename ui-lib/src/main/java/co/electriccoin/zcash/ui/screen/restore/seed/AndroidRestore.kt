package co.electriccoin.zcash.ui.screen.restore.seed

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.screen.scan.ImageUriToQrCodeConverter
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun AndroidRestoreSeed() {
    val vm = koinViewModel<RestoreSeedViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val suggestionsState = vm.suggestionsState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageUriToQrCodeConverter = remember { ImageUriToQrCodeConverter() }

    // Gallery picker launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            uri?.let {
                scope.launch {
                    val qrCode = imageUriToQrCodeConverter(context, uri)
                    vm.onGalleryResult(qrCode)
                }
            }
        }
    )

    SecureScreen()
    BackHandler(state != null) { state?.onBack?.invoke() }
    if (state != null && suggestionsState != null) {
        state?.let {
            RestoreSeedView(
                state = it.copy(
                    onScanGalleryClick = { galleryLauncher.launch("image/*") }
                ),
                suggestionsState = suggestionsState
            )
        }
    }
}

@Serializable
data object RestoreSeed
