package co.electriccoin.zcash.ui.screen.authentication

import androidx.biometric.BiometricPrompt
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1965 (ZC-1901 fails on the phone; device round 1 of 2.13.0, Seeker, 3 of 3): Home while the app-access
 * BiometricPrompt is up shows the "Authentication canceled" toast over the launcher. SystemUI logs "AuthController:
 * Close BP, reason :homekey"; it closes the prompt as a user dismissal, so the app gets ERROR_USER_CANCELED, which
 * authenticationResultForError maps to Canceled (ZC-1901 kept that), and WrapAppAccessAuth's Canceled branch shows the
 * toast at once. The error comes while the app is leaving the foreground: before or just after its onPause.
 *
 * Wanted: `internal object AppAccessCancelToast` in this package with
 *  - `const val LEAVING_WINDOW_MS: Long`: how long after a cancel a pause still means the app was leaving (Home);
 *  - `fun shows(errorCode: Int, resumedAtCancel: Boolean, pausedAfterCancelMs: Long?): Boolean`: whether the cancel's
 *    "Authentication canceled" toast shows. Never when the activity was not resumed at the cancel, or paused within
 *    LEAVING_WINDOW_MS after it, for ERROR_CANCELED and ERROR_USER_CANCELED alike; the user's own cancel
 *    (ERROR_USER_CANCELED) while the app stays in front keeps its toast; ERROR_CANCELED never toasts (ZC-1901).
 *  - WrapAppAccessAuth's Canceled branch shows the toast only through that rule (it waits LEAVING_WINDOW_MS for a pause).
 */
class Zc1965HomeDuringAppAccessPromptShowsNoToastTest {
    private fun shows(
        errorCode: Int,
        resumedAtCancel: Boolean,
        pausedAfterCancelMs: Long?,
    ): Boolean {
        val cls = policyClass()
        val m =
            cls.methods.firstOrNull { it.name == "shows" && it.parameterCount == 3 }
                ?: fail("$POLICY has no shows(errorCode, resumedAtCancel, pausedAfterCancelMs)")
        return try {
            m.invoke(cls.getField("INSTANCE").get(null), errorCode, resumedAtCancel, pausedAfterCancelMs) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun window(): Long =
        runCatching { policyClass().getField("LEAVING_WINDOW_MS").getLong(null) }
            .getOrElse { fail("$POLICY has no LEAVING_WINDOW_MS") }

    private fun policyClass(): Class<*> = runCatching { Class.forName(POLICY) }.getOrElse { fail("$POLICY does not exist") }

    @Test
    fun home_during_the_prompt_shows_no_toast_for_either_cancel_code() {
        listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED).forEach { code ->
            assertEquals(false, shows(code, resumedAtCancel = false, pausedAfterCancelMs = null), "ZC-1965: paused ($code)")
            assertEquals(
                false,
                shows(code, resumedAtCancel = true, pausedAfterCancelMs = SEEKER_HOME_PAUSE_MS),
                "ZC-1965: Home closed the prompt and the app paused ${SEEKER_HOME_PAUSE_MS} ms later: no toast ($code)",
            )
            assertEquals(false, shows(code, resumedAtCancel = true, pausedAfterCancelMs = 0L), "ZC-1965: same pause ($code)")
        }
    }

    @Test
    fun the_users_own_cancel_in_front_keeps_its_toast() {
        assertEquals(
            true,
            shows(BiometricPrompt.ERROR_USER_CANCELED, resumedAtCancel = true, pausedAfterCancelMs = null),
            "the user's own cancel with the app in front shows \"Authentication canceled\"",
        )
        assertEquals(
            true,
            shows(BiometricPrompt.ERROR_USER_CANCELED, resumedAtCancel = true, pausedAfterCancelMs = window() + 1_000L),
            "a pause long after the user's cancel does not take the toast back",
        )
        assertEquals(
            false,
            shows(BiometricPrompt.ERROR_CANCELED, resumedAtCancel = true, pausedAfterCancelMs = null),
            "ZC-1901: a system cancel in front never toasts",
        )
    }

    @Test
    fun the_leaving_window_covers_the_seekers_home_and_stays_short() {
        val w = window()
        assertTrue(w > SEEKER_HOME_PAUSE_MS, "ZC-1965: the window ($w ms) covers the Seeker's Home pause")
        assertTrue(w <= MAX_WINDOW_MS, "the user's toast waits at most $MAX_WINDOW_MS ms ($w)")
    }

    @Test
    fun the_app_access_canceled_branch_toasts_only_through_the_rule() {
        val text =
            ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/authentication/AndroidAuthentication.kt")
        val access = text.substringAfter("private fun WrapAppAccessAuth(", missingDelimiterValue = "")
        assertTrue(access.isNotEmpty(), "no WrapAppAccessAuth")
        val canceled =
            access
                .substringAfter("AuthenticationResult.Canceled ->", missingDelimiterValue = "")
                .substringBefore("AuthenticationResult.SystemCanceled ->")
        assertTrue(canceled.isNotEmpty(), "no Canceled branch in WrapAppAccessAuth")
        assertTrue(
            "AppAccessCancelToast" in canceled,
            "ZC-1965: the app-access Canceled branch must ask AppAccessCancelToast before the toast",
        )
    }

    private companion object {
        const val POLICY = "co.electriccoin.zcash.ui.screen.authentication.AppAccessCancelToast"

        // Seeker round 1: "Close BP, reason :homekey" to the launcher taking the focus, about 370 ms.
        const val SEEKER_HOME_PAUSE_MS = 400L
        const val MAX_WINDOW_MS = 1_500L
    }
}
