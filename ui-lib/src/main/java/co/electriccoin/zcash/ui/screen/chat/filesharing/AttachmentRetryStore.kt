package co.electriccoin.zcash.ui.screen.chat.filesharing

import java.io.File
import java.util.Properties

/**
 * R9-4 (R8-fixup F3 remainder, device D-F5) — what a photo, file or voice note whose upload or send failed needs to
 * be sent again from its FAILED bubble: the encrypted blob and the ZFILE fields around it.
 *
 * It holds ciphertext and the file key WRAPPED with the chat's E2E shared secret, never the plaintext or the bare
 * file key. Each entry is keyed by the optimistic bubble's id and lives in the app-private cache directory [dir]
 * (wiped with the rest of the cache by a destroy). An entry is removed when its file is sent, when its bubble is
 * deleted for me, and by [sweep] once it is older than [MAX_AGE_MS].
 */
internal class AttachmentRetryStore(
    val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The ZFILE fields of the blob. [typeCode] is ZFILEType.code; [durationMs] is only for a voice note's bubble. */
    data class Entry(
        val hash: String,
        val typeCode: String,
        val wrappedKeyB64: String,
        val viewOnce: Boolean,
        val durationMs: Long?,
    )

    fun put(
        id: String,
        ciphertext: ByteArray,
        entry: Entry,
    ) {
        requireValidId(id)
        dir.mkdirs()
        blobFile(id).writeBytes(ciphertext)
        val props =
            Properties().apply {
                setProperty(KEY_HASH, entry.hash)
                setProperty(KEY_TYPE, entry.typeCode)
                setProperty(KEY_WRAPPED, entry.wrappedKeyB64)
                setProperty(KEY_VIEW_ONCE, entry.viewOnce.toString())
                entry.durationMs?.let { setProperty(KEY_DURATION, it.toString()) }
            }
        metaFile(id).outputStream().use { props.store(it, null) }
    }

    /** The blob and its fields, or null when nothing (or only half of an entry) is kept for [id]. */
    fun get(id: String): Pair<ByteArray, Entry>? {
        if (!isValidId(id)) return null
        val blob = blobFile(id)
        val meta = metaFile(id)
        if (!blob.isFile || !meta.isFile) return null
        return runCatching {
            val props = Properties().apply { meta.inputStream().use { load(it) } }
            val entry =
                Entry(
                    hash = props.getProperty(KEY_HASH) ?: return null,
                    typeCode = props.getProperty(KEY_TYPE) ?: return null,
                    wrappedKeyB64 = props.getProperty(KEY_WRAPPED) ?: return null,
                    viewOnce = props.getProperty(KEY_VIEW_ONCE) == "true",
                    durationMs = props.getProperty(KEY_DURATION)?.toLongOrNull(),
                )
            blob.readBytes() to entry
        }.getOrNull()
    }

    fun contains(id: String): Boolean = isValidId(id) && blobFile(id).isFile && metaFile(id).isFile

    fun remove(id: String) {
        if (!isValidId(id)) return
        blobFile(id).delete()
        metaFile(id).delete()
    }

    /** Removes every entry older than [MAX_AGE_MS] (and any stray file in [dir] of that age). */
    fun sweep() {
        val cutoff = now() - MAX_AGE_MS
        dir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach { it.delete() }
    }

    private fun blobFile(id: String) = File(dir, "$id$BLOB_SUFFIX")

    private fun metaFile(id: String) = File(dir, "$id$META_SUFFIX")

    private fun requireValidId(id: String) = require(isValidId(id)) { "not an attachment bubble id" }

    companion object {
        /** 24 hours. */
        const val MAX_AGE_MS: Long = 24L * 60 * 60 * 1000

        /** The folder under the app's cache directory. */
        const val DIR_NAME = "zchat_attachment_retry"

        private const val BLOB_SUFFIX = ".blob"
        private const val META_SUFFIX = ".meta"
        private const val KEY_HASH = "hash"
        private const val KEY_TYPE = "type"
        private const val KEY_WRAPPED = "wrappedKey"
        private const val KEY_VIEW_ONCE = "viewOnce"
        private const val KEY_DURATION = "durationMs"

        // An optimistic bubble id ("img-pending-<n>", "file-pending-<n>", "audio-pending-<n>"): it becomes a file name.
        private val ID = Regex("^[a-z]{2,8}-pending-[0-9]{1,20}$")

        fun isValidId(id: String): Boolean = ID.matches(id)
    }
}
