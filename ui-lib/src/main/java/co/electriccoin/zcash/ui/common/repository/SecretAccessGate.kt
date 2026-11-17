package co.electriccoin.zcash.ui.common.repository

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.BiometricPrompt

/**
 * SEED-AUTH: how ZCHAT confirms it is the owner before it shows a secret (the recovery phrase and its QR).
 *
 * Unlike [BiometricRepository.requestBiometrics], this never skips the prompt on a phone with a screen lock. Only a
 * phone with no screen lock is shown the secret without a prompt, and the screen then says so.
 */
sealed interface SecretAccessGate {
    /** The phone has no screen lock: there is nothing to ask for. */
    data object NoScreenLock : SecretAccessGate

    /** Show the system prompt with these [authenticators]. */
    data class Prompt(
        val authenticators: Int
    ) : SecretAccessGate
}

/** What a confirmed secret request found. */
enum class SecretAccess {
    /** The owner passed the phone's own prompt (biometric or screen lock). */
    CONFIRMED,

    /** The phone has no screen lock; the secret is shown with a notice that says so. */
    NO_SCREEN_LOCK
}

/** The app lock's authenticators for this Android version (the same table as `BiometricRepositoryImpl`). */
internal fun appLockAuthenticators(sdkInt: Int): Int =
    if (sdkInt == SDK_P || sdkInt == SDK_Q) {
        BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    } else {
        BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    }

/**
 * A phone with a screen lock is always prompted. When the biometric check answers anything but SUCCESS (on a phone
 * with a lock that is an OEM deviation), the prompt asks for the screen lock alone on Android 11 and up, and uses the
 * app lock's mask below that, where androidx falls back to the confirm-credential screen.
 */
internal fun secretAccessGate(
    isDeviceSecure: Boolean,
    canAuthenticateStatus: Int,
    sdkInt: Int
): SecretAccessGate =
    when {
        !isDeviceSecure -> SecretAccessGate.NoScreenLock
        canAuthenticateStatus == BIOMETRIC_SUCCESS -> SecretAccessGate.Prompt(appLockAuthenticators(sdkInt))
        sdkInt >= SDK_R -> SecretAccessGate.Prompt(DEVICE_CREDENTIAL)
        else -> SecretAccessGate.Prompt(appLockAuthenticators(sdkInt))
    }

/**
 * True when a prompt error on a secret request means the biometric stack itself is broken, so the request falls back
 * to the phone's screen-lock screen instead of failing for good (a phone that can never show its phrase can never be
 * backed up). A cancel, a timeout or a lockout stays a plain failure: the user can tap Reveal again.
 */
internal fun secretCredentialFallback(errorCode: Int): Boolean = errorCode in BROKEN_STACK_ERRORS

private val BROKEN_STACK_ERRORS =
    setOf(
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
        BiometricPrompt.ERROR_NO_SPACE,
        BiometricPrompt.ERROR_VENDOR,
        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
    )

private const val SDK_P = 28
private const val SDK_Q = 29
private const val SDK_R = 30
