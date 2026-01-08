package co.electriccoin.zcash.ui.screen.chat.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import java.io.File

/**
 * Manager for handling app destruction and remote kill functionality.
 *
 * DESTROY ALL: Clears all app data and requests uninstallation.
 * REMOTE KILL: Monitors incoming transactions for kill signal.
 */
class DestroyManager(
    private val context: Context,
    private val zchatPreferences: ZchatPreferences
) {
    companion object {
        private const val MIN_KILL_PHRASE_LENGTH = 12

        // Remote kill memo prefix - the memo must contain this + the secret phrase
        const val KILL_MEMO_PREFIX = "ZCHAT_DESTROY:"
    }

    /**
     * Check if a transaction matches the remote kill criteria.
     *
     * @param amountZatoshi The amount of the transaction
     * @param memo The memo content
     * @return true if this is a valid kill signal
     */
    fun isKillSignal(amountZatoshi: Long, memo: String?): Boolean {
        if (!zchatPreferences.isRemoteKillEnabled()) return false

        val killPhrase = zchatPreferences.getRemoteKillPhrase() ?: return false
        val killAmount = zchatPreferences.getRemoteKillAmount()

        // Check if amount matches
        if (amountZatoshi != killAmount) return false

        // Check if memo contains the kill prefix and phrase
        if (memo == null) return false
        val expectedMemo = "$KILL_MEMO_PREFIX$killPhrase"

        return memo.trim() == expectedMemo
    }

    /**
     * Execute full app destruction:
     * 1. Clear all SharedPreferences
     * 2. Clear app cache
     * 3. Clear app databases
     * 4. Request uninstallation
     */
    fun destroyAll(requestUninstall: Boolean = true) {
        // 1. Clear ZCHAT preferences
        zchatPreferences.clearAll()

        // 2. Clear all SharedPreferences files
        clearAllSharedPreferences()

        // 3. Clear app cache
        clearCache()

        // 4. Clear app databases
        clearDatabases()

        // 5. Clear app files directory
        clearFilesDir()

        // 6. Request uninstallation if requested
        if (requestUninstall) {
            requestUninstall()
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
    fun setupRemoteKill(phrase: String, amountZatoshi: Long): Boolean {
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
     * Get the full kill memo that should be sent.
     * Format: ZCHAT_DESTROY:<secret_phrase>
     */
    fun getKillMemo(): String? {
        val phrase = zchatPreferences.getRemoteKillPhrase() ?: return null
        return "$KILL_MEMO_PREFIX$phrase"
    }
}
