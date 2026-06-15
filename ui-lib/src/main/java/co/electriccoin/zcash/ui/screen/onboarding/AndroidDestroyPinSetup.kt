package co.electriccoin.zcash.ui.screen.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.onboarding.view.DestroyPinSetupView
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }

    DestroyPinSetupView(
        onSetupPin = { pin ->
            // setDestroyPin is suspend (PBKDF2 runs on Dispatchers.Default). Launch in the
            // composable scope and only fire onPinSetupComplete after the write completes.
            // Guard against double-taps launching concurrent hashing while it runs.
            if (isSaving) return@DestroyPinSetupView
            isSaving = true
            scope.launch {
                try {
                    zchatPreferences.setDestroyPin(pin)
                    onPinSetupComplete()
                } finally {
                    isSaving = false
                }
            }
        },
        onSkip = onSkip,
        isSaving = isSaving
    )
}
