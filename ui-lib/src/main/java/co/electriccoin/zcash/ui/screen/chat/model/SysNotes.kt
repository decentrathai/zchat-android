package co.electriccoin.zcash.ui.screen.chat.model

import java.security.MessageDigest

/**
 * Stable ids for persisted in-chat SYSTEM NOTES (centered pills, not sender bubbles): peer key rotation
 * (B7a), VAULT→TUNNEL flow upgrade (B7b), a known-peer contact request (B8), and disappearing-message TTL
 * changes (B17). Pure Kotlin (no Android deps) so it's unit-tested. The id is the dedup key — the persisted
 * store (addPendingMessage) overwrites by id, and the live collector id-dedups — so a stable id gives
 * exactly one pill per distinct event and idempotent re-emission on re-scan/restore.
 */
object SysNotes {
    const val ID_PREFIX = "sysnote-"

    fun isSystemNoteId(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** One pill per distinct ZBOOT signature (a genuinely new rotation gets its own pill). */
    fun rotationNoteId(bootSignature: String): String = "${ID_PREFIX}rot-" + sha256Hex(bootSignature).take(12)

    /** One pill per adopted epoch of a VAULT→TUNNEL auto-upgrade. */
    fun modeUpgradeNoteId(peerAddress: String, epoch: Long): String =
        "${ID_PREFIX}modeup-" + sha256Hex(peerAddress).take(12) + "-e$epoch"

    /** MUST include the claimed address: the same attacker pubkey claiming two different contacts must
     *  produce two ids, or the id-keyed prefs overwrite would MOVE the pill between conversations. */
    fun requestNoteId(senderPubkeyHex: String, claimedAddress: String): String =
        "${ID_PREFIX}req-" + senderPubkeyHex.take(12) + "-" + sha256Hex(claimedAddress).take(8)

    /** One pill per (peer, effective-since) TTL change (B17). Hashes the address — no raw address in the id. */
    fun ttlNoteId(peerAddress: String, sinceMillis: Long): String =
        "${ID_PREFIX}ttl-" + sha256Hex(peerAddress).take(12) + "-$sinceMillis"

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
