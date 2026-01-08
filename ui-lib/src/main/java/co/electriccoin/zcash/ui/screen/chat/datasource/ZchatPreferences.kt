package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences

/**
 * Interface for ZCHAT preferences.
 */
interface ZchatPreferences {
    /**
     * Check if user has acknowledged that messages cost ZEC.
     */
    fun hasAcknowledgedMessageCost(): Boolean

    /**
     * Mark that user has acknowledged message cost.
     */
    fun setAcknowledgedMessageCost()

    /**
     * Get the set of hidden message IDs.
     */
    fun getHiddenMessageIds(): Set<String>

    /**
     * Add a message ID to the hidden set.
     */
    fun hideMessage(messageId: String)

    /**
     * Add multiple message IDs to the hidden set.
     */
    fun hideMessages(messageIds: Set<String>)

    /**
     * Remove a message ID from the hidden set (unhide).
     */
    fun unhideMessage(messageId: String)

    // ==========================================
    // USER STATUS
    // ==========================================

    /**
     * Get the user's current status text.
     */
    fun getUserStatus(): String

    /**
     * Set the user's status text.
     */
    fun setUserStatus(status: String)

    /**
     * Get the timestamp when status was last updated.
     */
    fun getUserStatusTimestamp(): Long

    /**
     * Get status for a specific peer address.
     */
    fun getPeerStatus(peerAddress: String): String?

    /**
     * Set status for a peer (received from their messages).
     */
    fun setPeerStatus(peerAddress: String, status: String)

    /**
     * Get all stored peer statuses.
     */
    fun getAllPeerStatuses(): Map<String, String>

    // ==========================================
    // MEMO TEMPLATES
    // ==========================================

    /**
     * Get all custom memo templates as JSON strings.
     */
    fun getCustomMemoTemplates(): List<String>

    /**
     * Save a custom memo template (as JSON string).
     */
    fun saveCustomMemoTemplate(templateJson: String)

    /**
     * Remove a custom memo template by ID.
     */
    fun removeCustomMemoTemplate(templateId: String)

    /**
     * Get all custom template JSON strings.
     */
    fun getAllCustomTemplateJson(): Set<String>

    // ==========================================
    // FONT SIZE
    // ==========================================

    /**
     * Get the font size scale (1.0 = normal, 1.1 = 10% bigger, etc.)
     * Default is 1.0
     */
    fun getFontSizeScale(): Float

    /**
     * Set the font size scale.
     */
    fun setFontSizeScale(scale: Float)

    // ==========================================
    // DESTROY / REMOTE KILL SETTINGS
    // ==========================================

    /**
     * Get the destroy PIN (for "Destroy All" button confirmation).
     * Returns null if not set.
     */
    fun getDestroyPin(): String?

    /**
     * Set the destroy PIN.
     */
    fun setDestroyPin(pin: String)

    /**
     * Check if destroy PIN is set.
     */
    fun hasDestroyPin(): Boolean

    /**
     * Check if remote kill is enabled.
     */
    fun isRemoteKillEnabled(): Boolean

    /**
     * Enable/disable remote kill.
     */
    fun setRemoteKillEnabled(enabled: Boolean)

    /**
     * Get the remote kill secret phrase.
     * Must be at least 12 characters.
     */
    fun getRemoteKillPhrase(): String?

    /**
     * Set the remote kill secret phrase.
     */
    fun setRemoteKillPhrase(phrase: String)

    /**
     * Get the remote kill amount in Zatoshi.
     * Default is a unique amount like 1337 zatoshi.
     */
    fun getRemoteKillAmount(): Long

    /**
     * Set the remote kill amount.
     */
    fun setRemoteKillAmount(amountZatoshi: Long)

    /**
     * Clear all preferences (used during destruction).
     */
    fun clearAll()
}

/**
 * SharedPreferences-based implementation of ZchatPreferences.
 */
class ZchatPreferencesImpl(context: Context) : ZchatPreferences {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Separate prefs file for peer statuses (can grow large)
    private val peerStatusPrefs: SharedPreferences = context.getSharedPreferences(
        PEER_STATUS_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "zchat_preferences"
        private const val PEER_STATUS_PREFS_NAME = "zchat_peer_statuses"
        private const val KEY_ACKNOWLEDGED_MESSAGE_COST = "acknowledged_message_cost"
        private const val KEY_HIDDEN_MESSAGES = "hidden_message_ids"
        private const val KEY_USER_STATUS = "user_status"
        private const val KEY_USER_STATUS_TIMESTAMP = "user_status_timestamp"
        private const val KEY_CUSTOM_MEMO_TEMPLATES = "custom_memo_templates"
        private const val KEY_FONT_SIZE_SCALE = "font_size_scale"
        // Destroy / Remote Kill keys
        private const val KEY_DESTROY_PIN = "destroy_pin"
        private const val KEY_REMOTE_KILL_ENABLED = "remote_kill_enabled"
        private const val KEY_REMOTE_KILL_PHRASE = "remote_kill_phrase"
        private const val KEY_REMOTE_KILL_AMOUNT = "remote_kill_amount"
        private const val DEFAULT_REMOTE_KILL_AMOUNT = 1337L // 0.00001337 ZEC - unique amount
    }

