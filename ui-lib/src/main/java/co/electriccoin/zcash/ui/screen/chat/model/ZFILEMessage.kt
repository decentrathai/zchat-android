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

        // Inbound-media host allowlist. Keep in sync with FileUploadManager.blossomServers/nip96Servers
        // (the ONLY hosts we upload to) — a legit ZFILE always points at one of these. Anything else is a
        // peer trying to make the auto-fetch hit a host of THEIR choosing (IP-deanonymization / SSRF).
        private const val MAX_URL_LEN = 512
        private val ALLOWED_MEDIA_HOSTS =
            setOf("blossom.primal.net", "blossom.band", "blossom.nostr.build", "nostr.build")

        private fun isAllowedMediaUrl(url: String): Boolean {
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
