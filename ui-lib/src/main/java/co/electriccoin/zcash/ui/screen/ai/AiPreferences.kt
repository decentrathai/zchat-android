package co.electriccoin.zcash.ui.screen.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the per-device bearer token so re-installing the app doesn't mint a fresh
 * $0.20 free trial. Kept in its own EncryptedSharedPreferences file to avoid coupling
 * to the chat module's ZchatPreferences.
 *
 * Stored fields:
 *   - "ai_token": the opaque bearer issued by POST /ai/auth/register
 *   - "ai_user_id": the matching userId (for diagnostics, not auth)
 *
 * Threat model: same as e2ePrefs — AES-256-GCM via Android Keystore. On rooted devices
 * + Keystore-extraction the attacker can read the token and use it; per Phase-1 v1.0
 * design that's acceptable since the token only buys their own AI quota.
 */
class AiPreferences(context: Context) {
    // Build EncryptedSharedPreferences/Tink lazily so the constructor stays cheap — the Keystore +
    // keyset disk reads (~241ms) used to run synchronously when the AI tab composable created this on
    // the main thread (StrictMode DiskReadViolation). The first access is now forced off the main
    // thread by AiTabVM (viewModelScope on Dispatchers.IO), so the UI never blocks on it.
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    // Chat and image models are picked + remembered independently — selecting a model in one mode
    // must not leave the other mode pointing at an incompatible model (e.g. an image model in chat).
    fun getSelectedChatModel(): String? = prefs.getString(KEY_SELECTED_MODEL, null)

    fun setSelectedChatModel(modelId: String) {
        prefs.edit().putString(KEY_SELECTED_MODEL, modelId).apply()
    }

    fun getSelectedImageModel(): String? = prefs.getString(KEY_SELECTED_IMAGE_MODEL, null)

    fun setSelectedImageModel(modelId: String) {
        prefs.edit().putString(KEY_SELECTED_IMAGE_MODEL, modelId).apply()
    }

    // ── AI history (kept until the user clears) ─────────────────────────────────────────────────
    // Chat transcripts persist as JSON here (text is small). Generated image BYTES are stored by
    // AiImageStore in filesDir; only their metadata JSON lives here.
    fun getConversationsJson(): String? = prefs.getString(KEY_CONVERSATIONS, null)

    fun setConversationsJson(json: String) {
        prefs.edit().putString(KEY_CONVERSATIONS, json).apply()
    }

    fun getImagesJson(): String? = prefs.getString(KEY_IMAGES, null)

    fun setImagesJson(json: String) {
        prefs.edit().putString(KEY_IMAGES, json).apply()
    }

    /** Auto-delete retention for chats + images, in days. 0 = keep forever (default). */
    fun getRetentionDays(): Int = prefs.getInt(KEY_RETENTION_DAYS, 0)

    fun setRetentionDays(days: Int) {
        prefs.edit().putInt(KEY_RETENTION_DAYS, days).apply()
    }

    fun saveCredentials(token: String, userId: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "zchat_ai_prefs"
        private const val MASTER_KEY_ALIAS = "zchat_ai_master_key"
        private const val KEY_TOKEN = "ai_token"
        private const val KEY_USER_ID = "ai_user_id"
        private const val KEY_SELECTED_MODEL = "ai_selected_model"
        private const val KEY_SELECTED_IMAGE_MODEL = "ai_selected_image_model"
        private const val KEY_CONVERSATIONS = "ai_conversations"
        private const val KEY_IMAGES = "ai_images"
        private const val KEY_RETENTION_DAYS = "ai_retention_days"
    }
}
