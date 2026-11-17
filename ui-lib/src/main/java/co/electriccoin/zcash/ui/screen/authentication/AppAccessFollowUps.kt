package co.electriccoin.zcash.ui.screen.authentication

import androidx.biometric.BiometricPrompt
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationUIState

/**
 * ZC-1901: the notification-permission dialog is a window of its own. Shown while the app-access prompt is up, it
 * takes the focus and the system ends the prompt. So the app asks for notifications once, only when app access has
 * succeeded or is not required.
 */
internal class NotificationPermissionGate {
    private var asked = false

    @Synchronized
    fun shouldAskNow(authState: AuthenticationUIState): Boolean {
        if (asked) return false
        val ready = authState == AuthenticationUIState.Successful || authState == AuthenticationUIState.NotRequired
        if (ready) asked = true
        return ready
    }
}

/**
 * ZC-1901: what app access does when the system ended its prompt (BiometricPrompt.ERROR_CANCELED). The user did not
 * cancel, so there is no toast and no "canceled" state:
 *  - app not in front: prompt again on the next resume;
 *  - app in front: prompt again at once, one time per resume;
 *  - a second system cancel in front before the next resume: show the tap-to-retry logo, so a prompt the system keeps
 *    ending can't loop.
 */
internal class AppAccessSystemCancel {
    enum class Action { RetryOnResume, RetryNow, ShowRetry }

    private var retryOnResume = false
    private var retriedSinceResume = false

    @Synchronized
    fun onSystemCanceled(isResumed: Boolean): Action =
        when {
            !isResumed -> {
                retryOnResume = true
                Action.RetryOnResume
            }
            !retriedSinceResume -> {
                retriedSinceResume = true
                Action.RetryNow
            }
            else -> Action.ShowRetry
        }

    /** App access succeeded: nothing is pending for a later lock. */
    @Synchronized
    fun reset() {
        retryOnResume = false
        retriedSinceResume = false
    }

    /** Call on every resume; true when the app-access prompt should be shown again now. */
    @Synchronized
    fun onResume(): Boolean {
        retriedSinceResume = false
        val retry = retryOnResume
        retryOnResume = false
        return retry
    }
}

/**
 * ZC-1965: whether the app-access prompt's cancel shows "Authentication canceled". Home closes the prompt as a user
 * dismissal (ERROR_USER_CANCELED) while the app is leaving the foreground: the error comes just before or after onPause
 * (about 370 ms before it on the Seeker). So the toast shows only for the user's own cancel with the app in front:
 * resumed at the cancel and not paused within [LEAVING_WINDOW_MS] after it. ERROR_CANCELED never toasts (ZC-1901).
 */
internal object AppAccessCancelToast {
    /** How long after a cancel a pause still means the app was leaving (Home); the user's toast waits this long. */
    const val LEAVING_WINDOW_MS: Long = 700L

    fun shows(
        errorCode: Int,
        resumedAtCancel: Boolean,
        pausedAfterCancelMs: Long?,
    ): Boolean =
        errorCode == BiometricPrompt.ERROR_USER_CANCELED &&
            resumedAtCancel &&
            (pausedAfterCancelMs == null || pausedAfterCancelMs > LEAVING_WINDOW_MS)
}
