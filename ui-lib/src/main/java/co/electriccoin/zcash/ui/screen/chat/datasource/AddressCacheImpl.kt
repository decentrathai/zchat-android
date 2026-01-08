package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
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

    init {
        // Load existing cache into memory
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is String) {
                val hash = key.removePrefix(KEY_PREFIX)
                memoryCache[hash] = value
            }
        }
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

    companion object {
        private const val PREFS_NAME = "zchat_address_cache"
        private const val KEY_PREFIX = "addr_"
    }
}
