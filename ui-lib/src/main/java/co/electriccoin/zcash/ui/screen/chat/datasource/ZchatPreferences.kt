package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import co.electriccoin.zcash.ui.common.util.redactAddress
import co.electriccoin.zcash.ui.common.util.redactConvId
import java.security.MessageDigest

/**
 * Notification privacy levels for ZCHAT.
 * Controls how much information is shown in notifications.
 */
enum class NotificationPrivacy {
    /** Shows sender name and message content. "Alice: Hey, how are you?" */
    FULL_PREVIEW,

    /** Shows only the sender name. "New message from Alice" */
    SENDER_ONLY,

    /** Shows a generic notification. "New ZCHAT message" */
    NEW_MESSAGE,

    /** No notifications are shown. User must check app manually. */
    SILENT
}

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
    // CONTACT NICKNAMES
    // ==========================================

    /**
     * Get nickname for a contact address.
     * @return The nickname, or null if not set
     */
    fun getNickname(address: String): String?

    /**
     * Set nickname for a contact address.
     * @param address The contact's Zcash address
     * @param nickname The nickname to set (empty string to clear)
     */
    fun setNickname(address: String, nickname: String)

    /**
     * Get display name for an address (nickname if set, otherwise truncated address).
     * @return Nickname if set, otherwise first 8 + last 6 chars of address
     */
    fun getDisplayName(address: String): String

    /**
     * Get all stored nicknames.
     * @return Map of address -> nickname
     */
    fun getAllNicknames(): Map<String, String>

    // ==========================================
    // DESTROY / REMOTE KILL SETTINGS
    // ==========================================

    /**
     * Set the destroy PIN (stored as hash for security).
     */
    fun setDestroyPin(pin: String)

    /**
     * Verify the destroy PIN by comparing hash.
     * @return true if the provided PIN matches the stored hash
     */
    fun verifyDestroyPin(pin: String): Boolean

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
     * Verify the remote kill secret phrase by comparing hash.
     * @param phrase The phrase to verify (will be hashed and compared)
     * @return true if the provided phrase matches the stored hash
     */
    fun verifyRemoteKillPhrase(phrase: String): Boolean

    /**
     * Set the remote kill secret phrase (stored as hash for security).
     * NOTE: The phrase cannot be recovered after setting. User must remember it.
     */
    fun setRemoteKillPhrase(phrase: String)

    /**
     * Check if remote kill phrase is set.
     */
    fun hasRemoteKillPhrase(): Boolean

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

    // ==========================================
    // CONVERSATION IDs (ZMSG v4)
    // ==========================================

    /**
     * Get the conversation ID for a peer address.
     * Returns null if no conversation ID is stored for this peer.
     */
    fun getConversationId(peerAddress: String): String?

    /**
     * Set the conversation ID for a peer address.
     * This is called when we initiate a new conversation.
     */
    fun setConversationId(peerAddress: String, convId: String)

    /**
     * Get the peer address for a conversation ID.
     * Returns null if no peer is associated with this conversation ID.
     */
    fun getPeerByConversationId(convId: String): String?

    /**
     * Store a mapping from conversation ID to peer address.
     * This is called when we receive a new conversation INIT from someone else.
     */
    fun setConversationMapping(convId: String, peerAddress: String)

    /**
     * Get all conversation mappings (convId -> peerAddress).
     */
    fun getAllConversationMappings(): Map<String, String>

    // ==========================================
    // NOTIFICATION PRIVACY
    // ==========================================

    /**
     * Get the current notification privacy level.
     * @return The notification privacy level, default is FULL_PREVIEW
     */
    fun getNotificationPrivacy(): NotificationPrivacy

    /**
     * Set the notification privacy level.
     * @param level The privacy level to set
     */
    fun setNotificationPrivacy(level: NotificationPrivacy)

    // ==========================================
    // MESSAGE DRAFTS (Auto-Save)
    // ==========================================

    /**
     * Get the draft message for a peer address.
     * @param peerAddress The peer's Zcash address
     * @return The draft text, or null if no draft exists
     */
    fun getDraft(peerAddress: String): String?

    /**
     * Save a draft message for a peer address.
     * @param peerAddress The peer's Zcash address
     * @param draft The draft text (empty string to clear)
     */
    fun setDraft(peerAddress: String, draft: String)

    /**
     * Clear the draft for a peer address.
     * Called when a message is successfully sent.
     */
    fun clearDraft(peerAddress: String)

    /**
     * Get all drafts (for showing "Draft" indicator in conversation list).
     * @return Map of peerAddress -> draft text
     */
    fun getAllDrafts(): Map<String, String>

    /**
     * Check if a draft exists for a peer address.
     */
    fun hasDraft(peerAddress: String): Boolean

    // ==========================================
    // E2E ENCRYPTION
    // ==========================================

    /**
     * Check if E2E encryption is enabled for a conversation.
     */
    fun isE2EEnabled(peerAddress: String): Boolean

    /**
     * Enable/disable E2E encryption for a conversation.
     */
    fun setE2EEnabled(peerAddress: String, enabled: Boolean)

    /**
     * Get our private key for E2E encryption with a peer.
     * @return Base64 encoded private key, or null if not set
     */
    fun getE2EPrivateKey(peerAddress: String): String?

    /**
     * Get the peer's public key for E2E encryption.
     * @return Base64 encoded public key, or null if not received
     */
    fun getE2EPeerPublicKey(peerAddress: String): String?

    /**
     * Get our public key for E2E encryption with a peer.
     * @return Base64 encoded public key, or null if not generated
     */
    fun getE2EOurPublicKey(peerAddress: String): String?

    /**
     * Store E2E keys for a conversation.
     * @param ourPublicKey Our public key (Base64)
     * @param ourPrivateKey Our private key (Base64)
     */
    fun setE2EOurKeys(peerAddress: String, ourPublicKey: String, ourPrivateKey: String)

    /**
     * Store the peer's public key for E2E encryption.
     * @param peerPublicKey Peer's public key (Base64)
     */
    fun setE2EPeerPublicKey(peerAddress: String, peerPublicKey: String)

    /**
     * Check if E2E key exchange is complete (both keys available).
     */
    fun isE2EKeyExchangeComplete(peerAddress: String): Boolean

    /**
     * Clear E2E keys for a conversation.
     */
    fun clearE2EKeys(peerAddress: String)

    /**
     * Get the E2E key derivation version for a peer.
     * @return Key version (1 = legacy SHA-256, 2 = HKDF), defaults to 1 for backwards compatibility
     */
    fun getE2EKeyVersion(peerAddress: String): Int

    /**
     * Set the E2E key derivation version for a peer.
     * Should be called when establishing new keys with HKDF (version 2).
     */
    fun setE2EKeyVersion(peerAddress: String, version: Int)

    // ==========================================
    // GROUP CHAT
    // ==========================================

    /**
     * Save group info (as JSON string).
     * @param groupId The unique group identifier
     * @param groupInfoJson JSON representation of GroupInfo
     */
    fun saveGroupInfo(groupId: String, groupInfoJson: String)

    /**
     * Get group info by ID.
     * @return JSON string of GroupInfo, or null if not found
     */
    fun getGroupInfo(groupId: String): String?

    /**
     * Get all group IDs.
     */
    fun getAllGroupIds(): Set<String>

    /**
     * Delete a group.
     */
    fun deleteGroup(groupId: String)

    /**
     * Save group members (as JSON string).
     * @param groupId The group ID
     * @param membersJson JSON array of GroupMember objects
     */
    fun saveGroupMembers(groupId: String, membersJson: String)

    /**
     * Get group members.
     * @return JSON array string of GroupMember objects
     */
    fun getGroupMembers(groupId: String): String?

    /**
     * Save group key for encryption.
     * @param groupId The group ID
     * @param keyEpoch The key epoch
     * @param encryptedKey Base64 encoded encrypted group key
     */
    fun saveGroupKey(groupId: String, keyEpoch: Int, encryptedKey: String)

    /**
     * Get group key for a specific epoch.
     * @return Base64 encoded encrypted group key
     */
    fun getGroupKey(groupId: String, keyEpoch: Int): String?

    /**
     * Get the current key epoch for a group.
     */
    fun getGroupKeyEpoch(groupId: String): Int

    /**
     * Set the current key epoch for a group.
     */
    fun setGroupKeyEpoch(groupId: String, epoch: Int)

    /**
     * Get draft for a group conversation.
     */
    fun getGroupDraft(groupId: String): String?

    /**
     * Set draft for a group conversation.
     */
    fun setGroupDraft(groupId: String, draft: String)

    /**
     * Clear draft for a group conversation.
     */
    fun clearGroupDraft(groupId: String)

    /**
     * Get all group drafts.
     */
    fun getAllGroupDrafts(): Map<String, String>

    /**
     * Get the sequence number for sending group messages.
     */
    fun getGroupMessageSequence(groupId: String): Long

    /**
     * Increment and return the next sequence number for group messages.
     */
    fun incrementGroupMessageSequence(groupId: String): Long

    /**
     * Get stored messages for a group (JSON array string).
     */
    fun getGroupMessages(groupId: String): String?

    /**
     * Save messages for a group (JSON array string).
     */
    fun saveGroupMessages(groupId: String, messagesJson: String)

    // ==========================================
    // IDENTITY MANAGEMENT
    // ==========================================

    /**
     * Get all contact addresses from the address book (nicknames storage).
     * @return Set of all addresses that have nicknames set
     */
    fun getAllContactAddresses(): Set<String>

    /**
     * Get all peer addresses from conversation mappings.
     * @return Set of all peer addresses that have conversations
     */
    fun getAllConversationPeerAddresses(): Set<String>
}

