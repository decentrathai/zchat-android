package co.electriccoin.zcash.ui.screen.onboarding

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.onboarding.view.DestroyPinSetupView
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Android wrapper for the Destroy PIN setup screen.
 * Handles saving the PIN to preferences and navigation callbacks.
 */
@Composable
fun AndroidDestroyPinSetup(
    onPinSetupComplete: () -> Unit,
    onSkip: () -> Unit
) {
    // Inject the DI singleton (do NOT construct ZchatPreferencesImpl directly — a second instance would
    // have its own in-memory state, e.g. the shared read-marker flow #226).
    val zchatPreferences = koinInject<ZchatPreferences>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isSaving by remember { mutableStateOf(false) }

    // #6 security: CHANGING an existing destroy PIN must first pass the device credential — otherwise
    // anyone with the briefly-unlocked phone can silently overwrite the emergency-wipe PIN with their own
    // (then trigger a full wipe using it). If no PIN is set yet, or the device has no credential to check
    // against, there is nothing to gate — proceed directly. Same factor the ChatList reset path uses.
    val alreadyHasPin = remember { zchatPreferences.hasDestroyPin() }
    var credentialConfirmed by remember { mutableStateOf(!alreadyHasPin) }
    val confirmLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) credentialConfirmed = true else onSkip()
        }
    LaunchedEffect(Unit) {
        if (alreadyHasPin) {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            @Suppress("DEPRECATION")
            val intent = km?.createConfirmDeviceCredentialIntent(
                "Change emergency-wipe PIN",
                "Confirm your device PIN/biometric to change the ZCHAT destroy PIN."
            )
            if (intent != null) confirmLauncher.launch(intent) else credentialConfirmed = true
        }
    }

    // Don't reveal the PIN-entry UI (nor let a PIN be saved) until the credential gate passes.
    if (!credentialConfirmed) return

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
