package co.electriccoin.zcash.ui.screen.onboarding

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import co.electriccoin.zcash.ui.screen.onboarding.view.DestroyPinCreditLine
import co.electriccoin.zcash.ui.screen.onboarding.view.DestroyPinSetupView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * The Destroy PIN set-up (Settings → Advanced → Emergency Data Wipe PIN). DESTROY-ADV-P (ZC-722; owner W-52): three
 * steps through [DestroyPinSetupFlow]: what Destroy All does, what it erases (the choice), the PIN; ONE write for the PIN
 * and the choice (setDestroySetup, through DestroyManager). After Save the screen returns to Advanced Settings.
 */
@Composable
fun AndroidDestroyPinSetup(
    onPinSetupComplete: () -> Unit,
    onSkip: () -> Unit
) {
    // Inject the DI singleton (do NOT construct ZchatPreferencesImpl directly — a second instance would
    // have its own in-memory state, e.g. the shared read-marker flow #226).
    val zchatPreferences = koinInject<ZchatPreferences>()
    val destroyManager = koinInject<DestroyManager>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val flow = remember { DestroyPinSetupFlow(zchatPreferences, destroyManager) { phoneHasScreenLock(context) } }
    val state by flow.state.collectAsState()

    // #6 security: CHANGING an existing destroy PIN must first pass the device credential — otherwise
    // anyone with the briefly-unlocked phone can silently overwrite the emergency-wipe PIN with their own
    // (then trigger a full wipe using it). If no PIN is set yet, or the device has no credential to check
    // against, there is nothing to gate — proceed directly.
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
            val intent =
                km?.createConfirmDeviceCredentialIntent(
                    context.getString(R.string.chat_destroy_pin_reset_title),
                    context.getString(R.string.chat_destroy_pin_reset_prompt)
                )
            if (intent != null) confirmLauncher.launch(intent) else credentialConfirmed = true
        }
    }

    // B2: a first Save that carries the keep tick asks for the phone's screen lock first.
    val proofLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            scope.launch { withContext(Dispatchers.IO) { flow.onProofResult(result.resultCode == Activity.RESULT_OK) } }
        }
    LaunchedEffect(state.proofNeeded) {
        if (state.proofNeeded) {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

            @Suppress("DEPRECATION")
            val intent =
                km?.createConfirmDeviceCredentialIntent(
                    context.getString(R.string.destroy_options_auth_title),
                    null
                )
            if (intent != null) {
                proofLauncher.launch(intent)
            } else {
                withContext(Dispatchers.IO) { flow.onProofResult(false) }
            }
        }
    }

    // DESTROY-ADV-C: what Destroy All erases with the saved choice, read again when the screen opens (off the main thread).
    var keepsContacts by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        keepsContacts =
            withContext(Dispatchers.IO) {
                flow.load()
                runCatching { destroyManager.readDestroyFlowChoice().keepsContacts }.getOrDefault(false)
            }
    }

    LaunchedEffect(state.finished, state.left) {
        state.notice?.let { notice ->
            Toast.makeText(context, context.getString(notice), Toast.LENGTH_LONG).show()
            flow.onNoticeShown()
        }
        when {
            state.finished -> onPinSetupComplete()
            state.left -> onSkip()
        }
    }

    // Don't reveal anything (nor let a PIN be saved) until the credential gate passes.
    if (!credentialConfirmed) return

    BackHandler { flow.onBack() }

    DestroyPinSetupView(
        state = state,
        onSetUp = flow::onSetUp,
        onSkip = flow::onSkip,
        onContinue = flow::onContinue,
        onCancel = flow::onCancel,
        onToggleAdvanced = flow::onToggleAdvanced,
        onChooseEverything = flow::onChooseEverything,
        onChooseKeep = flow::onChooseKeep,
        onTickContacts = flow::onTickContacts,
        onPinChange = flow::onPinChange,
        onConfirmChange = flow::onConfirmChange,
        onSave = { scope.launch { withContext(Dispatchers.IO) { flow.save() } } },
        keepsContacts = keepsContacts,
        aiCreditLine = { DestroyPinCreditLine() }
    )
}

/** Whether the phone has a screen lock (PIN, pattern, password or a biometric with one). */
private fun phoneHasScreenLock(context: Context): Boolean =
    (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true
