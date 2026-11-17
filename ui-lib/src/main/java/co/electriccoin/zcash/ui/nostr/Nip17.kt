package co.electriccoin.zcash.ui.nostr

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

/**
 * NIP-17 private direct messages, built on NIP-59 gift-wraps and NIP-44 encryption.
 *
 * Three layers per message:
 *   1. Rumor (kind 14, UNSIGNED) — the actual chat content.
 *   2. Seal (kind 13, signed by SENDER) — content = NIP-44(rumor json, sender_priv, recipient_pub).
 *      The seal's signature proves the sender's identity to the recipient.
 *   3. Gift wrap (kind 1059, signed by a one-time RANDOM key) — content = NIP-44(seal json,
 *      random_priv, recipient_pub). p tag = recipient. created_at jittered into the last
 *      48h to break relay-side timing correlation.
 *
 * The relay sees only the gift wrap: a kind-1059 event from a random pubkey to the
 * recipient. Sender identity is hidden from the relay.
 */
object Nip17 {

    private const val KIND_DM_RUMOR = 14
    private const val KIND_SEAL = 13
    private const val KIND_GIFT_WRAP = 1059
    private const val JITTER_WINDOW_SEC = 48L * 60 * 60

    /**
     * Bridge 1 capability tag, carried ONLY inside the encrypted kind-14 rumor (never on the seal or
     * the gift wrap, so relays can't see it). It says "this sender runs an Ironwood-capable ZChat";
     * it carries no version number. 2.12.4 ignores unknown rumor tags (receive reads only pubkey,
     * created_at, content and id), and the rumor id both sides compute includes it.
     */
    internal val CAPABILITY_TAG: List<String> = listOf("zchat_cap", "ironwood")

    /**
     * Round 8 MA1 (member announce, design §2.4) — "this sender can read a group member announce (GP)". A tag of its
     * own because the 2.13.0 test builds already send [CAPABILITY_TAG] but cannot read GP. Same rules: inside the
     * rumor only, no version number, ignored by 2.12.4. Only sent here; the admin starts recording it in MA3.
     */
    internal val GP_CAPABILITY_TAG: List<String> = listOf("zchat_cap", "gp1")

    /**
     * ZC-1474 (W-123) — "this sender reads a free attachment (ZATT)". A tag of its own because the 2.13.0 test builds
     * already send [CAPABILITY_TAG] but would show a ZATT as raw text, its file key included. Same rules: inside the
     * rumor only, no version number, ignored by 2.12.4. The newest rumor from a contact decides (not sticky).
     */
    internal val ZATT_CAPABILITY_TAG: List<String> = listOf("zchat_cap", "zatt1")

    data class SentMessage(val giftWrapJson: String, val rumorId: String = "")

    data class ReceivedMessage(
        val content: String,
        val senderPubkey: ByteArray,
        val createdAtSec: Long,
        // Unique per gift-wrap (sha256 of the canonical kind-1059 event). Identical across relays
        // for the same message, but distinct for two different messages — used as the dedup key so
        // two messages with the same text in the same second aren't collapsed into one.
        val eventId: String,
        // STABLE cross-device id: the NIP-01 id of the inner kind-14 rumor (sha256 of its canonical
        // form). The SENDER computes the identical id from the same rumor, so both sides agree on it —
        // unlike eventId (a per-recipient gift-wrap with a random ephemeral key). Used to correlate
        // reactions / replies / read-receipts to the message they target across the two devices.
        val rumorId: String = "",
        // The inner kind-14 rumor's tags, as sent (e.g. ["p", <recipient>], ["zchat_cap", "ironwood"], ["zchat_cap", "gp1"]).
        // Informational only: nothing here is authenticated beyond the seal signature that already
        // covers the whole rumor, and an unreadable tags array yields an empty list, never a failure.
        val rumorTags: List<List<String>> = emptyList(),
        // KI-24: the OUTER gift-wrap's created_at (seconds), from the verified wrap. NIP-59 back-dates it by up
        // to 48 h, and it is what a relay's `since` filter and the seen list's floor compare against.
        val wrapCreatedAtSec: Long = createdAtSec,
    )