/**
 * SharedPreferences-based implementation of ZchatPreferences.
 *
 * SECURITY: Sensitive data (E2E keys, group keys) uses EncryptedSharedPreferences
 * with AES256-GCM encryption backed by Android Keystore.
 * Non-sensitive data (drafts, nicknames) uses regular SharedPreferences.
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

    // Separate prefs file for conversation ID mappings
    private val convIdPrefs: SharedPreferences = context.getSharedPreferences(
        CONV_ID_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Reverse mapping: peer address -> convId
    private val peerToConvIdPrefs: SharedPreferences = context.getSharedPreferences(
        PEER_TO_CONV_ID_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Contact nicknames: address -> nickname
    private val nicknamePrefs: SharedPreferences = context.getSharedPreferences(
        NICKNAME_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Message drafts: peerAddress -> draft text
    private val draftPrefs: SharedPreferences = context.getSharedPreferences(
        DRAFT_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // SECURITY: E2E encryption keys stored in EncryptedSharedPreferences
    // Keys are encrypted with AES256-GCM, master key stored in Android Keystore
    private val e2ePrefs: SharedPreferences = createEncryptedPrefs(context, E2E_PREFS_NAME)

    // Group chat storage
    private val groupInfoPrefs: SharedPreferences = context.getSharedPreferences(
        GROUP_INFO_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val groupMembersPrefs: SharedPreferences = context.getSharedPreferences(
        GROUP_MEMBERS_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // SECURITY: Group encryption keys stored in EncryptedSharedPreferences
    private val groupKeysPrefs: SharedPreferences = createEncryptedPrefs(context, GROUP_KEYS_PREFS_NAME)

    private val groupDraftPrefs: SharedPreferences = context.getSharedPreferences(
        GROUP_DRAFT_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val groupSeqPrefs: SharedPreferences = context.getSharedPreferences(
        GROUP_SEQ_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val groupMsgPrefs: SharedPreferences = context.getSharedPreferences(
        GROUP_MSG_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * Create EncryptedSharedPreferences for secure storage of sensitive data.
     * Falls back to regular SharedPreferences if encryption fails (e.g., older devices).
     *
     * SECURITY: Uses AES256-GCM encryption with master key stored in Android Keystore.
     * The master key is hardware-backed on devices with secure hardware (TEE/StrongBox).
     */
    private fun createEncryptedPrefs(context: Context, name: String): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback for devices that don't support encryption (rare)
            // Log warning but don't crash - better to have some storage than none
            Log.w("ZchatPreferences", "Failed to create encrypted prefs for $name, using regular prefs", e)
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val PREFS_NAME = "zchat_preferences"
        private const val PEER_STATUS_PREFS_NAME = "zchat_peer_statuses"
        private const val CONV_ID_PREFS_NAME = "zchat_conv_ids"          // convId -> peerAddress
        private const val PEER_TO_CONV_ID_PREFS_NAME = "zchat_peer_conv_ids"  // peerAddress -> convId
        private const val NICKNAME_PREFS_NAME = "zchat_nicknames"        // address -> nickname
        private const val DRAFT_PREFS_NAME = "zchat_drafts"            // peerAddress -> draft text
        private const val E2E_PREFS_NAME = "zchat_e2e_keys_encrypted"  // E2E encryption keys (AES256-GCM encrypted)
        // Group chat prefs
        private const val GROUP_INFO_PREFS_NAME = "zchat_group_info"     // groupId -> GroupInfo JSON
        private const val GROUP_MEMBERS_PREFS_NAME = "zchat_group_members" // groupId -> members JSON array
        private const val GROUP_KEYS_PREFS_NAME = "zchat_group_keys_encrypted"  // groupId_epoch -> group key (AES256-GCM encrypted)
        private const val GROUP_DRAFT_PREFS_NAME = "zchat_group_drafts"  // groupId -> draft text
        private const val GROUP_SEQ_PREFS_NAME = "zchat_group_seq"       // groupId -> sequence number
        private const val GROUP_MSG_PREFS_NAME = "zchat_group_messages" // groupId -> messages JSON array
        private const val GROUP_IDS_KEY = "group_ids"                    // Set of all group IDs
        private const val GROUP_EPOCH_PREFIX = "epoch_"                  // Prefix for epoch storage
        // E2E key prefixes
        private const val E2E_ENABLED_PREFIX = "e2e_enabled_"
        private const val E2E_OUR_PUBLIC_PREFIX = "e2e_our_pub_"
        private const val E2E_OUR_PRIVATE_PREFIX = "e2e_our_priv_"
        private const val E2E_PEER_PUBLIC_PREFIX = "e2e_peer_pub_"
        private const val E2E_KEY_VERSION_PREFIX = "e2e_key_ver_"
        private const val KEY_ACKNOWLEDGED_MESSAGE_COST = "acknowledged_message_cost"
        private const val KEY_HIDDEN_MESSAGES = "hidden_message_ids"
        private const val KEY_USER_STATUS = "user_status"
        private const val KEY_USER_STATUS_TIMESTAMP = "user_status_timestamp"
        private const val KEY_CUSTOM_MEMO_TEMPLATES = "custom_memo_templates"
        private const val KEY_FONT_SIZE_SCALE = "font_size_scale"
        // Destroy / Remote Kill keys
        private const val KEY_DESTROY_PIN = "destroy_pin"
        private const val KEY_REMOTE_KILL_ENABLED = "remote_kill_enabled"
        private const val KEY_REMOTE_KILL_PHRASE_HASH = "remote_kill_phrase_hash"  // SHA-256 hash, not plaintext
        private const val KEY_REMOTE_KILL_AMOUNT = "remote_kill_amount"
        private const val DEFAULT_REMOTE_KILL_AMOUNT = 1337L // 0.00001337 ZEC - unique amount
        // Notification Privacy
        private const val KEY_NOTIFICATION_PRIVACY = "notification_privacy"
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
    // CONTACT NICKNAMES IMPLEMENTATION
    // ==========================================

    override fun getNickname(address: String): String? {
        return nicknamePrefs.getString(address, null)
    }

    override fun setNickname(address: String, nickname: String) {
        if (nickname.isBlank()) {
            // Clear nickname if empty
            nicknamePrefs.edit().remove(address).apply()
        } else {
            nicknamePrefs.edit().putString(address, nickname.trim()).apply()
        }
    }

    override fun getDisplayName(address: String): String {
        // Return nickname if set, otherwise truncate address
        val nickname = getNickname(address)
        if (!nickname.isNullOrBlank()) {
            return nickname
        }
        // Truncate: first 8 chars + "..." + last 6 chars
        return if (address.length > 20) {
            "${address.take(8)}...${address.takeLast(6)}"
        } else {
            address
        }
    }

    override fun getAllNicknames(): Map<String, String> {
        return nicknamePrefs.all
            .filterValues { it is String && it.isNotBlank() }
            .mapValues { it.value as String }
    }

    // ==========================================
    // DESTROY / REMOTE KILL IMPLEMENTATION
    // ==========================================

    override fun setDestroyPin(pin: String) {
        // Store hash of PIN for security (plaintext never stored)
        val hashedPin = hashPin(pin)
        prefs.edit().putString(KEY_DESTROY_PIN, hashedPin).apply()
    }

    override fun verifyDestroyPin(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_DESTROY_PIN, null) ?: return false
        return hashPin(pin) == storedHash
    }

    override fun hasDestroyPin(): Boolean {
        return prefs.getString(KEY_DESTROY_PIN, null) != null
    }

    /**
     * Hash a PIN using SHA-256 for secure storage.
     */
    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    override fun isRemoteKillEnabled(): Boolean {
        return prefs.getBoolean(KEY_REMOTE_KILL_ENABLED, false)
    }

    override fun setRemoteKillEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMOTE_KILL_ENABLED, enabled).apply()
    }

    override fun verifyRemoteKillPhrase(phrase: String): Boolean {
        val storedHash = prefs.getString(KEY_REMOTE_KILL_PHRASE_HASH, null) ?: return false
        return hashPhrase(phrase) == storedHash
    }

    override fun setRemoteKillPhrase(phrase: String) {
        // Store hash of phrase for security (plaintext never stored)
        val hashedPhrase = hashPhrase(phrase)
        prefs.edit().putString(KEY_REMOTE_KILL_PHRASE_HASH, hashedPhrase).apply()
    }

    override fun hasRemoteKillPhrase(): Boolean {
        return prefs.getString(KEY_REMOTE_KILL_PHRASE_HASH, null) != null
    }

    /**
     * Hash a phrase using SHA-256 for secure storage.
     * Used for both PIN and remote kill phrase.
     */
    private fun hashPhrase(phrase: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(phrase.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
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
        convIdPrefs.edit().clear().apply()
        peerToConvIdPrefs.edit().clear().apply()
        nicknamePrefs.edit().clear().apply()
        draftPrefs.edit().clear().apply()
        e2ePrefs.edit().clear().apply()
        // Group chat prefs
        groupInfoPrefs.edit().clear().apply()
        groupMembersPrefs.edit().clear().apply()
        groupKeysPrefs.edit().clear().apply()
        groupDraftPrefs.edit().clear().apply()
        groupSeqPrefs.edit().clear().apply()
        groupMsgPrefs.edit().clear().apply()
    }

    // ==========================================
    // CONVERSATION IDs IMPLEMENTATION
    // ==========================================

    override fun getConversationId(peerAddress: String): String? {
        val result = peerToConvIdPrefs.getString(peerAddress, null)
        android.util.Log.d("ZCHAT_CONVID", "getConversationId(${peerAddress.redactAddress()}) = ${result?.redactConvId()}")
        return result
    }

    override fun setConversationId(peerAddress: String, convId: String) {
        android.util.Log.d("ZCHAT_CONVID", "setConversationId: peer=${peerAddress.redactAddress()}, convId=${convId.redactConvId()}")
        // Store both directions of the mapping
        peerToConvIdPrefs.edit().putString(peerAddress, convId).apply()
        convIdPrefs.edit().putString(convId, peerAddress).apply()
    }

    override fun getPeerByConversationId(convId: String): String? {
        val result = convIdPrefs.getString(convId, null)
        android.util.Log.d("ZCHAT_CONVID", "getPeerByConversationId(${convId.redactConvId()}) = ${result?.redactAddress()}")
        return result
    }

    override fun setConversationMapping(convId: String, peerAddress: String) {
        android.util.Log.d("ZCHAT_CONVID", "setConversationMapping: convId=${convId.redactConvId()}, peer=${peerAddress.redactAddress()}")
        // Store both directions of the mapping
        convIdPrefs.edit().putString(convId, peerAddress).apply()
        peerToConvIdPrefs.edit().putString(peerAddress, convId).apply()
    }

    override fun getAllConversationMappings(): Map<String, String> {
        return convIdPrefs.all
            .filterValues { it is String }
            .mapValues { it.value as String }
    }

    // ==========================================
    // NOTIFICATION PRIVACY IMPLEMENTATION
    // ==========================================

    override fun getNotificationPrivacy(): NotificationPrivacy {
        val value = prefs.getString(KEY_NOTIFICATION_PRIVACY, null)
        return if (value != null) {
            try {
                NotificationPrivacy.valueOf(value)
            } catch (e: IllegalArgumentException) {
                NotificationPrivacy.FULL_PREVIEW
            }
        } else {
            NotificationPrivacy.FULL_PREVIEW
        }
    }

    override fun setNotificationPrivacy(level: NotificationPrivacy) {
        prefs.edit().putString(KEY_NOTIFICATION_PRIVACY, level.name).apply()
    }

    // ==========================================
    // MESSAGE DRAFTS IMPLEMENTATION
    // ==========================================

    override fun getDraft(peerAddress: String): String? {
        return draftPrefs.getString(peerAddress, null)
    }

    override fun setDraft(peerAddress: String, draft: String) {
        if (draft.isBlank()) {
            // Clear draft if empty
            draftPrefs.edit().remove(peerAddress).apply()
        } else {
            draftPrefs.edit().putString(peerAddress, draft).apply()
        }
    }

    override fun clearDraft(peerAddress: String) {
        draftPrefs.edit().remove(peerAddress).apply()
    }

    override fun getAllDrafts(): Map<String, String> {
        return draftPrefs.all
            .filterValues { it is String && it.isNotBlank() }
            .mapValues { it.value as String }
    }

    override fun hasDraft(peerAddress: String): Boolean {
        val draft = getDraft(peerAddress)
        return !draft.isNullOrBlank()
    }

    // ==========================================
    // E2E ENCRYPTION IMPLEMENTATION
    // ==========================================

    override fun isE2EEnabled(peerAddress: String): Boolean {
        return e2ePrefs.getBoolean("$E2E_ENABLED_PREFIX$peerAddress", false)
    }

    override fun setE2EEnabled(peerAddress: String, enabled: Boolean) {
        e2ePrefs.edit().putBoolean("$E2E_ENABLED_PREFIX$peerAddress", enabled).apply()
    }

    override fun getE2EPrivateKey(peerAddress: String): String? {
        return e2ePrefs.getString("$E2E_OUR_PRIVATE_PREFIX$peerAddress", null)
    }

    override fun getE2EPeerPublicKey(peerAddress: String): String? {
        return e2ePrefs.getString("$E2E_PEER_PUBLIC_PREFIX$peerAddress", null)
    }

    override fun getE2EOurPublicKey(peerAddress: String): String? {
        return e2ePrefs.getString("$E2E_OUR_PUBLIC_PREFIX$peerAddress", null)
    }

    override fun setE2EOurKeys(peerAddress: String, ourPublicKey: String, ourPrivateKey: String) {
        e2ePrefs.edit()
            .putString("$E2E_OUR_PUBLIC_PREFIX$peerAddress", ourPublicKey)
            .putString("$E2E_OUR_PRIVATE_PREFIX$peerAddress", ourPrivateKey)
            .apply()
    }

    override fun setE2EPeerPublicKey(peerAddress: String, peerPublicKey: String) {
        e2ePrefs.edit()
            .putString("$E2E_PEER_PUBLIC_PREFIX$peerAddress", peerPublicKey)
            .apply()
    }

    override fun isE2EKeyExchangeComplete(peerAddress: String): Boolean {
        val ourPrivate = getE2EPrivateKey(peerAddress)
        val peerPublic = getE2EPeerPublicKey(peerAddress)
        return ourPrivate != null && peerPublic != null
    }

    override fun clearE2EKeys(peerAddress: String) {
        e2ePrefs.edit()
            .remove("$E2E_ENABLED_PREFIX$peerAddress")
            .remove("$E2E_OUR_PUBLIC_PREFIX$peerAddress")
            .remove("$E2E_OUR_PRIVATE_PREFIX$peerAddress")
            .remove("$E2E_PEER_PUBLIC_PREFIX$peerAddress")
            .remove("$E2E_KEY_VERSION_PREFIX$peerAddress")
            .apply()
    }

    override fun getE2EKeyVersion(peerAddress: String): Int {
        // Default to version 1 (legacy) for backwards compatibility with existing keys
        return e2ePrefs.getInt("$E2E_KEY_VERSION_PREFIX$peerAddress", 1)
    }

    override fun setE2EKeyVersion(peerAddress: String, version: Int) {
        e2ePrefs.edit()
            .putInt("$E2E_KEY_VERSION_PREFIX$peerAddress", version)
            .apply()
    }

    // ==========================================
    // GROUP CHAT IMPLEMENTATION
    // ==========================================

    override fun saveGroupInfo(groupId: String, groupInfoJson: String) {
        // Save group info
        groupInfoPrefs.edit().putString(groupId, groupInfoJson).apply()
        // Add to group IDs set
        val groupIds = getAllGroupIds().toMutableSet()
        groupIds.add(groupId)
        prefs.edit().putStringSet(GROUP_IDS_KEY, groupIds).apply()
    }

    override fun getGroupInfo(groupId: String): String? {
        return groupInfoPrefs.getString(groupId, null)
    }

    override fun getAllGroupIds(): Set<String> {
        return prefs.getStringSet(GROUP_IDS_KEY, emptySet()) ?: emptySet()
    }

    override fun deleteGroup(groupId: String) {
        // Remove group info
        groupInfoPrefs.edit().remove(groupId).apply()
        // Remove members
        groupMembersPrefs.edit().remove(groupId).apply()
        // Remove draft
        groupDraftPrefs.edit().remove(groupId).apply()
        // Remove sequence
        groupSeqPrefs.edit().remove(groupId).apply()
        // Remove from group IDs set
        val groupIds = getAllGroupIds().toMutableSet()
        groupIds.remove(groupId)
        prefs.edit().putStringSet(GROUP_IDS_KEY, groupIds).apply()
        // Remove all keys for this group
        val keysToRemove = groupKeysPrefs.all.keys.filter { it.startsWith("${groupId}_") }
        val keysEditor = groupKeysPrefs.edit()
        keysToRemove.forEach { keysEditor.remove(it) }
        keysEditor.apply()
    }

    override fun saveGroupMembers(groupId: String, membersJson: String) {
        groupMembersPrefs.edit().putString(groupId, membersJson).apply()
    }

    override fun getGroupMembers(groupId: String): String? {
        return groupMembersPrefs.getString(groupId, null)
    }

    override fun saveGroupKey(groupId: String, keyEpoch: Int, encryptedKey: String) {
        groupKeysPrefs.edit().putString("${groupId}_$keyEpoch", encryptedKey).apply()
    }

    override fun getGroupKey(groupId: String, keyEpoch: Int): String? {
        return groupKeysPrefs.getString("${groupId}_$keyEpoch", null)
    }

    override fun getGroupKeyEpoch(groupId: String): Int {
        return prefs.getInt("$GROUP_EPOCH_PREFIX$groupId", 0)
    }

    override fun setGroupKeyEpoch(groupId: String, epoch: Int) {
        prefs.edit().putInt("$GROUP_EPOCH_PREFIX$groupId", epoch).apply()
    }

    override fun getGroupDraft(groupId: String): String? {
        return groupDraftPrefs.getString(groupId, null)
    }

    override fun setGroupDraft(groupId: String, draft: String) {
        if (draft.isBlank()) {
            groupDraftPrefs.edit().remove(groupId).apply()
        } else {
            groupDraftPrefs.edit().putString(groupId, draft).apply()
        }
    }

    override fun clearGroupDraft(groupId: String) {
        groupDraftPrefs.edit().remove(groupId).apply()
    }

    override fun getAllGroupDrafts(): Map<String, String> {
        return groupDraftPrefs.all
            .filterValues { it is String && it.isNotBlank() }
            .mapValues { it.value as String }
    }

    override fun getGroupMessageSequence(groupId: String): Long {
        return groupSeqPrefs.getLong(groupId, 0L)
    }

    override fun incrementGroupMessageSequence(groupId: String): Long {
        val current = getGroupMessageSequence(groupId)
        val next = current + 1
        groupSeqPrefs.edit().putLong(groupId, next).apply()
        return next
    }

    override fun getGroupMessages(groupId: String): String? {
        return groupMsgPrefs.getString(groupId, null)
    }

    override fun saveGroupMessages(groupId: String, messagesJson: String) {
        groupMsgPrefs.edit().putString(groupId, messagesJson).apply()
    }

    // ==========================================
    // IDENTITY MANAGEMENT IMPLEMENTATION
    // ==========================================

    override fun getAllContactAddresses(): Set<String> {
        // Return all addresses that have nicknames (address book contacts)
        return nicknamePrefs.all.keys
    }

    override fun getAllConversationPeerAddresses(): Set<String> {
        // Return all peer addresses from conversation mappings
        return peerToConvIdPrefs.all.keys
    }
}
