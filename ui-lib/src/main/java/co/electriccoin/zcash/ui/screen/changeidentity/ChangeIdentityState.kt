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
 * Contact notification option when changing identity.
 */
enum class NotificationOption {
    /** Send ADDR message to all contacts */
    NOTIFY_ALL,
    /** Don't notify anyone - silent regeneration */
    SILENT
}

/**
 * State for the Change Identity screen.
 */
@Immutable
data class ChangeIdentityState(
    val currentAddress: String,
    val selectedMode: IdentityMode,
    val selectedNotification: NotificationOption,
    val contactCount: Int,
    val estimatedCost: String,
    val isProcessing: Boolean,
    val showConfirmationDialog: Boolean,
    val showSuccessDialog: Boolean,
    val errorMessage: StringResource?,

    // Callbacks
    val onBack: () -> Unit,
    val onModeSelected: (IdentityMode) -> Unit,
    val onNotificationSelected: (NotificationOption) -> Unit,
    val onConfirmClick: () -> Unit,
    val onConfirmationDialogDismiss: () -> Unit,
    val onConfirmationDialogConfirm: () -> Unit,
    val onSuccessDialogDismiss: () -> Unit,
    val onErrorDismiss: () -> Unit
)
