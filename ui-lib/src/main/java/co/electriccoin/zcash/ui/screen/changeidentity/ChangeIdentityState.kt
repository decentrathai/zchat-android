package co.electriccoin.zcash.ui.screen.changeidentity

import androidx.compose.runtime.Immutable
import co.electriccoin.zcash.ui.design.util.StringResource

/**
 * Identity regeneration mode.
 */
enum class IdentityMode {
    /** Generate new diversified address from same seed - can switch back */
    DIVERSIFIED,
    /** Generate entirely new seed phrase - cannot switch back */
    FULL_RESET
}

/**
 * State for the Change Identity screen.
 */
@Immutable
data class ChangeIdentityState(
    val currentAddress: String,
    val selectedMode: IdentityMode,
    // R2-7 (a): there is no "Notify all contacts" option. Nothing sent the notice (a TODO and a debug log),
    // so contacts are never told a new address; the screen says so. The count is only shown in that line.
    val contactCount: Int,
    val isProcessing: Boolean,
    val showConfirmationDialog: Boolean,
    val showSuccessDialog: Boolean,
    val errorMessage: StringResource?,

    // Callbacks
    val onBack: () -> Unit,
    val onModeSelected: (IdentityMode) -> Unit,
    val onConfirmClick: () -> Unit,
    val onConfirmationDialogDismiss: () -> Unit,
    val onConfirmationDialogConfirm: () -> Unit,
    val onSuccessDialogDismiss: () -> Unit,
    val onErrorDismiss: () -> Unit,
    // AI-KEY-T (spec 5.3, 7.2): the text of identity_reset_ai_note that fits; null is the plain one.
    val aiResetNote: Int? = null
)
