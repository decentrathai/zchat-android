package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZBOOT — the handshake memo that hands a peer our NOSTR identity (so Tunnel/Open DMs and
 * voice/video calls can route to us). Sent inside a shielded Zcash transaction OR over NOSTR.
 *
 * Wire format (v3 — SIGNED + ROTATION EPOCH):
 *
 *     ZBOOT|v3|<convID>|<senderNostrPubkeyHex>|<relayUrl>|<epoch>|<signatureB64>
 *
 *   - convID                : 8-char alphanumeric, same as ZMSG v4
 *   - senderNostrPubkeyHex  : 64-hex-char x-only secp256k1 pubkey (NIP-01 compliant)
 *   - relayUrl              : wss:// URL the sender wants the recipient to publish replies to
 *   - epoch                 : the sender's NOSTR rotation index at send time — a MONOTONIC counter that
 *                             increments on every key rotation. The recipient adopts a ZBOOT only when
 *                             its epoch is >= the highest epoch it has already adopted for this peer, so
 *                             a re-scanned / replayed OLDER ZBOOT (lower epoch, stale pubkey) can NEVER
 *                             downgrade the live key (#225 hardening — prevents the rotation-replay
 *                             oscillation/downgrade the deferred review finding warned about).
 *   - signatureB64          : ECDSA-SHA256 signature over [signedData] (which INCLUDES the epoch for v3),
 *                             made with the SENDER's E2E identity private key. The recipient verifies it
 *                             against the peer's KEX-verified E2E public key — without this an attacker
 *                             could inject a ZBOOT claiming any NOSTR pubkey/epoch and MITM all NOSTR
 *                             DMs/calls (shielded receives hide the real sender, so the memo is untrusted).
 *
 * Legacy v2 (`ZBOOT|v2|<convID>|<pubkey>|<relay>|<sig>`, signedData WITHOUT epoch) is still PARSED so
 * on-chain history written before v3 keeps working; such messages carry epoch 0. The unsigned v1 form is
 * not accepted. Reject silently on parse failure — never bubble a parse error into the chat UI.
 */
data class ZBootMessage(
    val convId: String,
    val senderNostrPubkeyHex: String,
    val relayUrl: String,
    val signature: String,
    // Rotation epoch (sender's NOSTR rotation index). 0 for legacy v2.
    val epoch: Long = 0L,
    // 4 = NOSTR-key-signed (Schnorr; OPEN peers w/o E2E key, #250); 3 = E2E-signed epoch-carrying;
    // 2 = legacy E2E-signed (signedData omits epoch). v4 shares v3's signedData (epoch-bound).
    val version: Int = 3,
) {
    fun serialize(): String =
        "${if (version >= 4) PREFIX_V4 else PREFIX_V3}$convId|$senderNostrPubkeyHex|$relayUrl|$epoch|$signature"

    /** The exact bytes covered by [signature] — sign/verify this string. v3 binds the epoch too. */
    fun signedData(): String =
        if (version >= 3) {
            signedDataFor(convId, senderNostrPubkeyHex, relayUrl, epoch)
        } else {
            signedDataForV2(convId, senderNostrPubkeyHex, relayUrl)
        }

    companion object {
        const val PREFIX_V4 = "ZBOOT|v4|" // NOSTR-key-signed rotation for OPEN peers (no E2E key) — #250
        const val PREFIX_V3 = "ZBOOT|v3|"
        const val PREFIX_V2 = "ZBOOT|v2|"
        private const val ANY_PREFIX = "ZBOOT|"
        private val HEX_64 = Regex("^[0-9a-f]{64}$")

        // Detects ANY ZBOOT (incl. legacy v1/v2) so the router consumes it rather than rendering the raw
        // memo; parse() then rejects everything except a valid signed v2/v3.
        fun isBootMessage(content: String): Boolean = content.startsWith(ANY_PREFIX)

        /** v3 signed bytes — binds the rotation epoch so a stale ZBOOT can't be re-adopted. */
        fun signedDataFor(convId: String, pubkey: String, relay: String, epoch: Long): String =
            "$convId|$pubkey|$relay|$epoch"

        /** Legacy v2 signed bytes (no epoch). */
        fun signedDataForV2(convId: String, pubkey: String, relay: String): String =
            "$convId|$pubkey|$relay"

        fun parse(raw: String): ZBootMessage? =
            when {
                raw.startsWith(PREFIX_V4) -> parseV4(raw.removePrefix(PREFIX_V4))
                raw.startsWith(PREFIX_V3) -> parseV3(raw.removePrefix(PREFIX_V3))
                raw.startsWith(PREFIX_V2) -> parseV2(raw.removePrefix(PREFIX_V2))
                else -> null // unsigned v1 (or other) → rejected
            }

        // ZBOOT|v4|<convID>|<pubkey>|<relay>|<epoch>|<sig> — NOSTR-key-signed (Schnorr, 64-byte hex sig).
        // Same wire shape + signed bytes as v3; the version tells the receiver to verify against the peer's
        // known NOSTR pubkey (Schnorr) rather than the E2E key (#250 — OPEN peers have no E2E identity).
        private fun parseV4(body: String): ZBootMessage? {
            val parts = body.split("|")
            if (parts.size < 5) return null
            val convId = parts[0]
            val pubkey = parts[1]
            val relay = parts[2]
            val epoch = parts[3].toLongOrNull() ?: return null
            val sig = parts[4]
            if (!validCommon(convId, pubkey, relay, sig)) return null
            if (epoch < 0) return null
            return ZBootMessage(convId, pubkey, relay, sig, epoch = epoch, version = 4)
        }

        // ZBOOT|v3|<convID>|<pubkey>|<relay>|<epoch>|<sig>
        private fun parseV3(body: String): ZBootMessage? {
            val parts = body.split("|")
            if (parts.size < 5) return null
            val convId = parts[0]
            val pubkey = parts[1]
            val relay = parts[2]
            val epoch = parts[3].toLongOrNull() ?: return null
            val sig = parts[4]
            if (!validCommon(convId, pubkey, relay, sig)) return null
            if (epoch < 0) return null
            return ZBootMessage(convId, pubkey, relay, sig, epoch = epoch, version = 3)
        }

        // ZBOOT|v2|<convID>|<pubkey>|<relay>|<sig>  (legacy; epoch 0)
        private fun parseV2(body: String): ZBootMessage? {
            val parts = body.split("|")
            if (parts.size < 4) return null
            val convId = parts[0]
            val pubkey = parts[1]
            val relay = parts[2]
            val sig = parts[3]
            if (!validCommon(convId, pubkey, relay, sig)) return null
            return ZBootMessage(convId, pubkey, relay, sig, epoch = 0L, version = 2)
        }

        private fun validCommon(convId: String, pubkey: String, relay: String, sig: String): Boolean {
            if (convId.length != ZMSGConstants.CONV_ID_LENGTH) return false
            if (!HEX_64.matches(pubkey)) return false
            if (!relay.startsWith("wss://") || relay.length > 80) return false
            if (sig.isEmpty() || sig.length > 200) return false
            return true
        }
    }
}
