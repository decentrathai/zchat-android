package co.electriccoin.zcash.ui.screen.authentication

import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1901 (owner W-148): on first launch the notification-permission dialog took focus from the app-access
 * BiometricPrompt, which then ended with ERROR_CANCELED; the app treated that system cancel as the user's
 * cancel and showed "Authentication canceled" with the retry logo. This pins the wiring in the shipped sources:
 *  - MainActivity no longer asks for notifications in onCreate; it asks once, from the app-access state, only
 *    when authentication succeeded or is not required;
 *  - ERROR_CANCELED gets its own result, apart from ERROR_USER_CANCELED;
 *  - WrapAppAccessAuth handles that result with no toast and no failed state, and prompts again on resume.
 * The decisions themselves are covered by [Zc1901SystemCancelAndNotificationGateTest].
 */
class Zc1901NotificationAskWaitsForAppAccessWiringTest {
    @Test
    fun on_create_does_not_ask_for_notifications() {
        val main = source("MainActivity.kt")
        val onCreate = functionBody(main, "override fun onCreate(")
        assertFalse("requestNotificationPermission()" in onCreate, "onCreate still asks for notifications at once")
    }

    @Test
    fun the_notification_ask_follows_the_app_access_state_once() {
        val main = source("MainActivity.kt")
        val access = functionBody(main, "private fun AuthenticationForAppAccess(")
        assertTrue("notificationPermissionGate.shouldAskNow(authState)" in access, "the ask is not gated on app access")
        assertTrue("requestNotificationPermission()" in access, "nothing asks for notifications after app access")
        assertTrue(Regex("""requestNotificationPermission\(\)""").findAll(main).count() == 2, "one definition, one call")
    }

    @Test
    fun a_system_cancel_is_not_mapped_like_a_user_cancel() {
        val vm = source("common/viewmodel/AuthenticationViewModel.kt")
        val canceledBranch = vm.substringAfter("BiometricPrompt.ERROR_CANCELED ->").substringBefore("}")
        assertTrue("SystemCanceled" in canceledBranch, "ERROR_CANCELED still becomes Canceled: $canceledBranch")
        val userBranch = vm.substringAfter("BiometricPrompt.ERROR_USER_CANCELED ->").substringBefore("}")
        assertTrue("AuthenticationResult.Canceled" in userBranch, "the user's cancel is no longer Canceled")
    }

    @Test
    fun app_access_handles_a_system_cancel_without_toast_or_failed_state() {
        val auth = source("screen/authentication/AndroidAuthentication.kt")
        val appAccess = functionBody(auth, "private fun WrapAppAccessAuth(")
        val branch = appAccess.substringAfter("AuthenticationResult.SystemCanceled ->", missingDelimiterValue = "")
        assertTrue(branch.isNotEmpty(), "WrapAppAccessAuth has no SystemCanceled branch")
        val body = branch.substringBefore("AuthenticationResult.Failed ->")
        assertFalse("Toast" in body, "a system cancel still toasts: $body")
        assertFalse("onCancel()" in body || "onFail()" in body, "a system cancel still sets the failed state: $body")
        assertTrue("Lifecycle.Event.ON_RESUME" in appAccess, "app access does not prompt again on resume")
        val canceled = appAccess.substringAfter("AuthenticationResult.Canceled ->").substringBefore("AuthenticationResult.")
        assertTrue("authentication_toast_canceled" in canceled, "the user's own cancel lost its toast")
    }

    private fun functionBody(
        src: String,
        signature: String
    ): String {
        val start = src.indexOf(signature)
        assertTrue(start >= 0, "missing $signature")
        val open = src.indexOf('{', src.indexOf(')', start))
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(open, i + 1)
            }
        }
        error("unbalanced $signature")
    }

    private fun source(path: String) = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/$path").readText()

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }
}
