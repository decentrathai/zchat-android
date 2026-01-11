package co.electriccoin.zcash.ui.screen.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.onboarding.view.DestroyPinSetupView

/**
 * Android wrapper for the Destroy PIN setup screen.
 * Handles saving the PIN to preferences and navigation callbacks.
 */
@Composable
fun AndroidDestroyPinSetup(
    onPinSetupComplete: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val zchatPreferences = ZchatPreferencesImpl(context)

    DestroyPinSetupView(
        onSetupPin = { pin ->
            zchatPreferences.setDestroyPin(pin)
            onPinSetupComplete()
        },
        onSkip = onSkip
    )
}
