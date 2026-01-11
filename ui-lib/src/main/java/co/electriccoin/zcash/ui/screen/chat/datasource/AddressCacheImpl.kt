package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local address cache implementation using SharedPreferences.
 * Stores hash → address mappings on device only.
 * No data is sent to any server.
 */
class AddressCacheImpl(context: Context) : AddressCache {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val mutex = Mutex()

    // In-memory cache for faster lookups
    private val memoryCache = mutableMapOf<String, String>()

    // Conversation partners - addresses we've communicated with
    private val conversationPartners = mutableSetOf<String>()

    init {
        // Load existing cache into memory
        loadFromPrefs()
        loadConversationPartners()
    }

    private fun loadFromPrefs() {
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is String) {
                val hash = key.removePrefix(KEY_PREFIX)
                memoryCache[hash] = value
            }
        }
    }

    private fun loadConversationPartners() {
        val partners = prefs.getStringSet(KEY_CONVERSATION_PARTNERS, emptySet()) ?: emptySet()
        conversationPartners.addAll(partners)
    }

    override fun cacheAddress(hash: String, address: String) {
        // Don't cache invalid addresses
        if (!isValidZcashAddress(address)) return

        memoryCache[hash] = address
        prefs.edit().putString("$KEY_PREFIX$hash", address).apply()
    }

    override fun getAddress(hash: String): String? {
        return memoryCache[hash]
    }

    override fun hasAddress(hash: String): Boolean {
        return memoryCache.containsKey(hash)
    }

    override fun getAllCachedAddresses(): Map<String, String> {
        return memoryCache.toMap()
    }

    /**
     * Check if an address looks like a valid Zcash address
     */
    private fun isValidZcashAddress(address: String): Boolean {
        return (address.startsWith("u1") && address.length > 100) ||
                (address.startsWith("zs") && address.length > 70)
    }

    /**
     * Clear all cached addresses (for testing/reset)
     */
    fun clearCache() {
        memoryCache.clear()
        prefs.edit().clear().apply()
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            totalEntries = memoryCache.size,
            unifiedAddresses = memoryCache.values.count { it.startsWith("u1") },
            saplingAddresses = memoryCache.values.count { it.startsWith("zs") }
        )
    }

    data class CacheStats(
        val totalEntries: Int,
        val unifiedAddresses: Int,
        val saplingAddresses: Int
    )

    // ==========================================
    // CONVERSATION PARTNER TRACKING
    // ==========================================

    override fun addConversationPartner(address: String) {
        if (!isValidZcashAddress(address)) return
        if (conversationPartners.add(address)) {
            // Also cache the hash for this address
            val hash = ZMSGProtocol.generateAddressHash(address)
            cacheAddress(hash, address)
            // Persist to SharedPreferences
            prefs.edit().putStringSet(KEY_CONVERSATION_PARTNERS, conversationPartners.toSet()).apply()
        }
    }

    override fun getConversationPartners(): Set<String> {
        return conversationPartners.toSet()
    }

    override fun isConversationPartner(address: String): Boolean {
        return conversationPartners.contains(address)
    }

    /**
     * Find a conversation partner when we receive a message with an unknown hash.
     *
     * This handles the "diversified address" problem:
     * - User A sends to User B (address X)
     * - User B replies using diversified address Y (different from X)
     * - We can't match hash(Y) because we only know address X
     *
     * Solution: If we have only ONE active conversation partner,
     * it's highly likely the unknown message is from them.
     * If we have multiple, we return null and let other heuristics handle it.
     */
    override fun findConversationPartnerByHash(hash: String): String? {
        // First, try direct lookup
        val directMatch = getAddress(hash)
        if (directMatch != null && isConversationPartner(directMatch)) {
            return directMatch
        }

        // If only one conversation partner, it's probably from them
        // This is a heuristic that works well for typical 1-on-1 conversations
        if (conversationPartners.size == 1) {
            return conversationPartners.first()
        }

        // Multiple partners - can't determine which one sent this message
        // The ChatViewModel will need to use additional heuristics (like timing)
        return null
    }

    companion object {
        private const val PREFS_NAME = "zchat_address_cache"
        private const val KEY_PREFIX = "addr_"
        private const val KEY_CONVERSATION_PARTNERS = "conversation_partners"
    }
}
