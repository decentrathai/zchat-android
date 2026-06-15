package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZBOOT — the handshake memo that hands a peer our NOSTR identity (so Tunnel/Open DMs and
 * voice/video calls can route to us). Sent inside a shielded Zcash transaction.
 *
 * Wire format (v2 — SIGNED):
 *
 *     ZBOOT|v2|<convID>|<senderNostrPubkeyHex>|<relayUrl>|<signatureB64>
 *
 *   - convID                : 8-char alphanumeric, same as ZMSG v4
 *   - senderNostrPubkeyHex  : 64-hex-char x-only secp256k1 pubkey (NIP-01 compliant)
 *   - relayUrl              : wss:// URL the sender wants the recipient to publish replies to
 *   - signatureB64          : ECDSA-SHA256 signature over [signedData] = "<convID>|<pubkey>|<relay>",
 *                             made with the SENDER's E2E identity private key (the same P-256 key
 *                             KEX establishes). The recipient verifies it against the peer's
 *                             KEX-verified E2E public key — without this, an attacker could inject a
 *                             ZBOOT claiming any NOSTR pubkey and MITM all NOSTR DMs/calls
 *                             (shielded receives hide the real sender, so the memo alone is untrusted).
 *
 * SECURITY: the unsigned v1 form is no longer accepted — [parse] only succeeds for signed v2.
 * Reject silently on parse failure — never bubble a parse error into the chat UI.
 */
data class ZBootMessage(
    val convId: String,
    val senderNostrPubkeyHex: String,
    val relayUrl: String,
    val signature: String,
) {
    fun serialize(): String = "$PREFIX$convId|$senderNostrPubkeyHex|$relayUrl|$signature"

    /** The exact bytes covered by [signature] — sign/verify this string. */
    fun signedData(): String = signedDataFor(convId, senderNostrPubkeyHex, relayUrl)

    companion object {
        const val PREFIX = "ZBOOT|v2|"
        private const val ANY_PREFIX = "ZBOOT|"
        private val HEX_64 = Regex("^[0-9a-f]{64}$")

        // Detects ANY ZBOOT (incl. legacy unsigned v1) so the router consumes it rather than
        // rendering the raw memo; parse() then rejects everything except a valid signed v2.
        fun isBootMessage(content: String): Boolean = content.startsWith(ANY_PREFIX)

        fun signedDataFor(convId: String, pubkey: String, relay: String): String = "$convId|$pubkey|$relay"

        fun parse(raw: String): ZBootMessage? {
            if (!raw.startsWith(PREFIX)) return null // unsigned v1 (or other) → rejected
            val body = raw.removePrefix(PREFIX)
            val parts = body.split("|")
            if (parts.size < 4) return null
            val convId = parts[0]
            val pubkey = parts[1]
            val relay = parts[2]
            val sig = parts[3]
            if (convId.length != ZMSGConstants.CONV_ID_LENGTH) return null
            if (!HEX_64.matches(pubkey)) return null
            if (!relay.startsWith("wss://") || relay.length > 80) return null
            if (sig.isEmpty() || sig.length > 200) return null
            return ZBootMessage(convId, pubkey, relay, sig)
        }
    }
}
