package co.electriccoin.zcash.ui.screen.chat.crypto

import co.electriccoin.zcash.ui.screen.chat.filesharing.FileDownloadCache
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD for [FileDownloadCache] — local disk cache for decrypted file data,
 * keyed by ZFILE hash. Prevents re-downloading on re-scan.
 *
 * The hash is used directly as a filename, so the cache requires a bare 32-char
 * lowercase-hex string (a SHA-256 prefix) — this defends against path traversal
 * from a peer-supplied hash. These tests use valid 32-hex keys accordingly.
 */
class FileDownloadCacheTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val hashA = "a1b2c3d4e5f6000000000000000000ab"
    private val hashB = "0123456789abcdef0123456789abcdef"
    private val missingHash = "ffffffffffffffffffffffffffffffff"

    @Test
    fun put_then_get_returns_same_bytes() {
        val cache = FileDownloadCache(tempDir.root)
        val data = "test image data".toByteArray()
        cache.put(hashA, data)
        val retrieved = cache.get(hashA)
        assertContentEquals(data, retrieved)
    }

    @Test
    fun get_missing_key_returns_null() {
        val cache = FileDownloadCache(tempDir.root)
        assertNull(cache.get(missingHash))
    }

    @Test
    fun has_returns_true_for_cached_key() {
        val cache = FileDownloadCache(tempDir.root)
        assertFalse(cache.has(hashA))
        cache.put(hashA, "data".toByteArray())
        assertTrue(cache.has(hashA))
    }

    @Test
    fun different_keys_store_different_data() {
        val cache = FileDownloadCache(tempDir.root)
        cache.put(hashA, "first".toByteArray())
        cache.put(hashB, "second".toByteArray())
        assertContentEquals("first".toByteArray(), cache.get(hashA))
        assertContentEquals("second".toByteArray(), cache.get(hashB))
    }

    @Test
    fun rejects_path_traversal_hash() {
        val cache = FileDownloadCache(tempDir.root)
        // A peer-supplied hash that isn't a bare 32-hex string must be rejected before it becomes a
        // file path — guards against "../" traversal, separators, wrong length, and non-hex chars.
        listOf(
            "../../../etc/passwd",
            "a1b2c3d4e5f6000000000000000000ab/../x",
            "ABC123",                                // uppercase
            "short",                                 // too short
            "a1b2c3d4e5f6000000000000000000abEXTRA", // too long
        ).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("expected rejection for '$bad'") {
                cache.fileFor(bad)
            }
        }
    }
}
