package co.electriccoin.zcash.ui.screen.authentication

import androidx.biometric.BiometricPrompt
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationResult
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationUIState
import co.electriccoin.zcash.ui.common.viewmodel.authenticationResultForError
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-1901 (owner W-148): the decisions behind the first-launch fix.
 *  - BiometricPrompt's ERROR_CANCELED (the system took the prompt away, for example the notification-permission
 *    dialog) is [AuthenticationResult.SystemCanceled]; ERROR_USER_CANCELED stays [AuthenticationResult.Canceled]
 *    and the negative button stays an error.
 *  - A system cancel while the app is in the background prompts again on the next resume; while the app is in
 *    front it prompts again at once, one time per resume; a second one shows the tap-to-retry logo (no toast).
 *  - Notifications are asked for once, only after app access succeeded or is not required.
 */
class Zc1901SystemCancelAndNotificationGateTest {
    @Test
    fun error_canceled_is_a_system_cancel_and_user_cancel_stays_a_cancel() {
        assertEquals(AuthenticationResult.SystemCanceled, authenticationResultForError(BiometricPrompt.ERROR_CANCELED, "x"))
        assertEquals(AuthenticationResult.Canceled, authenticationResultForError(BiometricPrompt.ERROR_USER_CANCELED, "x"))
        assertEquals(
            AuthenticationResult.Error(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "Cancel"),
            authenticationResultForError(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "Cancel")
        )
        assertEquals(
            AuthenticationResult.Error(BiometricPrompt.ERROR_LOCKOUT, "locked"),
            authenticationResultForError(BiometricPrompt.ERROR_LOCKOUT, "locked")
        )
        assertEquals(AuthenticationResult.Success, authenticationResultForError(BiometricPrompt.ERROR_NO_BIOMETRICS, "x"))
        assertEquals(AuthenticationResult.Success, authenticationResultForError(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, "x"))
    }

    @Test
    fun a_system_cancel_in_the_background_prompts_again_on_resume_only_once() {
        val retry = AppAccessSystemCancel()
        assertEquals(AppAccessSystemCancel.Action.RetryOnResume, retry.onSystemCanceled(isResumed = false))
        assertTrue(retry.onResume(), "the first resume after a background cancel prompts again")
        assertFalse(retry.onResume(), "a later resume does not prompt again")
    }

    @Test
    fun a_system_cancel_in_front_retries_once_per_resume_then_shows_the_retry_logo() {
        val retry = AppAccessSystemCancel()
        assertFalse(retry.onResume())
        assertEquals(AppAccessSystemCancel.Action.RetryNow, retry.onSystemCanceled(isResumed = true))
        assertEquals(AppAccessSystemCancel.Action.ShowRetry, retry.onSystemCanceled(isResumed = true))
        assertFalse(retry.onResume())
        assertEquals(AppAccessSystemCancel.Action.RetryNow, retry.onSystemCanceled(isResumed = true))
    }

    @Test
    fun notifications_are_asked_once_and_only_after_app_access() {
        val gate = NotificationPermissionGate()
        assertFalse(gate.shouldAskNow(AuthenticationUIState.Initial))
        assertFalse(gate.shouldAskNow(AuthenticationUIState.Required), "asked while the app-access prompt is up")
        assertTrue(gate.shouldAskNow(AuthenticationUIState.Successful))
        assertFalse(gate.shouldAskNow(AuthenticationUIState.Successful), "asked twice")
        assertFalse(gate.shouldAskNow(AuthenticationUIState.NotRequired), "asked twice")

        val noLock = NotificationPermissionGate()
        assertTrue(noLock.shouldAskNow(AuthenticationUIState.NotRequired))
        assertFalse(noLock.shouldAskNow(AuthenticationUIState.Required), "asked again after a later lock")
    }
}
