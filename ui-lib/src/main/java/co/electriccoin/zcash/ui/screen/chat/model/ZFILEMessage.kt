package co.electriccoin.zcash.ui.screen.chat.model

enum class ZFILEType(val code: String, val mimeType: String) {
    JPEG("j", "image/jpeg"),
    PNG("p", "image/png"),
    GIF("g", "image/gif"),
    WEBP("w", "image/webp"),
    PDF("d", "application/pdf"),
    ZIP("z", "application/zip"),
    TXT("t", "text/plain"),
    // AAC-LC in an MP4 container — Android MediaRecorder default + universally playable.
    M4A("a", "audio/mp4");

    companion object {
        fun fromCode(code: String): ZFILEType? = entries.find { it.code == code }

        fun fromMime(mime: String): ZFILEType? = entries.find { it.mimeType == mime }
    }
}

data class ZFILEMessage(
    val hash: String,
    val type: ZFILEType,
    val size: Long,
    val url: String,
    val wrappedKey: String,
    val blurhash: String,
    // View-once: recipient deletes the local cache after a single view (image) or
    // a single playback to completion (audio). 7th field; absent or "0" = persistent.
    val viewOnce: Boolean = false,
) {
    fun serialize(): String =
        "ZFILE|$hash|${type.code}|$size|$url|$wrappedKey|$blurhash" +
            if (viewOnce) "|1" else ""

    val isImage: Boolean get() = type in listOf(ZFILEType.JPEG, ZFILEType.PNG, ZFILEType.GIF, ZFILEType.WEBP)

    val isAudio: Boolean get() = type == ZFILEType.M4A

    /**
     * Human-readable description for display in chat bubbles when the file
     * hasn't been downloaded yet (or as alt text).
     */
    val displayText: String get() {
        val sizeStr = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${"%.1f".format(size / 1024.0)} KB"
            else -> "${"%.1f".format(size / (1024.0 * 1024.0))} MB"
        }
        val typeLabel = when {
            isImage -> "Image"
            isAudio -> "Voice message"
            else -> "Document"
        }
        return "$typeLabel ($sizeStr)"
    }

    companion object {
        /** Quick check if a message content string is a ZFILE message. */
        fun isFileMessage(content: String): Boolean =
            content.startsWith("ZFILE|") && content.indexOf('|', 6) > 6
        // The file hash is a SHA-256 hex prefix that is later used directly AS A FILENAME in the
        // download cache. It must be exactly this many lowercase hex chars — never a path.
        private const val HASH_LEN = 32

        fun parse(raw: String): ZFILEMessage? {
            if (!raw.startsWith("ZFILE|")) return null
            val parts = raw.removePrefix("ZFILE|").split("|")
            if (parts.size < 6) return null
            val hash = parts[0]
            // SECURITY: reject a peer-supplied hash that isn't a bare 32-char hex string, so a
            // malicious peer can't smuggle path-traversal ("../"), separators, or an absolute path
            // through it into FileDownloadCache.fileFor() (path-traversal / arbitrary-file-write).
            if (hash.length != HASH_LEN || !hash.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val fileType = ZFILEType.fromCode(parts[1]) ?: return null
            val fileSize = parts[2].toLongOrNull() ?: return null
            return ZFILEMessage(
                hash = hash,
                type = fileType,
                size = fileSize,
                url = parts[3],
                wrappedKey = parts[4],
                blurhash = parts.getOrElse(5) { "" },
                viewOnce = parts.getOrElse(6) { "0" } == "1",
            )
        }
    }
}