    /**
     * Encrypt + wrap a DM. The returned [SentMessage.giftWrapJson] is a kind-1059 NIP-01
     * event ready to push to one or more relays.
     */
    fun send(
        senderPriv: ByteArray,
        senderPub: ByteArray,
        recipientPub: ByteArray,
        content: String,
        createdAtSec: Long = System.currentTimeMillis() / 1000,
        // NIP-40 expiration (unix seconds) put ONLY on the outer gift wrap, so the relay can
        // auto-delete it. Used for ephemeral CALL signaling (short TTL); null for chat DMs (which
        // must persist for offline delivery). The seal/rumor created_at stays real regardless.
        expiresAtSec: Long? = null,
        // ZC-421: the rumor's own created_at. A Retry that makes a relay message again passes its first try's second here,
        // so the rumor (and its id) is the same one, in a new seal and a new wrap made at [createdAtSec] (now).
        rumorCreatedAtSec: Long = createdAtSec,
    ): SentMessage {
        val rumor = rumorJson(senderPub, recipientPub, content, rumorCreatedAtSec)
        // The rumor's NIP-01 id is content-derived (sha256 of its canonical form) → the recipient,
        // decrypting the SAME rumor, computes the SAME id. Carry it so the chat layer can use it as the
        // shared message id (reaction/reply correlation).
        val rumorId = NostrEvent.parseId(rumor)
        return wrapAndSeal(
            sealSignerPriv = senderPriv,
            sealClaimedPub = senderPub,
            recipientPub = recipientPub,
            rumorJson = rumor,
            sealCreatedAtSec = createdAtSec,
            expiresAtSec = expiresAtSec,
        ).copy(rumorId = rumorId)
    }

    /**
     * ZC-421: the id the rumor of [send] has for these inputs (a hash of the rumor; nothing is signed, encrypted or sent).
     */
    fun rumorId(
        senderPub: ByteArray,
        recipientPub: ByteArray,
        content: String,
        rumorCreatedAtSec: Long,
    ): String = NostrEvent.parseId(rumorJson(senderPub, recipientPub, content, rumorCreatedAtSec))

    private fun rumorJson(
        senderPub: ByteArray,
        recipientPub: ByteArray,
        content: String,
        createdAtSec: Long,
    ): String =
        NostrEvent.unsignedSerialize(
            pubkeyHex = senderPub.toHex(),
            createdAtSec = createdAtSec,
            kind = KIND_DM_RUMOR,
            tags = listOf(listOf("p", recipientPub.toHex()), CAPABILITY_TAG, GP_CAPABILITY_TAG, ZATT_CAPABILITY_TAG),
            content = content,
        )

    /**
     * Test-only escape hatch: forge a seal claiming a different sender. Used to verify
     * that a recipient rejects a message whose seal pubkey doesn't match the seal signer.
     * Production callers must NOT use this.
     */
    internal fun sendForgingSender(
        actualSenderPriv: ByteArray,
        claimedSenderPub: ByteArray,
        recipientPub: ByteArray,
        content: String,
    ): SentMessage {
        val now = System.currentTimeMillis() / 1000
        val rumor = NostrEvent.unsignedSerialize(
            pubkeyHex = claimedSenderPub.toHex(),
            createdAtSec = now,
            kind = KIND_DM_RUMOR,
            tags = listOf(listOf("p", recipientPub.toHex())),
            content = content,
        )
        return wrapAndSeal(
            sealSignerPriv = actualSenderPriv,
            sealClaimedPub = claimedSenderPub,
            recipientPub = recipientPub,
            rumorJson = rumor,
            sealCreatedAtSec = now,
        )
    }

