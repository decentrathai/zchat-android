package co.electriccoin.zcash.ui.screen.enhanceddestroy

import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.pluralStringRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EnhancedDestroyVM(
    private val navigationRouter: NavigationRouter,
    private val zchatPreferences: ZchatPreferences,
    private val destroyManager: DestroyManager,
    private val phoneHasScreenLock: () -> Boolean,
) : ViewModel() {
    constructor(
        navigationRouter: NavigationRouter,
        zchatPreferences: ZchatPreferences,
        destroyManager: DestroyManager,
        biometricManager: BiometricManager,
    ) : this(navigationRouter, zchatPreferences, destroyManager, { canUseScreenLock(biometricManager) })

    private val _state = MutableStateFlow(createInitialState())
    val state: StateFlow<EnhancedDestroyState> = _state.asStateFlow()

    private var countdownJob: Job? = null

    init {
        // DESTROY-ADV-C: what this Destroy will do, read from the store when the screen opens, off the main thread.
        viewModelScope.launch {
            val choice = withContext(Dispatchers.IO) { runCatching { destroyManager.readDestroyFlowChoice() }.getOrNull() }
            if (choice != null) {
                _state.update { it.copy(keepsContacts = choice.keepsContacts, erasesEarlierList = choice.erasesEarlierList) }
            }
        }
    }

    // Biometric authentication result callback - set by the screen
    var onBiometricSuccess: (() -> Unit)? = null
    var onBiometricFailure: ((String) -> Unit)? = null

    private fun createInitialState(): EnhancedDestroyState =
        EnhancedDestroyState(
            currentStep = DestroyStep.CONFIRM_INTENT,
            isBiometricAvailable = runCatching { phoneHasScreenLock() }.getOrDefault(false),
            // ZC-1042: fail closed. A PIN store that cannot be read counts as a PIN that is set: the PIN is asked, and
            // neither the screen lock nor the no-proof button can start the countdown in its place.
            hasPin = runCatching { zchatPreferences.hasDestroyPin() }.getOrDefault(true),
            onBack = ::onBack,
            onPinChange = ::onPinChange,
            onPinSubmit = ::onPinSubmit,
            onBiometricRequest = ::onBiometricRequest,
            onStartWithoutProof = ::onStartWithoutProof,
            onCancelCountdown = ::onCancelCountdown,
            onFinalDestroy = ::onFinalDestroy
        )

    private fun onBack() {
        when (_state.value.currentStep) {
            DestroyStep.CONFIRM_INTENT -> navigationRouter.back()
            DestroyStep.COUNTDOWN -> onCancelCountdown()
            // Can't go back from DESTROYING
            DestroyStep.DESTROYING -> Unit
        }
    }

    private fun onPinChange(pin: String) {
        // Only allow digits, max 8 characters
        if (pin.length <= 8 && pin.all { it.isDigit() }) {
            _state.update { it.copy(pinInput = pin, pinError = null) }
        }
    }

    private fun onPinSubmit() {
        if (!_state.value.hasPin || _state.value.currentStep != DestroyStep.CONFIRM_INTENT) return
        val pin = _state.value.pinInput
        if (pin.length < 4) {
            _state.update { it.copy(pinError = stringRes(R.string.destroy_pin_too_short)) }
            return
        }
        // verifyDestroyPinWithLockout is now suspend and dispatches PBKDF2 internally.
        viewModelScope.launch {
            // ZC-1042: a check that cannot read the store starts nothing and says so (never "incorrect").
            val result =
                try {
                    zchatPreferences.verifyDestroyPinWithLockout(pin)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(pinError = stringRes(R.string.destroy_pin_cannot_check)) }
                    return@launch
                }
            when (result) {
                is co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult.Success -> {
                    // W-57: the PIN alone starts the countdown; the phone's screen lock is not asked after it.
                    _state.update { it.copy(pinError = null) }
                    onStartCountdown()
                }
                is co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult.Failed -> {
                    val msg = if (result.attemptsRemaining > 0) {
                        pluralStringRes(R.plurals.destroy_pin_incorrect_attempts, result.attemptsRemaining, result.attemptsRemaining)
                    } else {
                        stringRes(R.string.destroy_pin_incorrect)
                    }
                    _state.update { it.copy(pinError = msg) }
                }
                is co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult.LockedOut -> {
                    val seconds = (result.remainingMillis / 1000L).coerceAtLeast(1L)
                    val msg = if (seconds >= 60) {
                        stringRes(R.string.destroy_locked_minutes, (seconds / 60).toInt())
                    } else {
                        stringRes(R.string.destroy_locked_seconds, seconds.toInt())
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

    /** The phone's screen lock, the proof when no Destroy PIN is set: the countdown starts. */
    fun handleBiometricSuccess() {
        if (_state.value.hasPin || _state.value.currentStep != DestroyStep.CONFIRM_INTENT) return
        _state.update { it.copy(biometricError = null) }
        onStartCountdown()
    }

    /** B3: a phone with neither a Destroy PIN nor a screen lock: the one button starts the countdown. */
    private fun onStartWithoutProof() {
        val state = _state.value
        if (state.hasPin || state.isBiometricAvailable || state.currentStep != DestroyStep.CONFIRM_INTENT) return
        onStartCountdown()
    }

    fun handleBiometricFailure(message: StringResource) {
        _state.update { it.copy(biometricError = message) }
    }

    // onBiometricSkip removed: destroy requires authentication, no bypass.

    private fun onStartCountdown() {
        // Cancel any countdown already running before starting a new one. A double-tap (or a tap during
        // the 300ms AnimatedContent crossfade while the old button is still hit-testable) otherwise leaked
        // a second job that onCancelCountdown() could never reach — so CANCEL stopped only the latest job
        // and the orphaned one still fired onFinalDestroy(), wiping the wallet AFTER the user cancelled.
        countdownJob?.cancel()

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
        _state.update { it.copy(currentStep = DestroyStep.CONFIRM_INTENT, countdownSeconds = 5, pinInput = "") }
    }

    private fun onFinalDestroy() {
        viewModelScope.launch {
            _state.update { it.copy(currentStep = DestroyStep.DESTROYING, isDestroyInProgress = true) }

            try {
                // R2-7 (a): the flow used to offer a farewell to all contacts here, but nothing was ever sent
                // (a TODO and a 500 ms pause, then the wipe). The option is gone until sending really works.
                // ZC-720: no step follows. The wipe ends the app's process, so a screen after it was never drawn.
                destroyManager.destroyAll(requestUninstall = true)
            } catch (e: Exception) {
                // Even if something fails, try to destroy as much as possible
                destroyManager.destroyAll(requestUninstall = true)
            }
        }
    }

    private companion object {
        fun canUseScreenLock(biometricManager: BiometricManager): Boolean =
            biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }
}
