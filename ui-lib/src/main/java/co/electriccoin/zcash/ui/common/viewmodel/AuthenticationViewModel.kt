package co.electriccoin.zcash.ui.common.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.BooleanPreferenceDefault
import co.electriccoin.zcash.spackle.AndroidApiVersion
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.screen.authentication.AppAccessSystemCancel
import co.electriccoin.zcash.ui.screen.authentication.AuthenticationUseCase
import co.electriccoin.zcash.ui.screen.authentication.NotificationPermissionGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val DEFAULT_INITIAL_DELAY = 0
private val AUTHENTICATE_TIMEOUT = 15.minutes.inWholeMilliseconds

class AuthenticationViewModel(
    application: Application,
    private val biometricManager: BiometricManager,
    private val getVersionInfo: GetVersionInfoProvider,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val walletViewModel: WalletViewModel,
) : AndroidViewModel(application) {
    private val executor: Executor by lazy { ContextCompat.getMainExecutor(application) }
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    private val versionInfo by lazy { getVersionInfo() }

    // This provides [allowedAuthenticators] on the current user device according to Android Compatibility Definition
    // Document (CDD). See https://source.android.com/docs/compatibility/cdd
    private val allowedAuthenticators: Int =
        when {
            // Android SDK version == 27
            (AndroidApiVersion.isExactlyO) -> Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            // Android SDK version >= 30
            (AndroidApiVersion.isAtLeastR) -> Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            // Android SDK version == 28 || 29
            (AndroidApiVersion.isExactlyP || AndroidApiVersion.isExactlyQ) ->
                Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL

            else -> error("Unsupported Android SDK version")
        }

    /**
     * Welcome animation display state
     */
    internal val showWelcomeAnimation: MutableStateFlow<Boolean> = MutableStateFlow(true)

    internal fun setWelcomeAnimationDisplayed() {
        showWelcomeAnimation.value = false
    }

    /**
     * ZC-1901: the notification ask waits for app access, once per app start; a system cancel of the app-access
     * prompt prompts again instead of failing.
     */
    internal val notificationPermissionGate = NotificationPermissionGate()
    internal val appAccessSystemCancel = AppAccessSystemCancel()

    /**
     * Authentication failed UI state
     */
    internal val authFailed: MutableStateFlow<Boolean> = MutableStateFlow(false)

    internal fun setAuthFailed() {
        authFailed.value = true
    }

    /**
     * App access authentication logic values
     */
    private val isAppAccessAuthenticationRequired: StateFlow<Boolean?> =
        booleanStateFlow(StandardPreferenceKeys.IS_APP_ACCESS_AUTHENTICATION)

    internal val appAccessAuthentication: MutableStateFlow<AuthenticationUIState> =
        MutableStateFlow(AuthenticationUIState.Initial)

    internal val appAccessAuthenticationResultState: StateFlow<AuthenticationUIState> =
        combine(
            isAppAccessAuthenticationRequired.filterNotNull(),
            appAccessAuthentication,
            walletViewModel.secretState,
        ) { required: Boolean, state: AuthenticationUIState, secretState: SecretState ->
            when {
                (!required || versionInfo.isRunningUnderTestService) -> AuthenticationUIState.NotRequired
                (state == AuthenticationUIState.Initial) -> {
                    if (secretState == SecretState.NONE) {
                        appAccessAuthentication.value = AuthenticationUIState.NotRequired
                        AuthenticationUIState.NotRequired
                    } else {
                        AuthenticationUIState.Required
                    }
                }

                else -> state
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            AuthenticationUIState.Initial
        )

    private fun resetEntireAuthenticationState() {
        appAccessAuthentication.value = AuthenticationUIState.Initial
        showWelcomeAnimation.value = true
    }

    fun runAuthenticationRequiredCheck() =
        viewModelScope.launch {
            val latestAppBackgroundedTimeMillis =
                StandardPreferenceKeys.LATEST_APP_BACKGROUND_TIME_MILLIS.getValue(standardPreferenceProvider())

            if ((System.currentTimeMillis() - latestAppBackgroundedTimeMillis) > AUTHENTICATE_TIMEOUT) {
                resetEntireAuthenticationState()
            }
        }

    fun persistGoToBackgroundTime(millis: Long) =
        viewModelScope.launch {
            StandardPreferenceKeys.LATEST_APP_BACKGROUND_TIME_MILLIS.putValue(standardPreferenceProvider(), millis)
        }

    /**
     * Authentication framework result
     */
    internal val authenticationResult: MutableStateFlow<AuthenticationResult> =
        MutableStateFlow(AuthenticationResult.None)

    internal fun resetAuthenticationResult() {
        authenticationResult.value = AuthenticationResult.None
    }

    fun authenticate(
        activity: FragmentActivity,
        initialAuthSystemWindowDelay: Duration = DEFAULT_INITIAL_DELAY.milliseconds,
        useCase: AuthenticationUseCase
    ) {
        val biometricsSupportResult = getBiometricAuthenticationSupport(allowedAuthenticators)
        Twig.debug { "Authentication getBiometricAuthenticationSupport: $biometricsSupportResult" }

        when (biometricsSupportResult) {
            BiometricSupportResult.Success -> {
                // No action needed, let user proceed to the authentication steps
            }

            else -> {
                // Otherwise biometric authentication might not be available, but users still can use the
                // device credential authentication path
            }
        }

        biometricPrompt =
            BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    /**
                     * Called when an unrecoverable error has been encountered and authentication has stopped.
                     *
                     * After this method is called, no further events will be sent for the current
                     * authentication session.
                     *
                     * @param errorCode An integer ID associated with the error.
                     * @param errorString A human-readable string that describes the error.
                     */
                    override fun onAuthenticationError(
                        errorCode: Int,
                        errorString: CharSequence
                    ) {
                        super.onAuthenticationError(errorCode, errorString)
                        Twig.warn { "Authentication error: $errorCode: $errorString" }

                        // An error code outside the documented list leaves the result as it is, as before.
                        authenticationResultForError(errorCode, errorString.toString())?.let {
                            authenticationResult.value = it
                        }
                    }

                    /**
                     * Called when a biometric (e.g. fingerprint, face, etc.) is recognized, indicating that the
                     * user has successfully authenticated.
                     *
                     * <p>After this method is called, no further events will be sent for the current
                     * authentication session.
                     *
                     * @param result An object containing authentication-related data.
                     */
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        Twig.info { "Authentication successful" }
                        authenticationResult.value = AuthenticationResult.Success
                    }

                    /**
                     * Called when a biometric (e.g. fingerprint, face, etc.) is presented but not recognized as
                     * belonging to the user.
                     */
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        Twig.error { "Authentication failed" }
                        authenticationResult.value = AuthenticationResult.Failed
                    }
                }
            )

        promptInfo =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(
                    getApplication<Application>().applicationContext.run {
                        getString(R.string.authentication_system_ui_title, getString(R.string.app_name))
                    }
                ).setSubtitle(
                    getApplication<Application>().applicationContext.run {
                        getString(
                            R.string.authentication_system_ui_subtitle,
                            getString(
                                when (useCase) {
                                    AuthenticationUseCase.AppAccess -> R.string.app_name

                                    AuthenticationUseCase.SendFunds ->
                                        R.string.authentication_use_case_send_funds
                                }
                            )
                        )
                    }
                ).setConfirmationRequired(false)
                .setAllowedAuthenticators(allowedAuthenticators)
                .build()

        // TODO [#7]: Consider integrating with the keystore to unlock cryptographic operations
        // TODO [#7]: https://github.com/Electric-Coin-Company/zashi/issues/7

        viewModelScope.launch {
            delay(initialAuthSystemWindowDelay)
            biometricPrompt.authenticate(promptInfo)
        }
    }

    private fun getBiometricAuthenticationSupport(allowedAuthenticators: Int): BiometricSupportResult =
        when (biometricManager.canAuthenticate(allowedAuthenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                Twig.debug { "Auth canAuthenticate BIOMETRIC_SUCCESS: App can authenticate using biometrics." }
                BiometricSupportResult.Success
            }

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                Twig.info {
                    "Auth canAuthenticate BIOMETRIC_ERROR_NO_HARDWARE: No biometric features available on " +
                        "this device."
                }
                BiometricSupportResult.ErrorNoHardware
            }

            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                Twig.error {
                    "Auth canAuthenticate BIOMETRIC_ERROR_HW_UNAVAILABLE: Biometric features are currently " +
                        "unavailable."
                }
                BiometricSupportResult.ErrorHwUnavailable
            }

            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Twig.warn {
                    "Auth canAuthenticate BIOMETRIC_ERROR_NONE_ENROLLED: Prompts the user to create " +
                        "credentials that your app accepts."
                }
                BiometricSupportResult.ErrorNoneEnrolled
            }

            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
                Twig.error {
                    "Auth canAuthenticate BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED: The user can't authenticate " +
                        "because a security vulnerability has been discovered with one or more hardware sensors. The " +
                        "affected sensor(s) are unavailable until a security update has addressed the issue."
                }
                BiometricSupportResult.ErrorSecurityUpdateRequired
            }

            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> {
                Twig.error {
                    "Auth canAuthenticate BIOMETRIC_ERROR_UNSUPPORTED: The user can't authenticate because " +
                        "the specified options are incompatible with the current Android version."
                }
                BiometricSupportResult.ErrorUnsupported
            }

            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> {
                Twig.error {
                    "Auth canAuthenticate BIOMETRIC_STATUS_UNKNOWN: Unable to determine whether the user can" +
                        " authenticate. This status code may be returned on older Android versions due to partial " +
                        "incompatibility with a newer API. Applications that wish to enable biometric authentication " +
                        "on affected devices may still call BiometricPrompt#authenticate() after receiving this " +
                        "status code but should be prepared to handle possible errors."
                }
                BiometricSupportResult.StatusUnknown
            }

            else -> {
                Twig.error { "Unexpected biometric framework status" }
                BiometricSupportResult.StatusUnexpected
            }
        }

    private fun booleanStateFlow(default: BooleanPreferenceDefault): StateFlow<Boolean?> =
        flow<Boolean?> {
            emitAll(default.observe(standardPreferenceProvider()))
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            null
        )
}

