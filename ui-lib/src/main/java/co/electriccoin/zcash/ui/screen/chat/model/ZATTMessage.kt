package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZC-1474 (owner W-123, FIX_PLAN REVISION 2): a photo, file or voice note sent free in an Open or Tunnel chat. The
 * encrypted blob is uploaded as for a ZFILE; this text carries its 32-byte file key itself, and goes ONLY inside an
 * authenticated NIP-17 rumor to the contact's bound relay key (never on chain, never INIT-wrapped), and only to a
 * contact whose newest rumor carried the `zatt1` capability ([co.electriccoin.zcash.ui.nostr.Nip17.ZATT_CAPABILITY_TAG]):
 * a 2.12.4 phone would show the whole text, key included, as a message.
 *
 * Wire: `ZATT|v1|<hash 32 hex>|<type code>|<ciphertext size>|<viewOnce 0/1>|<https url>|<file key base64>|<blurhash>`.
 * The blurhash is last because its alphabet contains '|'. The receiver's row keeps this text in the encrypted pending
 * store (as a ZFILE's) until the file is downloaded and decrypted.
 */
class ZATTMessage(
    val hash: String,
    val type: ZFILEType,
    val size: Long,
    val url: String,
    val fileKey: ByteArray,
    val blurhash: String,
    val viewOnce: Boolean,
) {
    fun serialize(): String {
        val keyB64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(fileKey)
        val fields = listOf(hash, type.code, size.toString(), if (viewOnce) "1" else "0", url, keyB64, blurhash)
        return PREFIX + fields.joinToString("|")
    }

    /** The display and download fields as a ZFILE's (no wrapped key: the key is [fileKey]). */
    fun toFileRef(): ZFILEMessage =
        ZFILEMessage(hash = hash, type = type, size = size, url = url, wrappedKey = "", blurhash = blurhash, viewOnce = viewOnce)

    companion object {
        const val PREFIX = "ZATT|v1|"

        /** The download cap ([size] above it is refused before anything is fetched). */
        const val MAX_SIZE_BYTES = 25L * 1024 * 1024

        /** A ZATT text longer than this is refused (a normal one is about 300 characters). */
        const val MAX_TEXT_CHARS = 4_096

        private const val FIELDS = 7
        private const val HASH_LEN = 32
        private const val FILE_KEY_BYTES = 32

        /** Any text that claims to be a ZATT (any version), so no reader shows it as a message. */
        fun isZatt(content: String): Boolean = content.startsWith("ZATT|")

        fun build(
            hash: String,
            type: ZFILEType,
            size: Long,
            url: String,
            fileKey: ByteArray,
            blurhash: String,
            viewOnce: Boolean,
        ): String {
            require(fileKey.size == FILE_KEY_BYTES) { "a file key is $FILE_KEY_BYTES bytes" }
            return ZATTMessage(hash, type, size, url, fileKey, blurhash, viewOnce).serialize()
        }

        /** The ZATT [raw] holds, or null for anything malformed (never throws). */
        @Suppress("ReturnCount")
        fun parse(raw: String): ZATTMessage? {
            if (raw.length > MAX_TEXT_CHARS || !raw.startsWith(PREFIX)) return null
            val parts = raw.removePrefix(PREFIX).split("|", limit = FIELDS)
            if (parts.size != FIELDS) return null
            val hash = parts[0]
            // The hash names the download cache file: a bare 32-char lowercase hex string, never a path.
            if (hash.length != HASH_LEN || !hash.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val type = ZFILEType.fromCode(parts[1]) ?: return null
            val size = parts[2].toLongOrNull()?.takeIf { it in 1..MAX_SIZE_BYTES } ?: return null
            val viewOnce =
                when (parts[3]) {
                    "0" -> false
                    "1" -> true
                    else -> return null
                }
            // The url is fetched without a tap: https on an upload host only (ZFILEMessage's rule).
            val url = parts[4]
            if (!ZFILEMessage.isAllowedMediaUrl(url)) return null
            val key =
                runCatching {
                    java.util.Base64
                        .getDecoder()
                        .decode(parts[5])
                }.getOrNull()
                    ?.takeIf { it.size == FILE_KEY_BYTES }
                    ?: return null
            return ZATTMessage(hash, type, size, url, key, parts[6], viewOnce)
        }

        /** The file a stored ZFILE or ZATT line names (its display and download fields), or null. */
        fun fileRefOf(content: String): ZFILEMessage? =
            when {
                ZFILEMessage.isFileMessage(content) -> ZFILEMessage.parse(content)
                isZatt(content) -> parse(content)?.toFileRef()
                else -> null
            }
    }
}
