package co.electriccoin.zcash.ui

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.secretCredentialFallback
import org.koin.android.ext.android.inject

class BiometricActivity : FragmentActivity() {
    private val biometricRepository by inject<BiometricRepository>()

    // SEED-AUTH: set when the caller passed EXTRA_AUTHENTICATORS (a secret request, requestSecretAccess). Every other
    // caller (app lock, Send and the rest) passes nothing and keeps today's behaviour.
    private var isSecretMode = false
    private var requestCode = ""
    private var resultSent = false
    private var isScreenLockFallbackShown = false
    private lateinit var screenLockLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots and screen recording — but only in RELEASE builds. Debug/debuggable
        // builds skip FLAG_SECURE so QA can screenshot/drive the auth screen during on-device testing.
        // Release builds are NOT debuggable, so they always keep it — this gate cannot ship a release
        // insecure. Mirrors the per-screen gate in CompositionLocalBinder.
        val isDebuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        requestCode = intent.getStringExtra(EXTRA_REQUEST_CODE).orEmpty()
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        isSecretMode = intent.hasExtra(EXTRA_AUTHENTICATORS)
        val authenticators = intent.getIntExtra(EXTRA_AUTHENTICATORS, biometricRepository.allowedAuthenticators)
        val title =
            intent.getStringExtra(EXTRA_TITLE)
                ?: getString(R.string.authentication_system_ui_title, getString(R.string.app_name))

        screenLockLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                sendResult(
                    if (result.resultCode == RESULT_OK) {
                        BiometricResult.Success(requestCode)
                    } else {
                        BiometricResult.Failure(requestCode)
                    }
                )
                finish()
            }

        // Recreated while the screen-lock screen is up (e.g. fold/unfold): its result arrives on the launcher.
        isScreenLockFallbackShown = savedInstanceState?.getBoolean(STATE_SCREEN_LOCK_FALLBACK_SHOWN) == true
        if (isSecretMode && isScreenLockFallbackShown) return

        val biometricPrompt =
            BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(application),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence
                    ) {
                        super.onAuthenticationError(errorCode, errString)
                        // SEED-AUTH: a broken biometric stack asks for the screen lock instead; never a skip.
                        if (isSecretMode && secretCredentialFallback(errorCode) && launchScreenLockFallback(title, subtitle)) {
                            return
                        }
                        sendResult(BiometricResult.Failure(requestCode))
                        finish()
                    }

                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        sendResult(BiometricResult.Success(requestCode))
                        finish()
                    }
                }
            )

        val promptInfo =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
                .build()

        biometricPrompt.authenticate(promptInfo)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SCREEN_LOCK_FALLBACK_SHOWN, isScreenLockFallbackShown)
    }

    override fun onDestroy() {
        // SEED-AUTH: a secret request whose host finishes with no result is a failure, so Reveal is never left waiting.
        if (isSecretMode && isFinishing && !resultSent) {
            sendResult(BiometricResult.Failure(requestCode))
        }
        super.onDestroy()
    }

    private fun sendResult(result: BiometricResult) {
        resultSent = true
        biometricRepository.onBiometricResult(result)
    }

    /** The phone's own screen-lock screen; false when the phone offers none (no lock) or cannot open it. */
    private fun launchScreenLockFallback(
        title: String,
        subtitle: String
    ): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        @Suppress("DEPRECATION")
        val intent = keyguardManager?.createConfirmDeviceCredentialIntent(title, subtitle) ?: return false
        return try {
            screenLockLauncher.launch(intent)
            isScreenLockFallbackShown = true
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    companion object {
        private const val EXTRA_REQUEST_CODE = "EXTRA_REQUEST_CODE"
        private const val EXTRA_SUBTITLE = "EXTRA_SUBTITLE"
        private const val EXTRA_TITLE = "EXTRA_TITLE"
        private const val EXTRA_AUTHENTICATORS = "EXTRA_AUTHENTICATORS"
        private const val STATE_SCREEN_LOCK_FALLBACK_SHOWN = "STATE_SCREEN_LOCK_FALLBACK_SHOWN"

        fun createIntent(
            context: Context,
            requestCode: String,
            subtitle: String,
            inNewTask: Boolean = true,
            authenticators: Int? = null,
            title: String? = null
        ) = Intent(context, BiometricActivity::class.java).apply {
            // NEW_TASK is required (and only valid) when launching from a non-Activity (application)
            // context. Launching from an Activity keeps the prompt in the SAME task, so the translucent
            // host shows over the app's content instead of a black NEW_TASK void.
            if (inNewTask) flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_REQUEST_CODE, requestCode)
            putExtra(EXTRA_SUBTITLE, subtitle)
            if (title != null) putExtra(EXTRA_TITLE, title)
            // SEED-AUTH: only requestSecretAccess passes authenticators; it also switches on secret mode.
            if (authenticators != null) putExtra(EXTRA_AUTHENTICATORS, authenticators)
        }
    }
}
