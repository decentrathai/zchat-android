package co.electriccoin.zcash.ui.screen.chat.model

import android.util.Base64
import java.security.MessageDigest

/**
 * ZPROF — self-avatar propagation over FREE NOSTR (NIP-17 gift-wrap) ONLY.
 *
 * This is the Phase-2 propagation channel referenced by [co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore]:
 * the user's OWN (self) avatar is pushed to ESTABLISHED contacts so it renders as THEIR default contact
 * avatar (a locally-set override still wins — see AvatarStore.setContactAvatar / ZchatAvatar). Avatar bytes
 * NEVER touch a Zcash transaction / on-chain memo — they ride the same free NIP-17 DM path TUNNEL/OPEN text
 * uses, and only to peers whose NOSTR pubkey we already hold.
 *
 * ## Wire format (one memo per chunk)
 * ```
 * ZPROF|v1|<xferId>|<idx>/<total>|<sha16>|<b64chunk>
 * ```
 *  - `v1`        protocol version.
 *  - `xferId`    per-transfer id (8 hex) grouping the chunks of ONE avatar push, so a re-broadcast (or a
 *                different avatar) can't interleave with a half-received one. Reassembly key = peer + xferId.
 *  - `idx/total` 1-based chunk index / chunk count — mirrors the ZMSG v4c "M/N" chunk header.
 *  - `sha16`     first 16 lowercase-hex chars of SHA-256 over the FULL JPEG (integrity + reassembly guard;
 *                identical on every chunk of a transfer).
 *  - `b64chunk`  a contiguous slice of Base64(jpeg). Concatenating every chunk's slice in `idx` order
 *                reproduces the whole Base64 string, decoded ONCE on completion.
 *
 * ## Why chunked (not a single event, not a media-host upload)
 * A stored self avatar is a 256x256 JPEG capped at AvatarStore.MAX_AVATAR_BYTES (~50KB) → ~67KB Base64.
 * Sealed twice by NIP-17 (~1.8x) that is a ~118KB relay frame — over the 64KB many public relays enforce
 * (RelayClient bounds inbound frames at 256KB, but the sending side must clear EVERY relay in the pool).
 * Slicing the Base64 into [CHUNK_B64_LEN]-char pieces keeps each gift-wrap well under any relay's ceiling.
 * Unlike ZFILE we do NOT upload to a Blossom/NIP-96 host: that would leak the avatar (and an IP-fetch
 * beacon) to a third party; ZPROF stays inside the encrypted DM to a known contact.
 */
object ZPROFMessage {

    private const val PREFIX = ZMSGConstants.Prefixes.PROFILE
    private const val VERSION = "v1"

    /** Base64 chars per chunk. ~16KB keeps a doubly-NIP-17-sealed gift-wrap ~30KB — safe on every relay. */
    const val CHUNK_B64_LEN = 16 * 1024

    /** Hard ceiling on a reassembled avatar (task cap): a peer can't make us buffer/decode more than this. */
    const val MAX_TOTAL_BYTES = 256 * 1024

    /** Ceiling on the buffered Base64 (≈ MAX_TOTAL_BYTES * 4/3, with slack) — reject before over-buffering. */
    private const val MAX_TOTAL_B64 = 350_000

    /** Chunk-count bound (DoS guard). The byte/Base64 caps above are the real limit; this backstops them. */
    const val MAX_CHUNKS = 32

    /** SHA-256 hex-prefix length carried on the wire (integrity + last-writer identity). */
    private const val SHA_LEN = 16

    /** True if [memo] is a ZPROF avatar chunk. */
    fun isProfileMessage(memo: String): Boolean = memo.startsWith(PREFIX)

    /** A single parsed ZPROF chunk (all fields already range/format-validated). */
    data class Chunk(
        val xferId: String,
        val idx: Int,
        val total: Int,
        val sha16: String,
        val b64: String,
    )

    /**
     * Build the ZPROF chunk memos for a self-avatar JPEG. Returns one memo per chunk (a small avatar is a
     * single `1/1` memo), or null if [jpeg] is empty / larger than [MAX_TOTAL_BYTES] / would exceed
     * [MAX_CHUNKS]. Caller publishes each memo over free NOSTR to a known peer.
     */
    fun build(jpeg: ByteArray): List<String>? {
        if (jpeg.isEmpty() || jpeg.size > MAX_TOTAL_BYTES) return null
        val sha16 = sha256Hex(jpeg).take(SHA_LEN)
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val total = (b64.length + CHUNK_B64_LEN - 1) / CHUNK_B64_LEN
        if (total < 1 || total > MAX_CHUNKS) return null
        // 8 hex chars from a random UUID — enough to keep concurrent/re-broadcast transfers from colliding.
        val xferId = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        return (0 until total).map { i ->
            val start = i * CHUNK_B64_LEN
            val end = minOf(start + CHUNK_B64_LEN, b64.length)
            "$PREFIX$VERSION|$xferId|${i + 1}/$total|$sha16|${b64.substring(start, end)}"
        }
    }