    override fun hasAcknowledgedMessageCost(): Boolean {
        return prefs.getBoolean(KEY_ACKNOWLEDGED_MESSAGE_COST, false)
    }

    override fun setAcknowledgedMessageCost() {
        prefs.edit().putBoolean(KEY_ACKNOWLEDGED_MESSAGE_COST, true).apply()
    }

    override fun getHiddenMessageIds(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_MESSAGES, emptySet()) ?: emptySet()
    }

    override fun hideMessage(messageId: String) {
        val current = getHiddenMessageIds().toMutableSet()
        current.add(messageId)
        prefs.edit().putStringSet(KEY_HIDDEN_MESSAGES, current).apply()
    }

    override fun hideMessages(messageIds: Set<String>) {
        val current = getHiddenMessageIds().toMutableSet()
        current.addAll(messageIds)
        prefs.edit().putStringSet(KEY_HIDDEN_MESSAGES, current).apply()
    }

    override fun unhideMessage(messageId: String) {
        val current = getHiddenMessageIds().toMutableSet()
        current.remove(messageId)
        prefs.edit().putStringSet(KEY_HIDDEN_MESSAGES, current).apply()
    }

    // ==========================================
    // USER STATUS IMPLEMENTATION
    // ==========================================

    override fun getUserStatus(): String {
        return prefs.getString(KEY_USER_STATUS, "") ?: ""
    }

    override fun setUserStatus(status: String) {
        prefs.edit()
            .putString(KEY_USER_STATUS, status)
            .putLong(KEY_USER_STATUS_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    override fun getUserStatusTimestamp(): Long {
        return prefs.getLong(KEY_USER_STATUS_TIMESTAMP, 0L)
    }

    override fun getPeerStatus(peerAddress: String): String? {
        return peerStatusPrefs.getString(peerAddress, null)
    }

    override fun setPeerStatus(peerAddress: String, status: String) {
        peerStatusPrefs.edit().putString(peerAddress, status).apply()
    }

    override fun getAllPeerStatuses(): Map<String, String> {
        return peerStatusPrefs.all
            .filterValues { it is String }
            .mapValues { it.value as String }
    }

    // ==========================================
    // MEMO TEMPLATES IMPLEMENTATION
    // ==========================================

    override fun getCustomMemoTemplates(): List<String> {
        return getAllCustomTemplateJson().toList()
    }

    override fun saveCustomMemoTemplate(templateJson: String) {
        val current = getAllCustomTemplateJson().toMutableSet()
        // Remove any existing template with same ID (update)
        val templateId = extractTemplateId(templateJson)
        if (templateId != null) {
            current.removeAll { extractTemplateId(it) == templateId }
        }
        current.add(templateJson)
        prefs.edit().putStringSet(KEY_CUSTOM_MEMO_TEMPLATES, current).apply()
    }

    override fun removeCustomMemoTemplate(templateId: String) {
        val current = getAllCustomTemplateJson().toMutableSet()
        current.removeAll { extractTemplateId(it) == templateId }
        prefs.edit().putStringSet(KEY_CUSTOM_MEMO_TEMPLATES, current).apply()
    }

    override fun getAllCustomTemplateJson(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_MEMO_TEMPLATES, emptySet()) ?: emptySet()
    }

    /**
     * Extract template ID from JSON string (simple parsing).
     * Format expected: {"id":"...", ...}
     */
    private fun extractTemplateId(json: String): String? {
        val regex = """"id"\s*:\s*"([^"]+)"""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    // ==========================================
    // FONT SIZE IMPLEMENTATION
    // ==========================================

    override fun getFontSizeScale(): Float {
        return prefs.getFloat(KEY_FONT_SIZE_SCALE, 1.0f)
    }

    override fun setFontSizeScale(scale: Float) {
        prefs.edit().putFloat(KEY_FONT_SIZE_SCALE, scale).apply()
    }

    // ==========================================
    // DESTROY / REMOTE KILL IMPLEMENTATION
    // ==========================================

    override fun getDestroyPin(): String? {
        return prefs.getString(KEY_DESTROY_PIN, null)
    }

    override fun setDestroyPin(pin: String) {
        prefs.edit().putString(KEY_DESTROY_PIN, pin).apply()
    }

    override fun hasDestroyPin(): Boolean {
        return getDestroyPin() != null
    }

    override fun isRemoteKillEnabled(): Boolean {
        return prefs.getBoolean(KEY_REMOTE_KILL_ENABLED, false)
    }

    override fun setRemoteKillEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMOTE_KILL_ENABLED, enabled).apply()
    }

    override fun getRemoteKillPhrase(): String? {
        return prefs.getString(KEY_REMOTE_KILL_PHRASE, null)
    }

    override fun setRemoteKillPhrase(phrase: String) {
        prefs.edit().putString(KEY_REMOTE_KILL_PHRASE, phrase).apply()
    }

    override fun getRemoteKillAmount(): Long {
        return prefs.getLong(KEY_REMOTE_KILL_AMOUNT, DEFAULT_REMOTE_KILL_AMOUNT)
    }

    override fun setRemoteKillAmount(amountZatoshi: Long) {
        prefs.edit().putLong(KEY_REMOTE_KILL_AMOUNT, amountZatoshi).apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
        peerStatusPrefs.edit().clear().apply()
    }
}
