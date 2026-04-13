package co.electriccoin.zcash.ui.screen.chat.filesharing

import java.io.File

/**
 * Simple file-based cache for decrypted images/documents, keyed by ZFILE hash.
 * Prevents re-downloading and re-decrypting on every blockchain re-scan.
 *
 * Thread safety: concurrent reads are safe (file system). Concurrent writes to the
 * same key are idempotent (same data overwritten). Different keys are independent.
 *
 * @param cacheDir Directory to store cached files (typically `context.cacheDir/zchat_files/`).
 */
class FileDownloadCache(private val cacheDir: File) {

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    /** Store decrypted file data under the given hash key. */
    fun put(hash: String, data: ByteArray) {
        fileFor(hash).writeBytes(data)
    }

    /** Retrieve cached data for the given hash, or null if not cached. */
    fun get(hash: String): ByteArray? {
        val file = fileFor(hash)
        return if (file.exists()) file.readBytes() else null
    }

    /** Check if data is cached for the given hash. */
    fun has(hash: String): Boolean = fileFor(hash).exists()

    /** Get the File path for a cached item (for use with image loaders). */
    fun fileFor(hash: String): File = File(cacheDir, hash)
}