    private fun wrapAndSeal(
        sealSignerPriv: ByteArray,
        sealClaimedPub: ByteArray,
        recipientPub: ByteArray,
        rumorJson: String,
        sealCreatedAtSec: Long,
        expiresAtSec: Long? = null,
    ): SentMessage {
        // Seal: kind 13, signed by the actual seal signer. NIP-17 says the pubkey field
        // here must be the real sender — the recipient verifies this matches the rumor.
        // Use the *signer's* x-only pubkey so the Schnorr verify in NostrEvent.verify
        // succeeds.
        val sealSignerPub = derivePubkey(sealSignerPriv)
        val sealPubForVerify = sealSignerPub.toHex()
        val sealContentEnc = Nip44Encryption.encrypt(rumorJson, sealSignerPriv, recipientPub)
        val sealJson = NostrEvent.signAndSerialize(
            pubkeyHex = sealPubForVerify,
            privateKey = sealSignerPriv,
            createdAtSec = sealCreatedAtSec,
            kind = KIND_SEAL,
            tags = emptyList(),
            content = sealContentEnc,
        )

        // Gift wrap: kind 1059, signed by a random one-time key. Hides sender identity.
        // Embed the *claimed* sender pubkey inside an extra tag so the recipient can
        // route on it without decrypting; not in the NIP-17 spec, but inexpensive and
        // useful for conversation filters. We MUST NOT trust it: the seal sig is the
        // real proof.
        val wrapPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapPub = derivePubkey(wrapPriv)
        val wrapCreatedAt = jitterPastTimestamp(sealCreatedAtSec)
        val wrapContentEnc = Nip44Encryption.encrypt(sealJson, wrapPriv, recipientPub)
        // p tag = recipient; optional NIP-40 expiration tag for ephemeral (call) signals.
        val wrapTags = buildList {
            add(listOf("p", recipientPub.toHex()))
            if (expiresAtSec != null) add(listOf("expiration", expiresAtSec.toString()))
        }
        val wrapJson = NostrEvent.signAndSerialize(
            pubkeyHex = wrapPub.toHex(),
            privateKey = wrapPriv,
            createdAtSec = wrapCreatedAt,
            kind = KIND_GIFT_WRAP,
            tags = wrapTags,
            content = wrapContentEnc,
        )

        // Also stash the claimed sender for the rejection-of-forgery test path: we
        // simply expose nothing extra in the JSON for production use.
        @Suppress("UNUSED_VARIABLE")
        val claimed = sealClaimedPub

        return SentMessage(wrapJson)
    }

    /**
     * Unwrap, verify, decrypt. Throws if any layer fails (HMAC, sig, recipient mismatch,
     * pubkey forgery in the seal).
     */
    fun receive(giftWrapJson: String, recipientPriv: ByteArray): ReceivedMessage {
        // Verify the gift wrap's own signature first so a corrupted envelope fails fast.
        val wrap = NostrEvent.verify(giftWrapJson)
        require(wrap.kind == KIND_GIFT_WRAP) { "not a kind-1059 gift wrap" }

        val sealJson = Nip44Encryption.decrypt(
            b64Payload = wrap.content,
            recipientPriv = recipientPriv,
            senderXOnlyPub = hexToBytes(wrap.pubkeyHex),
        )

        // Verify the inner seal's signature.
        val seal = NostrEvent.verify(sealJson)
        require(seal.kind == KIND_SEAL) { "inner event is not a seal" }

        val rumorJson = Nip44Encryption.decrypt(
            b64Payload = seal.content,
            recipientPriv = recipientPriv,
            senderXOnlyPub = hexToBytes(seal.pubkeyHex),
        )

        val rumorPubkey = NostrEvent.parsePubkey(rumorJson)
        require(rumorPubkey == seal.pubkeyHex) {
            "rumor pubkey ($rumorPubkey) doesn't match seal signer (${seal.pubkeyHex}) — possible forgery"
        }
        val content = NostrEvent.parseContent(rumorJson)
        val createdAt = NostrEvent.parseCreatedAt(rumorJson)
        return ReceivedMessage(
            content = content,
            senderPubkey = hexToBytes(seal.pubkeyHex),
            createdAtSec = createdAt,
            eventId = wrap.id,
            rumorId = NostrEvent.parseId(rumorJson),
            // Never fails the receive: tags we can't read are left out (see NostrEvent.parseTags).
            rumorTags = NostrEvent.parseTags(rumorJson),
            wrapCreatedAtSec = wrap.createdAtSec,
        )
    }

    /**
     * Pick a random timestamp uniformly in the [referenceSec - 48h, referenceSec] window
     * to defeat correlation between rumor created_at and wrap created_at.
     */
    private fun jitterPastTimestamp(referenceSec: Long): Long {
        val r = SecureRandom()
        val deltaSec = (r.nextDouble() * JITTER_WINDOW_SEC).toLong()
        return referenceSec - deltaSec
    }

    private fun derivePubkey(priv: ByteArray): ByteArray {
        val pub65 = Secp256k1.pubkeyCreate(priv)
        return pub65.copyOfRange(1, 33)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
