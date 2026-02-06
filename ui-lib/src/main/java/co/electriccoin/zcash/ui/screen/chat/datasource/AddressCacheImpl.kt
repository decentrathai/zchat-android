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

    // In-memory cache for faster lookups - synchronized access
    private val memoryCache = mutableMapOf<String, String>()

    // Conversation partners - addresses we've communicated with
    private val conversationPartners = mutableSetOf<String>()

    // Flag to indicate cache is fully loaded
    @Volatile
    private var cacheLoaded = false

    init {
        // Load existing cache into memory SYNCHRONOUSLY before any reads
        synchronized(memoryCache) {
            loadFromPrefs()
            loadConversationPartners()
            cacheLoaded = true
        }
    }

    private fun loadFromPrefs() {
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is String) {
                val hash = key.removePrefix(KEY_PREFIX)
                memoryCache[hash] = value
            }
        }
        android.util.Log.d("ZCHAT_CACHE", "Loaded ${memoryCache.size} addresses from cache")
    }

    private fun loadConversationPartners() {
        val partners = prefs.getStringSet(KEY_CONVERSATION_PARTNERS, emptySet()) ?: emptySet()
        conversationPartners.addAll(partners)
        android.util.Log.d("ZCHAT_CACHE", "Loaded ${conversationPartners.size} conversation partners")
    }

    /**
     * Cache an address mapping with validation.
     * IMPORTANT: Only caches if the address is valid AND we have high confidence
     * the mapping is correct. Call with validated=true only when the mapping
     * was established from trusted source (INIT message, contact book, etc.)
     */
    override fun cacheAddress(hash: String, address: String) {
        cacheAddressWithValidation(hash, address, validated = false)
    }

    /**
     * Cache address with explicit validation flag.
     * @param validated If true, skip validation (trusted source). If false, validate thoroughly.
     */
    fun cacheAddressWithValidation(hash: String, address: String, validated: Boolean) {
        // Don't cache invalid addresses
        if (!isValidZcashAddress(address)) {
            android.util.Log.w("ZCHAT_CACHE", "Rejected invalid address: ${address.take(10)}...")
            return
        }

        // Don't overwrite existing mapping with potentially wrong one
        // unless the source is validated/trusted
        synchronized(memoryCache) {
            val existing = memoryCache[hash]
            if (existing != null && existing != address && !validated) {
                android.util.Log.w("ZCHAT_CACHE", "SKIPPED overwriting hash $hash: existing=${existing.take(10)}... new=${address.take(10)}...")
                return
            }

            memoryCache[hash] = address
        }
        prefs.edit().putString("$KEY_PREFIX$hash", address).commit() // Use commit() for reliability
    }

    override fun getAddress(hash: String): String? {
        // Wait for cache to be loaded
        if (!cacheLoaded) {
            android.util.Log.w("ZCHAT_CACHE", "Cache not yet loaded, waiting...")
            synchronized(memoryCache) {
                // Re-check after acquiring lock
            }
        }
        return synchronized(memoryCache) { memoryCache[hash] }
    }

    /**
     * Get address with legacy hash fallback.
     * First tries the provided hash, then tries legacy 12-char hash if needed.
     */
    fun getAddressWithLegacyFallback(hash: String, address: String?): String? {
        // Try direct lookup first
        val direct = getAddress(hash)
        if (direct != null) return direct

        // If this looks like a new 16-char hash and we have the original address,
        // try legacy 12-char hash lookup
        if (hash.length == 16 && address != null) {
            val legacyHash = ZMSGProtocol.generateLegacyAddressHash(address)
            val legacyResult = getAddress(legacyHash)
            if (legacyResult != null) {
                // Migrate: cache new hash pointing to same address
                cacheAddressWithValidation(hash, legacyResult, validated = true)
                return legacyResult
            }
        }

        return null
    }

    override fun hasAddress(hash: String): Boolean {
        return synchronized(memoryCache) { memoryCache.containsKey(hash) }
    }

    override fun getAllCachedAddresses(): Map<String, String> {
        return synchronized(memoryCache) { memoryCache.toMap() }
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
     * CONSERVATIVE APPROACH: Only return a match if we have HIGH CONFIDENCE.
     * We NEVER guess when there are multiple possibilities - this prevents misrouting.
     */
    override fun findConversationPartnerByHash(hash: String): String? {
        // First, try direct lookup (HIGH CONFIDENCE)
        val directMatch = getAddress(hash)
        if (directMatch != null && isConversationPartner(directMatch)) {
            android.util.Log.d("ZCHAT_CACHE", "findConversationPartnerByHash: Direct match found for hash $hash")
            return directMatch
        }

        // Try legacy hash lookup if this is a new format hash
        if (hash.length == 16) {
            // Check each partner's legacy hash
            val partners = synchronized(conversationPartners) { conversationPartners.toList() }
            for (partner in partners) {
                val legacyHash = ZMSGProtocol.generateLegacyAddressHash(partner)
                if (hash.startsWith(legacyHash)) {
                    android.util.Log.d("ZCHAT_CACHE", "findConversationPartnerByHash: Legacy hash prefix match for $partner")
                    return partner
                }
            }
        }

        // REMOVED: Single-partner heuristic was causing misrouting.
        // If we don't have a direct hash match, we MUST NOT guess.
        // The message will go to a new conversation, which is better than misrouting.
        android.util.Log.d("ZCHAT_CACHE", "findConversationPartnerByHash: No confident match for hash $hash (partners: ${conversationPartners.size})")
        return null
    }

    companion object {
        private const val PREFS_NAME = "zchat_address_cache"
        private const val KEY_PREFIX = "addr_"
        private const val KEY_CONVERSATION_PARTNERS = "conversation_partners"
    }
}