    /** Parse one ZPROF chunk memo. Returns null on any malformed/out-of-range field. */
    fun parseChunk(memo: String): Chunk? {
        if (!memo.startsWith(PREFIX)) return null
        // Base64 never contains '|', so a fixed split is safe; limit=5 keeps the payload intact regardless.
        val parts = memo.removePrefix(PREFIX).split("|", limit = 5)
        if (parts.size < 5) return null
        if (parts[0] != VERSION) return null
        val xferId = parts[1]
        if (xferId.isEmpty() || xferId.length > 32 || !xferId.all { it.isLetterOrDigit() }) return null
        val idxTotal = parts[2].split("/")
        if (idxTotal.size != 2) return null
        val idx = idxTotal[0].toIntOrNull() ?: return null
        val total = idxTotal[1].toIntOrNull() ?: return null
        if (total !in 1..MAX_CHUNKS) return null
        if (idx !in 1..total) return null
        val sha16 = parts[3]
        if (sha16.length != SHA_LEN || !sha16.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val b64 = parts[4]
        if (b64.isEmpty() || b64.length > CHUNK_B64_LEN) return null
        return Chunk(xferId, idx, total, sha16, b64)
    }

    private fun sha256Hex(input: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input)
            .joinToString("") { "%02x".format(it) }

    /**
     * Bounded, self-contained reassembly buffer for inbound ZPROF chunks. Feed each parsed [Chunk] with the
     * authenticated sender's peer address; [accept] returns the full JPEG bytes exactly once, when the final
     * missing chunk of a transfer lands and the reassembled bytes pass the size + SHA-256 integrity check
     * (else null while a transfer is still incomplete or a completed transfer failed validation).
     *
     * Defensive by construction (all inputs are from a KNOWN peer, but still): at most MAX_INFLIGHT
     * concurrent transfers (oldest evicted), stale transfers dropped after STALE_MS, buffered Base64 bounded
     * by MAX_TOTAL_B64, decoded bytes bounded by MAX_TOTAL_BYTES. Chunk redelivery (relays replay gift-wraps)
     * is idempotent — a duplicate index is ignored, never double-counted.
     */
    class Reassembler {

        // Plain class (not data) — it holds a mutable Array and is never compared/copied, so the structural
        // equals/hashCode a data class would synthesize over an Array would be misleading.
        private class Holder(
            val total: Int,
            val sha16: String,
            val chunks: Array<String?>,
            var received: Int,
            var b64Len: Int,
            val firstSeenMillis: Long,
        )

        private val lock = Any()
        private val inFlight = LinkedHashMap<String, Holder>()

        fun accept(peerAddress: String, chunk: Chunk, nowMillis: Long = System.currentTimeMillis()): ByteArray? {
            synchronized(lock) {
                evictStale(nowMillis)
                val key = "$peerAddress ${chunk.xferId}"
                val existing = inFlight[key]
                // Reuse an in-progress transfer only when it's the SAME avatar (sha + chunk-count match); a
                // new key, or the same xferId now carrying a DIFFERENT avatar, starts fresh so a stale partial
                // can't corrupt a new push. holder is a val (never a captured var) so it smart-casts non-null
                // for the deref + the assemble closure below.
                val holder: Holder
                if (existing != null && existing.total == chunk.total && existing.sha16 == chunk.sha16) {
                    holder = existing
                } else {
                    // Bound concurrent transfers — only when adding a genuinely NEW key (replacing the same
                    // key in place doesn't grow the map). Evict oldest-first (LinkedHashMap insertion order).
                    if (existing == null && inFlight.size >= MAX_INFLIGHT) {
                        inFlight.entries.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
                    }
                    holder = Holder(
                        total = chunk.total,
                        sha16 = chunk.sha16,
                        chunks = arrayOfNulls<String>(chunk.total),
                        received = 0,
                        b64Len = 0,
                        firstSeenMillis = nowMillis,
                    )
                    inFlight[key] = holder
                }
                val slot = chunk.idx - 1
                if (holder.chunks[slot] != null) return null // duplicate (relay replay) — idempotent
                if (holder.b64Len + chunk.b64.length > MAX_TOTAL_B64) {
                    inFlight.remove(key)
                    return null
                }
                holder.chunks[slot] = chunk.b64
                holder.b64Len += chunk.b64.length
                holder.received += 1
                if (holder.received < holder.total) return null
                // Complete — assemble, decode once, validate, and hand the bytes back (remove either way).
                inFlight.remove(key)
                val assembled = buildString(holder.b64Len) { holder.chunks.forEach { append(it) } }
                val decoded = runCatching { Base64.decode(assembled, Base64.NO_WRAP) }.getOrNull() ?: return null
                if (decoded.isEmpty() || decoded.size > MAX_TOTAL_BYTES) return null
                if (sha256Hex(decoded).take(SHA_LEN) != holder.sha16) return null
                return decoded
            }
        }

        private fun evictStale(nowMillis: Long) {
            val it = inFlight.entries.iterator()
            while (it.hasNext()) {
                if (nowMillis - it.next().value.firstSeenMillis > STALE_MS) it.remove()
            }
        }

        companion object {
            private const val MAX_INFLIGHT = 8
            private const val STALE_MS = 5L * 60 * 1000
        }
    }
}
