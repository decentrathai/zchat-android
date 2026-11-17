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
        val typeLabel = when {
            isImage -> "Image"
            isAudio -> "Voice message"
            else -> "Document"
        }
        return "$typeLabel ($sizeText)"
    }

    /**
     * Round 10b R10B-3 (F-R9C-7): the file's own size. [size] is the ciphertext's by protocol (the integrity check
     * compares the download against it), which is the file plus the IV and GCM tag encryptFile adds (a 580 B file is
     * sent as 608). Never below 0.
     */
    val plainSize: Long get() =
        (size - co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.FILE_CIPHER_OVERHEAD_BYTES).coerceAtLeast(0)

    /** The file's size as the bubbles show it ("149.0 KB"; the number follows the phone's locale): [plainSize]. */
    val sizeText: String get() {
        val shown = plainSize
        return when {
            shown < 1024 -> "$shown B"
            shown < 1024 * 1024 -> "${"%.1f".format(shown / 1024.0)} KB"
            else -> "${"%.1f".format(shown / (1024.0 * 1024.0))} MB"
        }
    }

    /**
     * R10A-2 (F-R9P-4): the words of [displayText] as a string resource taking [sizeText], so an outgoing file row
     * reads «Documento (7,0 MB)» on a Spanish phone instead of the English «Document (7,0 MB)».
     */
    @get:androidx.annotation.StringRes
    val typeLabelRes: Int get() =
        when {
            isImage -> co.electriccoin.zcash.ui.R.string.chat_attachment_image
            isAudio -> co.electriccoin.zcash.ui.R.string.chat_attachment_voice
            else -> co.electriccoin.zcash.ui.R.string.chat_attachment_document
        }

    companion object {
        /** Quick check if a message content string is a ZFILE message. */
        fun isFileMessage(content: String): Boolean =
            content.startsWith("ZFILE|") && content.indexOf('|', 6) > 6
        // The file hash is a SHA-256 hex prefix that is later used directly AS A FILENAME in the
        // download cache. It must be exactly this many lowercase hex chars — never a path.
        private const val HASH_LEN = 32

        // Inbound-media host allowlist. Keep in sync with FileUploadManager.blossomServers/nip96Servers
        // (the ONLY hosts we upload to) — a legit ZFILE always points at one of these. Anything else is a
        // peer trying to make the auto-fetch hit a host of THEIR choosing (IP-deanonymization / SSRF).
        private const val MAX_URL_LEN = 512

        // R8-fixup F3: the hosts attachments are uploaded to since the servers began refusing encrypted blobs declared as
        // images, plus the earlier ones, so files already sent keep loading.
        private val ALLOWED_MEDIA_HOSTS =
            setOf(
                "nostr.download",
                "blossom.yakihonne.com",
                "cdn.hzrd149.com",
                "files.sovbit.host",
                "blossom.primal.net",
                "blossom.band",
                "blossom.nostr.build",
                "nostr.build",
            )

        /** R9-4: also what FileUploadManager checks an upload's url against, so it never returns one the recipient drops. */
        internal fun isAllowedMediaUrl(url: String): Boolean {
            if (url.length !in 1..MAX_URL_LEN) return false
            val uri = try { java.net.URI(url) } catch (e: Exception) { return false }
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            // Only the default TLS port — a peer must not be able to aim the auto-fetch at an arbitrary
            // port of an allowed host (hang / internal-port probe).
            if (uri.port != -1 && uri.port != 443) return false
            val host = uri.host?.lowercase() ?: return false
            // Accept an allowlisted host OR any SUBDOMAIN of it: Blossom/NIP-96 servers serve blobs from
            // dedicated subdomains (image.nostr.build, media.nostr.build, <npub>.blossom.band), not the
            // upload host — an exact-match would drop legit files whenever upload falls back off primal.
            return ALLOWED_MEDIA_HOSTS.any { host == it || host.endsWith(".$it") }
        }

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
            // SECURITY: an inbound ZFILE `url` is AUTO-FETCHED on chat-open (downloadAndCacheFile), with
            // no user tap. A peer fully controls this field, so an unvalidated url pointed at an
            // attacker host would leak the recipient's REAL public IP + a precise "chat opened at T"
            // read-receipt (deanonymizing a privacy-wallet user, bypassing the relay/Tor path), and a
            // url like http://127.0.0.1:PORT / http://192.168.x.x enables blind SSRF against localhost/LAN.
            // Only accept an https url whose host is one we actually upload media to.
            if (!isAllowedMediaUrl(parts[3])) return null
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
