package co.electriccoin.zcash.ui.screen.chat.model

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A shareable ZCHAT contact code. Carries the user's Zcash receive address and — when available —
 * their seed-derived NOSTR messaging key + preferred relay, so a peer who scans/pastes it can start
 * a FREE NIP-17 NOSTR ("Open") conversation from message #1, with NO on-chain handshake.
 *
 * Why this exists: the old "My Address" QR carried only the Zcash address, so a fresh chat had no
 * NOSTR key to send over the relay — Open could not work for the first message (it silently fell back
 * to a charged on-chain memo / "unknown sender"). Embedding the NOSTR key here removes that limitation.
 *
 * Wire format (URI — compact, QR-able, and copyable as text):
 *   zchat:c1?z=<zcashUA>&n=<nostrPubkeyHex>&r=<urlEncodedRelay>
 * - `z` (required): the Zcash unified/receive address.
 * - `n` (optional): the sender's NOSTR public key, 64-hex (x-only). Absent ⇒ Open not offered.
 * - `r` (optional): the sender's preferred NOSTR relay URL (wss://…), URL-encoded.
 *
 * Backward / interop compatibility: [parse] also accepts a BARE Zcash address (u1…/zs…/zt…/t…) and a
 * `zcash:`-prefixed URI, returning { nostrPubkeyHex = null }. So old QR codes, addresses copied from a
 * block explorer, and plain Zcash wallets all still resolve — they just don't enable Open.
 */
data class ZchatContactCode(
    val zcashAddress: String,
    val nostrPubkeyHex: String? = null,
    val relayUrl: String? = null,
) {
    /** True when this code carries everything needed to start a free NOSTR (Open) chat immediately. */
    val supportsOpen: Boolean get() = !nostrPubkeyHex.isNullOrBlank() && !relayUrl.isNullOrBlank()

    /** Serialize to the `zchat:c1?…` URI. Omits absent fields. */
    fun serialize(): String {
        val sb = StringBuilder("$SCHEME:$VERSION?z=").append(enc(zcashAddress))
        if (!nostrPubkeyHex.isNullOrBlank()) sb.append("&n=").append(nostrPubkeyHex)
        if (!relayUrl.isNullOrBlank()) sb.append("&r=").append(enc(relayUrl))
        return sb.toString()
    }

    companion object {
        const val SCHEME = "zchat"
        const val VERSION = "c1"
        // x-only NOSTR pubkey is exactly 64 lowercase hex chars.
        private val NOSTR_HEX = Regex("^[0-9a-fA-F]{64}$")

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        // Null (not throw) on a malformed percent-escape. parse() runs on attacker-controlled scanned/pasted
        // input, and URLDecoder.decode throws IllegalArgumentException on a bare/incomplete '%' (e.g.
        // "zchat:c1?z=%"). Callers (ScanZashiAddressVM.onScanned, ZchatComposeVM.onRecipientChange) do NOT
        // wrap parse(), so an uncaught throw crashed the scan coroutine / the main thread on paste. Return
        // null → parse() treats it as "not a usable contact", the contract callers already handle.
        private fun dec(s: String): String? = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()

        private fun looksLikeZcashAddress(s: String): Boolean {
            val t = s.trim()
            return t.length in 20..512 &&
                (t.startsWith("u1") || t.startsWith("zs") || t.startsWith("zt") ||
                    t.startsWith("ztestsapling") || t.startsWith("t1") || t.startsWith("t3") ||
                    t.startsWith("utest"))
        }

        /**
         * Parse a scanned/pasted string into a [ZchatContactCode], or null if it isn't a usable
         * contact (no resolvable Zcash address). Handles three forms:
         *  1. `zchat:c1?z=…&n=…&r=…`  (full code — may enable Open)
         *  2. `zcash:<addr>[?…]`      (standard Zcash URI — address only)
         *  3. bare `<addr>`           (raw Zcash address — address only)
         */
        fun parse(raw: String?): ZchatContactCode? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null

            if (s.startsWith("$SCHEME:")) {
                val query = s.substringAfter('?', "")
                if (query.isEmpty()) return null
                val params = query.split('&').mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
                }.toMap()
                val z = params["z"]?.let { dec(it) }?.trim().orEmpty()
                if (!looksLikeZcashAddress(z)) return null
                val n = params["n"]?.trim()?.takeIf { NOSTR_HEX.matches(it) }?.lowercase()
                val r = params["r"]?.let { dec(it) }?.trim()?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
                return ZchatContactCode(z, n, r)
            }

            // zcash: URI → take the address portion before any query/params.
            if (s.startsWith("zcash:")) {
                val addr = s.removePrefix("zcash:").substringBefore('?').substringBefore('&').trim()
                return if (looksLikeZcashAddress(addr)) ZchatContactCode(addr) else null
            }

            // Bare address.
            return if (looksLikeZcashAddress(s)) ZchatContactCode(s) else null
        }
    }
}