/**
 * Maps a BiometricPrompt error to the app's result. Most errors are handled the same; ERROR_CANCELED (the system ended
 * the prompt) is kept apart from ERROR_USER_CANCELED (ZC-1901). Null for a code outside the documented list.
 */
@SuppressLint("SwitchIntDef")
internal fun authenticationResultForError(
    errorCode: Int,
    errorString: String
): AuthenticationResult? =
    when (errorCode) {
        // The user canceled the operation.
        BiometricPrompt.ERROR_USER_CANCELED -> AuthenticationResult.Canceled
        // The operation was canceled because the biometric sensor is unavailable. This may happen when user is
        // switched, the device is locked, or another pending operation or window prevents it.
        BiometricPrompt.ERROR_CANCELED -> AuthenticationResult.SystemCanceled
        // No biometrics enrolled, or no pin, pattern or password set up: allow unauthenticated access, as before.
        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> AuthenticationResult.Success
        // The hardware is unavailable; the sensor could not process the image; timeout; not enough storage;
        // lockout (5 failed attempts, 30 s); a vendor-specific error; permanent lockout until the device credential
        // is used; no authentication hardware; the user pressed the negative button; a sensor needs a security update.
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
        BiometricPrompt.ERROR_TIMEOUT,
        BiometricPrompt.ERROR_NO_SPACE,
        BiometricPrompt.ERROR_LOCKOUT,
        BiometricPrompt.ERROR_VENDOR,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED -> AuthenticationResult.Error(errorCode, errorString)
        else -> null
    }

