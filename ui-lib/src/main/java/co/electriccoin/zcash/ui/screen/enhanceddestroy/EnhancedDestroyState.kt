package co.electriccoin.zcash.ui.screen.enhanceddestroy

import androidx.compose.runtime.Immutable
import co.electriccoin.zcash.ui.design.util.StringResource

/**
 * Represents the current step in the enhanced destroy flow.
 */
enum class DestroyStep {
    /**
     * The one screen (DESTROY-ADV-P, W-57): what is erased and what stays, and on the same screen the proof: the PIN
     * field when a Destroy PIN is set, "Authenticate" when none is and the phone has a screen lock, "Start 5-Second
     * Countdown" when the phone has neither.
     */
    CONFIRM_INTENT,

    /** The five-second countdown with CANCEL: the one window to stop. */
    COUNTDOWN,

    /**
     * Destruction in progress. The last step (ZC-720): the wipe ends the app's process, so nothing can be drawn
     * after it; what the user must know is said before the last tap.
     */
    DESTROYING
}

/**
 * State for the Enhanced Destroy screen.
 */
@Immutable
data class EnhancedDestroyState(
    val currentStep: DestroyStep = DestroyStep.CONFIRM_INTENT,
    val pinInput: String = "",
    // R4-6 (12): a StringResource, not a String, so the PIN and biometric refusals reach a Spanish
    // phone in Spanish. Nothing about the destroy flow itself depends on these values.
    val pinError: StringResource? = null,
    val countdownSeconds: Int = 5,
    val isBiometricAvailable: Boolean = false,
    // DESTROY-ADV-P: whether a Destroy PIN is set (then its field is the proof and nothing is asked after it).
    val hasPin: Boolean = false,
    val biometricError: StringResource? = null,
    val isDestroyInProgress: Boolean = false,
    // DESTROY-ADV-C (ZC-722): the saved choice, read again when the screen opens; B5: an earlier list is erased too.
    val keepsContacts: Boolean = false,
    val erasesEarlierList: Boolean = false,
    val onBack: () -> Unit = {},
    val onPinChange: (String) -> Unit = {},
    val onPinSubmit: () -> Unit = {},
    val onBiometricRequest: () -> Unit = {},
    val onStartWithoutProof: () -> Unit = {},
    val onCancelCountdown: () -> Unit = {},
    val onFinalDestroy: () -> Unit = {},
)
