package co.electriccoin.zcash.ui.screen.restore.seed

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.SeedTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource

data class RestoreSeedState(
    val seed: SeedTextFieldState,
    val onBack: () -> Unit,
    val dialogButton: IconButtonState,
    val nextButton: ButtonState?,
    // QR scan options
    val onScanCameraClick: (() -> Unit)? = null,
    val onScanGalleryClick: (() -> Unit)? = null,
    val scanError: StringResource? = null,
    val onDismissScanError: (() -> Unit)? = null
)

data class RestoreSeedSuggestionsState(
    val isVisible: Boolean,
    val suggestions: List<String>,
)
