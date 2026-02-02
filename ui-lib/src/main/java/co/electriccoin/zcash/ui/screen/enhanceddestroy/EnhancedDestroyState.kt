package co.electriccoin.zcash.ui.screen.enhanceddestroy

import androidx.compose.runtime.Immutable

/**
 * Represents the current step in the enhanced destroy flow.
 */
enum class DestroyStep {
    /** Initial step - prompt user to confirm they want to destroy */
    CONFIRM_INTENT,
    /** PIN entry step */
    ENTER_PIN,
    /** Biometric verification step */
    BIOMETRIC_VERIFY,
    /** Option to send goodbye messages */
    GOODBYE_OPTION,
    /** Countdown before destruction */
    COUNTDOWN,
    /** Destruction in progress */
    DESTROYING,
    /** Destruction complete */
    COMPLETE
}

/**
 * State for the Enhanced Destroy screen.
 */
@Immutable
data class EnhancedDestroyState(
    val currentStep: DestroyStep = DestroyStep.CONFIRM_INTENT,
    val pinInput: String = "",
    val pinError: String? = null,
    val countdownSeconds: Int = 5,
    val sendGoodbyeMessages: Boolean = false,
    val goodbyeMessageText: String = "This contact has deleted their ZCHAT account. Goodbye!",
    val contactCount: Int = 0,
    val isBiometricAvailable: Boolean = false,
    val biometricError: String? = null,
    val isDestroyInProgress: Boolean = false,
    val onBack: () -> Unit = {},
    val onConfirmIntent: () -> Unit = {},
    val onPinChange: (String) -> Unit = {},
    val onPinSubmit: () -> Unit = {},
    val onBiometricRequest: () -> Unit = {},
    val onBiometricSkip: () -> Unit = {},
    val onToggleGoodbye: (Boolean) -> Unit = {},
    val onGoodbyeMessageChange: (String) -> Unit = {},
    val onStartCountdown: () -> Unit = {},
    val onCancelCountdown: () -> Unit = {},
    val onFinalDestroy: () -> Unit = {},
)
