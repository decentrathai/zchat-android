package co.electriccoin.zcash.ui.screen.viewingkeyexport

import androidx.compose.runtime.Immutable
import co.electriccoin.zcash.ui.design.util.StringResource

/**
 * Type of viewing key that can be exported.
 */
enum class ViewingKeyType {
    /** Full Viewing Key - can view all incoming and outgoing transactions */
    FVK,
    /** Incoming Viewing Key - can only view incoming transactions */
    IVK,
    /** Outgoing Viewing Key - can only view outgoing transactions */
    OVK
}

/**
 * State for a viewing key section.
 */
@Immutable
data class ViewingKeyState(
    val type: ViewingKeyType,
    val title: StringResource,
    val description: StringResource,
    val key: String,
    val isRevealed: Boolean,
    val onRevealClick: () -> Unit,
    val onCopyClick: () -> Unit
)

/**
 * State for the viewing key export screen.
 */
@Immutable
data class ViewingKeyExportState(
    val onBack: () -> Unit,
    val isLoading: Boolean = true,
    val fvkState: ViewingKeyState? = null,
    val showAdvanced: Boolean = false,
    val onToggleAdvanced: () -> Unit = {},
    val ivkState: ViewingKeyState? = null,
    val ovkState: ViewingKeyState? = null,
    val snackbarMessage: String? = null,
    val onSnackbarDismiss: () -> Unit = {}
)
