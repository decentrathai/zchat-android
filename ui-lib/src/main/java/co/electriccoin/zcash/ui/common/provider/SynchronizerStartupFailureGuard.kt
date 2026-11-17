package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferencesUnavailableException
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.screen.chat.datasource.EncryptedStoreUnavailableException
import co.electriccoin.zcash.ui.screen.chat.datasource.OtherStoreUnavailableException

/**
 * Keeps a wallet that cannot be opened from crash-looping the app (RD-4).
 *
 * The SDK's `WalletCoordinator` calls `Synchronizer.new` inside its own
 * `CoroutineScope(GlobalScope.coroutineContext + Dispatchers.Main)`, which has no exception handler. When the
 * wallet database cannot be opened — an SDK migration that fails on an upgraded wallet, or a data DB that
 * belongs to another seed (`InitializeException.SeedNotRelevant`) — kotlinx.coroutines hands the exception
 * to the thread's uncaught-exception handler, and Android's default handler kills the process. That repeats
 * on every launch with no message, and a user who then clears data or reinstalls loses every chat, and the
 * funds if the seed was never backed up.
 *
 * This handler wraps the previous default handler and intercepts exactly that case, reporting it through
 * [onStartupFailure] so the app can show "do not uninstall, back up your recovery phrase". It intercepts
 * only when ALL of these hold, and passes everything else on unchanged:
 * - the thread is NOT terminating. kotlinx.coroutines calls the handler directly from the still-running
 *   thread (`propagateExceptionFinalResort`), so returning keeps that thread alive; an exception that is
 *   unwinding a dying thread (`Thread.dispatchUncaughtException`) must still reach the platform handler;
 * - no synchronizer is running ([isSynchronizerStarting]), i.e. the SDK is still trying to start one;
 * - the exception, or one of its causes, was thrown by Zcash SDK code.
 *
 * R2-3: it also intercepts, on a thread that keeps running and whatever the synchronizer state, an exception
 * whose cause chain holds a typed storage error: the store with the recovery phrase could not be read
 * ([EncryptedPreferencesUnavailableException]) or the encrypted chat stores could not be opened
 * ([EncryptedStoreUnavailableException]). Those come from a phone-security (Keystore) error, first met by
 * the SDK's own read of the saved wallet at start-up; they end on the same screen, never in a crash loop.
 */
internal class SynchronizerStartupFailureGuard(
    private val delegate: Thread.UncaughtExceptionHandler?,
    private val isSynchronizerStarting: () -> Boolean,
    private val onStartupFailure: (Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    @Suppress("TooGenericExceptionCaught")
    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable
    ) {
        val intercept =
            try {
                // Same rule as isStartupFailure, but isSynchronizerStarting (which may read storage) is asked
                // only for an SDK exception on a thread that keeps running.
                val isThreadTerminating = isCurrentThreadTerminating()
                !isThreadTerminating &&
                    (
                        isStorageUnavailable(throwable) ||
                            isThrownBySdk(throwable) &&
                            isStartupFailure(
                                throwable = throwable,
                                isThreadTerminating = isThreadTerminating,
                                isSynchronizerStarting = isSynchronizerStarting(),
                            )
                    )
            } catch (_: Throwable) {
                false
            }
        if (intercept) {
            Twig.error(throwable) { "WALLET - start-up failed; showing the wallet start-up error" }
            onStartupFailure(throwable)
            return
        }
        if (delegate != null) {
            delegate.uncaughtException(thread, throwable)
        } else {
            // Mirrors the JVM default when no handler is set (ThreadGroup would call back into this handler).
            System.err.print("Exception in thread \"${thread.name}\" ")
            throwable.printStackTrace()
        }
    }

    companion object {
        private const val SDK_PACKAGE_PREFIX = "cash.z.ecc.android."
        private const val MAX_CAUSE_DEPTH = 16

        /** Wraps the current default uncaught-exception handler, once per handler chain. */
        fun install(
            isSynchronizerStarting: () -> Boolean,
            onStartupFailure: (Throwable) -> Unit
        ) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is SynchronizerStartupFailureGuard) return
            Thread.setDefaultUncaughtExceptionHandler(
                SynchronizerStartupFailureGuard(current, isSynchronizerStarting, onStartupFailure)
            )
        }

        fun isStartupFailure(
            throwable: Throwable,
            isThreadTerminating: Boolean,
            isSynchronizerStarting: Boolean
        ): Boolean = !isThreadTerminating && isSynchronizerStarting && isThrownBySdk(throwable)

        /** The seed store or the encrypted chat stores could not be opened (R2-3). */
        fun isStorageUnavailable(throwable: Throwable): Boolean {
            val chain = generateSequence(throwable) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH).toList()
            // ZC-733-A: an error of the contact book, the picture index or the AI store never brings the chat stores'
            // screen, whose text would be false for them; such a leak ends as it did before, with nothing false said.
            if (chain.any { it is OtherStoreUnavailableException }) return false
            return chain.any { it is EncryptedPreferencesUnavailableException || it is EncryptedStoreUnavailableException }
        }

        private fun isThrownBySdk(throwable: Throwable): Boolean =
            generateSequence(throwable) { it.cause?.takeIf { cause -> cause !== it } }
                .take(MAX_CAUSE_DEPTH)
                .any { error ->
                    error.javaClass.name.startsWith(SDK_PACKAGE_PREFIX) ||
                        error.stackTrace.any { frame -> frame.className.startsWith(SDK_PACKAGE_PREFIX) }
                }

        private fun isCurrentThreadTerminating(): Boolean =
            Thread.currentThread().stackTrace.any { frame ->
                frame.className == "java.lang.Thread" && frame.methodName == "dispatchUncaughtException"
            }
    }
}
