package co.electriccoin.zcash.ui.screen.chat.crypto

import co.electriccoin.zcash.ui.screen.chat.filesharing.FileDownloadCache
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD for [FileDownloadCache] — local disk cache for decrypted file data,
 * keyed by ZFILE hash. Prevents re-downloading on re-scan.
 */
class FileDownloadCacheTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun put_then_get_returns_same_bytes() {
        val cache = FileDownloadCache(tempDir.root)
        val data = "test image data".toByteArray()
        cache.put("abc123", data)
        val retrieved = cache.get("abc123")
        assertContentEquals(data, retrieved)
    }

    @Test
    fun get_missing_key_returns_null() {
        val cache = FileDownloadCache(tempDir.root)
        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun has_returns_true_for_cached_key() {
        val cache = FileDownloadCache(tempDir.root)
        assertFalse(cache.has("key1"))
        cache.put("key1", "data".toByteArray())
        assertTrue(cache.has("key1"))
    }

    @Test
    fun different_keys_store_different_data() {
        val cache = FileDownloadCache(tempDir.root)
        cache.put("img1", "first".toByteArray())
        cache.put("img2", "second".toByteArray())
        assertContentEquals("first".toByteArray(), cache.get("img1"))
        assertContentEquals("second".toByteArray(), cache.get("img2"))
    }
}
