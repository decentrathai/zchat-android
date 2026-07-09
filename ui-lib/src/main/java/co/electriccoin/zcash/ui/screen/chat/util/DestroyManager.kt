package co.electriccoin.zcash.ui.screen.chat.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Manager for handling app destruction and remote kill functionality.
 *
 * DESTROY ALL: Clears all app data and requests uninstallation.
 * REMOTE KILL: Monitors incoming transactions for kill signal.
 */
class DestroyManager(
    private val context: Context,
    private val zchatPreferences: ZchatPreferences,
    private val walletCoordinator: WalletCoordinator,
    private val synchronizerProvider: SynchronizerProvider,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
    private val flexaRepository: FlexaRepository
) {
    companion object {
        private const val TAG = "DestroyManager"
        private const val MIN_KILL_PHRASE_LENGTH = 12

        // Upper bound on the SDK synchronizer close during a wipe. closeFlow().first() can suspend
        // forever if the synchronizer is wedged mid-sync, and — running under NonCancellable — a hang
        // (not an exception) would block the entire panic wipe with no way out. Time-box it and proceed
        // to the data erase on timeout. Mirrors ResetZashiUseCase's handling of the same call.
        private const val SYNCHRONIZER_CLOSE_TIMEOUT_MS = 10_000L

        // Remote kill memo prefix from ZMSGConstants
        const val KILL_MEMO_PREFIX = ZMSGConstants.REMOTE_KILL_PREFIX
    }

    /**
     * Check if a transaction matches the remote kill criteria.
     *
     * @param amountZatoshi The amount of the transaction
     * @param memo The memo content
     * @return true if this is a valid kill signal
     */
    suspend fun isKillSignal(amountZatoshi: Long, memo: String?): Boolean {
        if (!zchatPreferences.isRemoteKillEnabled()) return false
        if (!zchatPreferences.hasRemoteKillPhrase()) return false

        val killAmount = zchatPreferences.getRemoteKillAmount()

        // Check if amount matches
        if (amountZatoshi != killAmount) return false

        // Check if memo contains the kill prefix and phrase
        if (memo == null) return false
        if (!memo.trim().startsWith(KILL_MEMO_PREFIX)) return false

        // Extract phrase from memo and verify against stored hash
        val phraseFromMemo = memo.trim().removePrefix(KILL_MEMO_PREFIX)
        return zchatPreferences.verifyRemoteKillPhrase(phraseFromMemo)
    }

    /**
     * Execute full app destruction:
     * 1. Disconnect external services
     * 2. Close the SDK synchronizer
     * 3. Delete SDK/wallet data
     * 4. Clear all SharedPreferences
     * 5. Clear app cache and files
     * 6. Kill the app process
     */
    suspend fun destroyAll(requestUninstall: Boolean = true) {
        Log.w(TAG, "destroyAll() called - beginning complete app destruction")

        // Run the entire wipe under NonCancellable: it MUST complete even if the caller's
        // coroutine scope is cancelled. The chat-list red button and the remote-kill callback
        // launch this from a Composable-tied rememberCoroutineScope (AndroidChat.kt), which is
        // cancelled the instant the screen leaves composition — that previously interrupted the
        // wipe mid-flight while forceKillApp() still ran, so the app "destroyed" itself but left
        // ALL data intact (same address + messages on reopen). The Settings path used
        // viewModelScope and was unaffected; NonCancellable protects every caller uniformly.
        // Dispatchers.IO: performFullWipe() does synchronous file IO (deleteRecursively / listFiles /
        // delete across prefs, cache, databases, files dirs). The chat-list / remote-kill callers launch
        // this from a Composable rememberCoroutineScope (Dispatchers.Main), and withContext(NonCancellable)
        // alone does NOT switch dispatcher — so the IO ran on Main and tripped StrictMode disk
        // read/write violations (a hard crash when IS_STRICT_MODE_CRASH_ENABLED). Run the wipe on IO; the
        // post-wipe requestUninstall()/forceKillApp() run on the caller's dispatcher (Main) for startActivity.
        //
        // The ENTIRE sequence — wipe AND the uninstall/kill below — runs under a single outer
        // NonCancellable. Previously requestUninstall()/forceKillApp() sat AFTER the withContext block, so
        // when the caller's Composable-tied scope was cancelled mid-wipe (the documented common case), the
        // resumption of withContext threw CancellationException and SILENTLY SKIPPED the uninstall + process
        // kill — leaving the app running with decrypted keys/messages still in memory (and live prefs
        // in-memory caches able to flush "destroyed" data back to disk on any later write). Keeping the kill
        // inside NonCancellable guarantees the destroy actually finishes.
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                performFullWipe()
            }

            // Request uninstallation or kill the app — only AFTER the wipe has fully completed.
            if (requestUninstall) {
                requestUninstall()
            }

            // Force kill the app process so it restarts fresh.
            forceKillApp()
        }
    }

    /**
     * The full data wipe (steps 1–9). Invoked under [NonCancellable] from [destroyAll] so it always
     * runs to completion even if the caller's coroutine scope is cancelled mid-flight (the chat-list
     * and remote-kill entry points launch from a Composable-tied scope that previously cancelled it).
     */
    private suspend fun performFullWipe() {
        try {
            // 1. Disconnect external services (Flexa, etc.)
            try {
                flexaRepository.disconnect()
                Log.d(TAG, "Flexa disconnected")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disconnect Flexa: ${e.message}")
            }

            // 2. Close the SDK synchronizer - CRITICAL!
            // This releases file locks so we can delete the database
            try {
                val synchronizer = synchronizerProvider.getSynchronizer()
                // Time-box the close: a wedged synchronizer would otherwise suspend here forever, and
                // under NonCancellable a hang is unrecoverable (no exception, no cancellation) — the whole
                // panic wipe would stall at "Destroying All Data…" and never erase anything. withTimeoutOrNull
                // uses its own cancellable child scope, so it still fires inside the surrounding NonCancellable.
                withTimeoutOrNull(SYNCHRONIZER_CLOSE_TIMEOUT_MS) {
                    (synchronizer as? SdkSynchronizer)?.closeFlow()?.first()
                }
                Log.d(TAG, "Synchronizer closed")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close synchronizer: ${e.message}")
            }

            // 3. Delete SDK data through WalletCoordinator - CRITICAL!
            // This properly deletes the wallet database and derived data
            try {
                val deleted = walletCoordinator.deleteSdkDataFlow().first()
                Log.d(TAG, "SDK data deletion result: $deleted")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete SDK data: ${e.message}")
            }

            // 4. Clear ZCHAT-specific preferences
            // Own try/catch like every other step: an EncryptedSharedPreferences/Keystore error here must
            // NOT abort the wipe and leave the seed-bearing encrypted prefs, databases and files dir intact.
            try {
                zchatPreferences.clearAll()
                Log.d(TAG, "ZCHAT preferences cleared")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear ZCHAT preferences: ${e.message}")
            }

            // 5. Clear all SharedPreferences through proper providers
            try {
                standardPreferenceProvider().clearPreferences()
                encryptedPreferenceProvider().clearPreferences()
                Log.d(TAG, "Standard and encrypted preferences cleared")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear preference providers: ${e.message}")
            }

            // 6. Clear all SharedPreferences files (backup in case providers miss any)
            clearAllSharedPreferences()

            // 7. Clear app cache
            clearCache()

            // 8. Clear any remaining databases
            clearDatabases()

            // 9. Clear app files directory
            clearFilesDir()

            Log.w(TAG, "All app data destroyed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during destruction: ${e.message}", e)
            // Even if something fails, try to clear as much as possible
        }
    }

    /**
     * Clear all SharedPreferences files.
     */
    private fun clearAllSharedPreferences() {
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.exists() && prefsDir.isDirectory) {
                prefsDir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }
        } catch (e: Exception) {
            // Ignore errors during destruction
        }
    }

    /**
     * Clear app cache directory.
     */
    private fun clearCache() {
        try {
            context.cacheDir.deleteRecursively()
            context.externalCacheDir?.deleteRecursively()
        } catch (e: Exception) {
            // Ignore errors during destruction
        }
    }

    /**
     * Clear all databases.
     */
    private fun clearDatabases() {
        try {
            val dbDir = File(context.applicationInfo.dataDir, "databases")
            if (dbDir.exists() && dbDir.isDirectory) {
                dbDir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }
            // Also try to delete known database names
            context.databaseList().forEach { dbName ->
                context.deleteDatabase(dbName)
            }
        } catch (e: Exception) {
            // Ignore errors during destruction
        }
    }

    /**
     * Clear files directory.
     */
    private fun clearFilesDir() {
        try {
            context.filesDir.deleteRecursively()
            context.getExternalFilesDir(null)?.deleteRecursively()
        } catch (e: Exception) {
            // Ignore errors during destruction
        }
    }

    /**
     * Request app uninstallation.
     * This will open a system dialog asking the user to confirm.
     */
    private fun requestUninstall() {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // If uninstall request fails, at least the data is cleared
        }
    }

    /**
     * Validate a kill phrase.
     * Must be at least 12 characters long.
     */
    fun isValidKillPhrase(phrase: String): Boolean {
        return phrase.length >= MIN_KILL_PHRASE_LENGTH
    }

    /**
     * Set up remote kill with validation.
     *
     * @return true if setup was successful
     */
    suspend fun setupRemoteKill(phrase: String, amountZatoshi: Long): Boolean {
        if (!isValidKillPhrase(phrase)) return false
        if (amountZatoshi <= 0) return false

        zchatPreferences.setRemoteKillPhrase(phrase)
        zchatPreferences.setRemoteKillAmount(amountZatoshi)
        zchatPreferences.setRemoteKillEnabled(true)

        return true
    }

    /**
     * Disable remote kill.
     */
    fun disableRemoteKill() {
        zchatPreferences.setRemoteKillEnabled(false)
    }

    /**
     * Get the kill memo format hint (without the actual phrase).
     * Format: ZCHAT_DESTROY:<your_secret_phrase>
     *
     * NOTE: The actual phrase is stored as a hash and cannot be recovered.
     * User must remember/write down their phrase when setting up remote kill.
     */
    fun getKillMemoFormat(): String {
        return "${KILL_MEMO_PREFIX}<your_secret_phrase>"
    }

    /**
     * Check if remote kill is configured (enabled and has phrase).
     */
    fun isRemoteKillConfigured(): Boolean {
        return zchatPreferences.isRemoteKillEnabled() && zchatPreferences.hasRemoteKillPhrase()
    }

    /**
     * Force kill the app process.
     * This ensures all in-memory data is cleared and the app restarts fresh.
     */
    private fun forceKillApp() {
        try {
            // Clear all activities from the task stack
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.let { am ->
                // On newer Android versions, we can clear app tasks
                am.appTasks.forEach { task ->
                    task.finishAndRemoveTask()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear app tasks: ${e.message}")
        }

        // Kill the process - this is the nuclear option that ensures complete reset
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
