package co.electriccoin.zcash.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.filters.MediumTest
import co.electriccoin.zcash.test.UiTestPrerequisites
import co.electriccoin.zcash.ui.common.compose.LocalScreenSecurity
import co.electriccoin.zcash.ui.common.compose.ScreenSecurity
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.authentication.view.AppAccessAuthentication
import co.electriccoin.zcash.ui.screen.onboarding.view.DestroyPinSetupView
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Verifies that screens displaying sensitive content (PIN entry, app-lock authentication)
 * participate in the SecureScreen invariant so Android sets FLAG_SECURE on the window.
 *
 * Each screen is rendered inside a CompositionLocalProvider supplying a fresh ScreenSecurity
 * instance; if the composable calls SecureScreen() at composition time, the reference count
 * increments to 1. A count of 0 means FLAG_SECURE will NOT be applied and the screen is
 * vulnerable to screenshots / screen recording apps.
 */
class SensitiveScreenInvariantTest : UiTestPrerequisites() {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @MediumTest
    fun destroy_pin_setup_view_acquires_secure_screen() = runTest {
        val screenSecurity = renderAndObserve(composeTestRule) {
            DestroyPinSetupView(
                onSetupPin = {},
                onSkip = {},
            )
        }

        assertEquals(
            expected = 1,
            actual = screenSecurity.referenceCount.value,
            message = "DestroyPinSetupView shows PIN input and MUST call SecureScreen() to " +
                "prevent the PIN from being captured by screenshots or screen recording."
        )
    }

    @Test
    @MediumTest
    fun app_access_authentication_acquires_secure_screen() = runTest {
        val screenSecurity = renderAndObserve(composeTestRule) {
            AppAccessAuthentication(
                onRetry = {},
                showAuthLogo = false,
                welcomeAnimVisibility = true,
            )
        }

        assertEquals(
            expected = 1,
            actual = screenSecurity.referenceCount.value,
            message = "AppAccessAuthentication is the app-lock screen shown on every app " +
                "resume and MUST call SecureScreen() to prevent credential entry from " +
                "being captured by screenshots or screen recording."
        )
    }

    private fun renderAndObserve(
        rule: ComposeContentTestRule,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ): ScreenSecurity {
        val screenSecurity = ScreenSecurity()
        rule.setContent {
            CompositionLocalProvider(LocalScreenSecurity provides screenSecurity) {
                ZcashTheme {
                    content()
                }
            }
        }
        rule.waitForIdle()
        return screenSecurity
    }
}