sealed class AuthenticationUIState {
    data object Initial : AuthenticationUIState()

    data object Required : AuthenticationUIState()

    data object NotRequired : AuthenticationUIState()

    data object Successful : AuthenticationUIState()
}

sealed class AuthenticationResult {
    data object None : AuthenticationResult()

    data object Success : AuthenticationResult()

    data class Error(
        val errorCode: Int,
        val errorMessage: String
    ) : AuthenticationResult()

    data object Canceled : AuthenticationResult()

    /**
     * ZC-1901: the system ended the prompt (BiometricPrompt.ERROR_CANCELED), for example because another window such
     * as the notification-permission dialog took focus. Not the user's choice: app access prompts again on resume.
     */
    data object SystemCanceled : AuthenticationResult()

    data object Failed : AuthenticationResult()
}

private sealed class BiometricSupportResult {
    data object Success : BiometricSupportResult()

    data object ErrorNoHardware : BiometricSupportResult()

    data object ErrorHwUnavailable : BiometricSupportResult()

    data object ErrorNoneEnrolled : BiometricSupportResult()

    data object ErrorSecurityUpdateRequired : BiometricSupportResult()

    data object ErrorUnsupported : BiometricSupportResult()

    data object StatusUnknown : BiometricSupportResult()

    data object StatusUnexpected : BiometricSupportResult()
}
