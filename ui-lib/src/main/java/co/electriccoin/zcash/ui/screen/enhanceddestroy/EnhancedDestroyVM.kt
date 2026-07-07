package co.electriccoin.zcash.ui.screen.enhanceddestroy

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EnhancedDestroyVM(
    private val navigationRouter: NavigationRouter,
    private val zchatPreferences: ZchatPreferences,
    private val destroyManager: DestroyManager,
    private val biometricManager: BiometricManager,
    private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(createInitialState())
    val state: StateFlow<EnhancedDestroyState> = _state.asStateFlow()

    private var countdownJob: Job? = null

    // Biometric authentication result callback - set by the screen
    var onBiometricSuccess: (() -> Unit)? = null
    var onBiometricFailure: ((String) -> Unit)? = null

    private fun createInitialState(): EnhancedDestroyState {
        val hasPin = zchatPreferences.hasDestroyPin()
        val isBiometricAvailable = checkBiometricAvailability()

        // Count contacts from conversation mappings
        val contactCount = zchatPreferences.getAllConversationMappings().size

        return EnhancedDestroyState(
            currentStep = DestroyStep.CONFIRM_INTENT,
            isBiometricAvailable = isBiometricAvailable,
            contactCount = contactCount,
            onBack = ::onBack,
            onConfirmIntent = ::onConfirmIntent,
            onPinChange = ::onPinChange,
            onPinSubmit = ::onPinSubmit,
            onBiometricRequest = ::onBiometricRequest,
            onToggleGoodbye = ::onToggleGoodbye,
            onGoodbyeMessageChange = ::onGoodbyeMessageChange,
            onStartCountdown = ::onStartCountdown,
            onCancelCountdown = ::onCancelCountdown,
            onFinalDestroy = ::onFinalDestroy
        )
    }

    private fun checkBiometricAvailability(): Boolean {
        val allowedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return when (biometricManager.canAuthenticate(allowedAuthenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    private fun onBack() {
        val currentState = _state.value
        when (currentState.currentStep) {
            DestroyStep.CONFIRM_INTENT -> {
                navigationRouter.back()
            }
            DestroyStep.ENTER_PIN -> {
                _state.update { it.copy(currentStep = DestroyStep.CONFIRM_INTENT, pinInput = "", pinError = null) }
            }
            DestroyStep.BIOMETRIC_VERIFY -> {
                _state.update { it.copy(currentStep = DestroyStep.ENTER_PIN) }
            }
            DestroyStep.GOODBYE_OPTION -> {
                if (currentState.isBiometricAvailable) {
                    _state.update { it.copy(currentStep = DestroyStep.BIOMETRIC_VERIFY) }
                } else {
                    _state.update { it.copy(currentStep = DestroyStep.ENTER_PIN) }
                }
            }
            DestroyStep.COUNTDOWN -> {
                onCancelCountdown()
            }
            else -> {
                // Can't go back from DESTROYING or COMPLETE
            }
        }
    }

    private fun onConfirmIntent() {
        val hasPin = zchatPreferences.hasDestroyPin()
        if (hasPin) {
            _state.update { it.copy(currentStep = DestroyStep.ENTER_PIN) }
        } else {
            // No PIN set — still require biometric. The previous "fallback to GOODBYE_OPTION"
            // for devices without biometric was a bypass: anyone with the unlocked phone could
            // destroy without ever authenticating. Now we require at least one factor: if PIN
            // wasn't set up and biometric isn't available, the destroy flow refuses to proceed.
            if (_state.value.isBiometricAvailable) {
                _state.update { it.copy(currentStep = DestroyStep.BIOMETRIC_VERIFY) }
            } else {
                _state.update {
                    it.copy(
                        biometricError = "Set a Destroy PIN in Settings → Advanced, or enroll a device " +
                            "biometric. Authentication is required before destroying all data."
                    )
                }
            }
        }
    }

    private fun onPinChange(pin: String) {
        // Only allow digits, max 8 characters
        if (pin.length <= 8 && pin.all { it.isDigit() }) {
            _state.update { it.copy(pinInput = pin, pinError = null) }
        }
    }

    private fun onPinSubmit() {
        val pin = _state.value.pinInput
        if (pin.length < 4) {
            _state.update { it.copy(pinError = "PIN must be at least 4 digits") }
            return
        }
        // verifyDestroyPinWithLockout is now suspend and dispatches PBKDF2 internally.
        viewModelScope.launch {
            val result = zchatPreferences.verifyDestroyPinWithLockout(pin)
            when (result) {
                is co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult.Success -> {
                    if (_state.value.isBiometricAvailable) {
                        _state.update { it.copy(currentStep = DestroyStep.BIOMETRIC_VERIFY, pinError = null) }
                    } else {
                        _state.update { it.copy(currentStep = DestroyStep.GOODBYE_OPTION, pinError = null) }
                    }
                }
                is co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult.Failed -> {
                    val msg = if (result.attemptsRemaining > 0) {
                        "Incorrect PIN. ${result.attemptsRemaining} attempts remaining before lockout."
                    } else {
                        "Incorrect PIN."
                    }
                    _state.update { it.copy(pinError = msg) }
                }
                is co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult.LockedOut -> {
                    val seconds = (result.remainingMillis / 1000L).coerceAtLeast(1L)
                    val msg = if (seconds >= 60) {
                        "Too many failed attempts. Try again in ${seconds / 60} minutes."
                    } else {
                        "Too many failed attempts. Try again in ${seconds} seconds."
                    }
                    _state.update { it.copy(pinError = msg) }
                }
            }
        }
    }

    private fun onBiometricRequest() {
        // This will be handled by the screen which has access to FragmentActivity
        // The screen will call handleBiometricResult after authentication
    }

    fun handleBiometricSuccess() {
        _state.update { it.copy(currentStep = DestroyStep.GOODBYE_OPTION, biometricError = null) }
    }

    fun handleBiometricFailure(message: String) {
        _state.update { it.copy(biometricError = message) }
    }

    // onBiometricSkip removed: destroy requires authentication, no bypass.

    private fun onToggleGoodbye(enabled: Boolean) {
        _state.update { it.copy(sendGoodbyeMessages = enabled) }
    }

    private fun onGoodbyeMessageChange(message: String) {
        _state.update { it.copy(goodbyeMessageText = message) }
    }

    private fun onStartCountdown() {
        _state.update { it.copy(currentStep = DestroyStep.COUNTDOWN, countdownSeconds = 5) }

        countdownJob = viewModelScope.launch {
            for (i in 5 downTo 1) {
                _state.update { it.copy(countdownSeconds = i) }
                delay(1000)
            }
            // Countdown finished - execute destroy
            onFinalDestroy()
        }
    }

    private fun onCancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _state.update { it.copy(currentStep = DestroyStep.GOODBYE_OPTION, countdownSeconds = 5) }
    }

    private fun onFinalDestroy() {
        viewModelScope.launch {
            _state.update { it.copy(currentStep = DestroyStep.DESTROYING, isDestroyInProgress = true) }

            try {
                // Step 1: Send goodbye messages if enabled
                if (_state.value.sendGoodbyeMessages && _state.value.contactCount > 0) {
                    // Note: We can't actually send messages from here without the full SDK setup
                    // The goodbye messages would need to be sent via the ChatViewModel
                    // For now, we'll just proceed with destruction
                    // TODO: Implement goodbye message sending via a use case
                    delay(500) // Small delay to show the "sending" state
                }

                // Step 2: Execute destruction
                destroyManager.destroyAll(requestUninstall = true)

                _state.update { it.copy(currentStep = DestroyStep.COMPLETE, isDestroyInProgress = false) }
            } catch (e: Exception) {
                // Even if something fails, try to destroy as much as possible
                destroyManager.destroyAll(requestUninstall = true)
                _state.update { it.copy(currentStep = DestroyStep.COMPLETE, isDestroyInProgress = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }
}
