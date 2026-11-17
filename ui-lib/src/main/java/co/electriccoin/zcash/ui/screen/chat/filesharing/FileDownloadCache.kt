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

    /**
     * Store decrypted file data under the given hash key. Writes ATOMICALLY (temp file + rename) so
     * that an app kill / crash mid-write can't leave a truncated file that a later read would hand
     * back as a silently-corrupt download.
     */
    fun put(hash: String, data: ByteArray) {
        val target = fileFor(hash) // validates hash before we build any sibling temp path
        val tmp = File(cacheDir, "$hash.tmp")
        try {
            tmp.writeBytes(data)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Retrieve cached data for the given hash, or null if not cached. */
    fun get(hash: String): ByteArray? {
        val file = fileFor(hash)
        return if (file.exists()) file.readBytes() else null
    }

    /** Check if data is cached for the given hash. */
    fun has(hash: String): Boolean = fileFor(hash).exists()

    /**
     * D11 — best-effort secure wipe of a cached decrypted blob: overwrite once with random bytes,
     * fsync, then delete. Idempotent — an invalid hash or missing file is a no-op. ext4 / F2FS
     * don't guarantee in-place rewrite, but on devices where the page mapping is stable this
     * raises the bar against forensic recovery. Disk I/O — call from a background dispatcher.
     */
    fun secureDelete(hash: String) {
        val file = try {
            fileFor(hash)
        } catch (_: IllegalArgumentException) {
            return
        }
        if (!file.exists()) return
        runCatching {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                val len = raf.length()
                if (len > 0) {
                    val buf = ByteArray(8192)
                    java.security.SecureRandom().nextBytes(buf)
                    var written = 0L
                    raf.seek(0)
                    while (written < len) {
                        val toWrite = minOf(buf.size.toLong(), len - written).toInt()
                        raf.write(buf, 0, toWrite)
                        written += toWrite
                    }
                    raf.fd.sync()
                }
            }
        }.onFailure {
            android.util.Log.w("FileDownloadCache", "Secure wipe failed for $hash: ${it.message}")
        }
        file.delete()
    }

    /** Get the File path for a cached item (for use with image loaders). */
    fun fileFor(hash: String): File {
        // Defense in depth: the hash is used directly as a filename, so it MUST be a bare 32-char
        // lowercase-hex string — never a path. This rejects "../" traversal, path separators, and
        // absolute paths. ZFILEMessage.parse() also validates peer input on the way in; this guards
        // every caller (and any future one) at the actual filesystem sink.
        require(hash.length == HASH_LEN && hash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid file-cache hash (must be 32 lowercase hex chars)"
        }
        return File(cacheDir, hash)
    }

    private companion object {
        const val HASH_LEN = 32
    }
}
