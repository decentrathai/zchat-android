package co.electriccoin.zcash.ui.common.repository

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import co.electriccoin.zcash.spackle.AndroidApiVersion
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.BiometricActivity
import co.electriccoin.zcash.ui.common.provider.ActivityProvider
import co.electriccoin.zcash.ui.common.util.PaymentDiag
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

interface BiometricRepository {
    val allowedAuthenticators: Int

    fun onBiometricResult(result: BiometricResult)

    @Throws(BiometricsFailureException::class, BiometricsCancelledException::class)
    suspend fun requestBiometrics(request: BiometricRequest)

    /**
     * SEED-AUTH: confirms it is the owner before a secret is shown. Unlike [requestBiometrics] it never returns
     * without a prompt on a phone with a screen lock; only a phone with no screen lock answers
     * [SecretAccess.NO_SCREEN_LOCK]. The default fails closed, so an implementation that does not override it never
     * shows a secret.
     */
    @Throws(BiometricsFailureException::class, BiometricsCancelledException::class)
    suspend fun requestSecretAccess(request: BiometricRequest): SecretAccess = throw BiometricsFailureException()
}

data class BiometricRequest(
    val message: StringResource,
    val requestCode: String = UUID.randomUUID().toString(),
    /** The prompt's title; null keeps "Authentication for ZCHAT". */
    val title: StringResource? = null,
)

sealed interface BiometricResult {
    val requestCode: String

    data class Success(
        override val requestCode: String
    ) : BiometricResult

    data class Failure(
        override val requestCode: String
    ) : BiometricResult

    data class Cancelled(
        override val requestCode: String
    ) : BiometricResult
}

class BiometricsFailureException : Exception()

class BiometricsCancelledException : Exception()

class BiometricRepositoryImpl(
    private val context: Context,
    private val biometricManager: BiometricManager
) : BiometricRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // replay = 1 closes a lost-result race: requestBiometrics() launches the prompt Activity and only
    // THEN subscribes via .first(). With a replay-0 flow, a fast result emitted before .first() subscribes
    // was dropped → the awaiting coroutine suspended forever → the caller's "Sending" state wedged
    // permanently, silently killing that send AND every later one (root-caused for the "Send Payment does
    // nothing" report). replay = 1 lets the late subscriber still receive the result; the per-request
    // .filter { requestCode } (codes are unique UUIDs) discards any replayed result from a prior request.
    private val onBiometricsResult = MutableSharedFlow<BiometricResult>(replay = 1)

    override val allowedAuthenticators: Int
        get() =
            when {
                // Android SDK version == 27
                (AndroidApiVersion.isExactlyO) ->
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                // Android SDK version >= 30
                (AndroidApiVersion.isAtLeastR) ->
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                // Android SDK version == 28 || 29
                (AndroidApiVersion.isExactlyP || AndroidApiVersion.isExactlyQ) ->
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL

                else -> error("Unsupported Android SDK version")
            }

    override fun onBiometricResult(result: BiometricResult) {
        scope.launch {
            onBiometricsResult.emit(result)
        }
    }

    override suspend fun requestBiometrics(request: BiometricRequest) {
        if (!canAuthenticate()) {
            // do nothing
            return
        }

        // Launch from the current Activity (same task) when one is available, so the translucent
        // biometric prompt appears OVER the app's content rather than a black NEW_TASK void (the
        // "dark screen on Send" report). Falls back to the application context + NEW_TASK only when
        // there's no foreground Activity (app backgrounded).
        val activity = ActivityProvider.getActivity()
        val launchContext = activity ?: context
        launchContext.startActivity(
            BiometricActivity.createIntent(
                context = launchContext,
                requestCode = request.requestCode,
                subtitle = request.message.getString(context),
                title = request.title?.getString(context),
                inNewTask = activity == null
            )
        )
        // QA (debug-only): the ZC-101 race window opens here — the prompt is up and this coroutine
        // is parked awaiting the result while inbound auto-sends may overwrite the proposal cell.
        PaymentDiag.logBiometricShown()
        when (
            onBiometricsResult.filter { it.requestCode == request.requestCode }.first()
        ) {
            is BiometricResult.Cancelled -> {
                PaymentDiag.logBiometricResult(ok = false, reason = "cancelled")
                throw BiometricsCancelledException()
            }
            is BiometricResult.Failure -> {
                PaymentDiag.logBiometricResult(ok = false, reason = "failure")
                throw BiometricsFailureException()
            }
            is BiometricResult.Success -> {
                PaymentDiag.logBiometricResult(ok = true)
            }
        }
    }

    private fun canAuthenticate(): Boolean =
        when (biometricManager.canAuthenticate(allowedAuthenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }

    override suspend fun requestSecretAccess(request: BiometricRequest): SecretAccess {
        // A missing KeyguardManager counts as a phone WITH a screen lock: fail closed and prompt.
        val isDeviceSecure =
            (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure ?: true
        val status = biometricManager.canAuthenticate(allowedAuthenticators)
        val gate = secretAccessGate(isDeviceSecure, status, Build.VERSION.SDK_INT)
        Twig.info { "SEED-AUTH gate=${gate::class.simpleName} status=$status" }
        return when (gate) {
            SecretAccessGate.NoScreenLock -> SecretAccess.NO_SCREEN_LOCK
            is SecretAccessGate.Prompt -> {
                awaitSecretPrompt(request, gate.authenticators)
                SecretAccess.CONFIRMED
            }
        }
    }

    private suspend fun awaitSecretPrompt(
        request: BiometricRequest,
        authenticators: Int
    ) {
        // Launched as requestBiometrics does (same task over the app's content when an Activity is available).
        val activity = ActivityProvider.getActivity()
        val launchContext = activity ?: context
        launchContext.startActivity(
            BiometricActivity.createIntent(
                context = launchContext,
                requestCode = request.requestCode,
                subtitle = request.message.getString(context),
                title = request.title?.getString(context),
                inNewTask = activity == null,
                authenticators = authenticators
            )
        )
        when (onBiometricsResult.filter { it.requestCode == request.requestCode }.first()) {
            is BiometricResult.Cancelled -> throw BiometricsCancelledException()
            is BiometricResult.Failure -> throw BiometricsFailureException()
            is BiometricResult.Success -> Unit
        }
    }
}
