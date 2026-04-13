package co.electriccoin.zcash.ui.screen.chat.model

enum class ZFILEType(val code: String, val mimeType: String) {
    JPEG("j", "image/jpeg"),
    PNG("p", "image/png"),
    GIF("g", "image/gif"),
    WEBP("w", "image/webp"),
    PDF("d", "application/pdf"),
    ZIP("z", "application/zip"),
    TXT("t", "text/plain");

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
) {
    fun serialize(): String = "ZFILE|$hash|${type.code}|$size|$url|$wrappedKey|$blurhash"

    val isImage: Boolean get() = type in listOf(ZFILEType.JPEG, ZFILEType.PNG, ZFILEType.GIF, ZFILEType.WEBP)

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
        val typeLabel = if (isImage) "Image" else "Document"
        return "$typeLabel ($sizeStr)"
    }

    companion object {
        /** Quick check if a message content string is a ZFILE message. */
        fun isFileMessage(content: String): Boolean =
            content.startsWith("ZFILE|") && content.indexOf('|', 6) > 6
        fun parse(raw: String): ZFILEMessage? {
            if (!raw.startsWith("ZFILE|")) return null
            val parts = raw.removePrefix("ZFILE|").split("|")
            if (parts.size < 6) return null
            val fileType = ZFILEType.fromCode(parts[1]) ?: return null
            val fileSize = parts[2].toLongOrNull() ?: return null
            return ZFILEMessage(
                hash = parts[0],
                type = fileType,
                size = fileSize,
                url = parts[3],
                wrappedKey = parts[4],
                blurhash = parts.getOrElse(5) { "" },
            )
        }
    }
}
