package co.electriccoin.zcash.ui.nostr

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Glue between [NostrRelayPool] and the rest of the app.
 *
 * Inbound:
 *   start() opens the pool, subscribes to kind-1059 gift-wraps tagged with our pubkey,
 *   decrypts via [Nip17.receive], and emits an [InboundDm] on [inbound] for the chat
 *   layer to pick up.
 *
 * Outbound:
 *   send(plaintext, recipientPub) wraps the plaintext as a NIP-17 DM and publishes to
 *   every connected relay. Returns true if at least one relay ack'd.
 *
 * State:
 *   The manager is *stateless* about conversations — it just delivers raw decrypted
 *   strings. The chat layer (ChatViewModel) is responsible for mapping the sender's
 *   NOSTR pubkey back to a peer Zcash address.
 */
class NostrInboxManager(
    private val pool: NostrRelayPool = NostrRelayPool(),
) {
    data class InboundDm(
        val senderPubkeyHex: String,
        val content: String,
        val createdAtSec: Long,
        // Unique per gift-wrap; carried through so the chat layer can dedup on it.
        val eventId: String,
        // STABLE cross-device rumor id — both sides agree on it (see Nip17.ReceivedMessage.rumorId).
        val rumorId: String = "",
    )

    /** Result of [send]: relay ack count + the message's STABLE rumor id (for reaction correlation). */
    data class SendResult(val acks: Int, val rumorId: String)

    // 256 (was 64) — headroom for a relay (re)subscribe replaying a gift-wrap backlog burst; overflow
    // is still logged at the dispatch site rather than silently dropped.
    private val _inbound = MutableSharedFlow<InboundDm>(replay = 0, extraBufferCapacity = 256)
    val inbound: SharedFlow<InboundDm> = _inbound.asSharedFlow()

    private var identity: NOSTRIdentity? = null

    /**
     * Start subscribing to gift-wraps addressed to [identity]'s pubkey. Safe to call
     * once; subsequent calls are no-ops (subscription is replayed automatically on
     * reconnect).
     */
    fun start(identity: NOSTRIdentity) {
        if (this.identity != null) return
        this.identity = identity
        pool.start()
        val ourPubHex = identity.publicKey.toLowerHex()
        val filter = mapOf<String, Any>(
            "kinds" to listOf(KIND_GIFT_WRAP),
            "#p" to listOf(ourPubHex),
        )
        pool.subscribe(filter) { eventJson ->
            Log.d(TAG, "inbound gift-wrap arrived (len=${eventJson.length}) — decrypting")
            try {
                val dm = Nip17.receive(eventJson, identity.privateKey)
                val ok = _inbound.tryEmit(
                    InboundDm(
                        senderPubkeyHex = dm.senderPubkey.toLowerHex(),
                        content = dm.content,
                        createdAtSec = dm.createdAtSec,
                        eventId = dm.eventId,
                        rumorId = dm.rumorId,
                    ),
                )
                if (!ok) Log.w(TAG, "inbound buffer overflow — dropping DM")
            } catch (e: Throwable) {
                // Don't crash the subscription on a malformed event; log + skip.
                Log.w(TAG, "ignoring bad gift-wrap: ${e.message}")
            }
        }
    }

    /**
     * Hot-swap the inbound subscription to a rotated NOSTR identity WITHOUT an app restart (#188).
     * Previously a key rotation only re-keyed OUTBOUND (peers were re-KEXed) while the running inbox
     * stayed bound to the old pubkey until the process was killed, so the user had to restart to keep
     * receiving. This tears the pool down and re-subscribes under the new pubkey — a brief reconnect,
     * acceptable for a rare, user-initiated rotation, and it fully drops the old server-side filter
     * (unsubscribe alone leaves it lingering until disconnect). No-op if already on this key.
     */
    fun rotate(newIdentity: NOSTRIdentity) {
        val current = identity
        if (current != null && current.publicKey.contentEquals(newIdentity.publicKey)) {
            Log.d(TAG, "rotate(): already subscribed under this pubkey — no-op")
            return
        }
        Log.d(TAG, "rotate(): hot-swapping inbox subscription to rotated NOSTR pubkey")
        stop()              // clears identity + tears down the pool/subscriptions
        start(newIdentity)  // re-opens and subscribes under the new pubkey
    }

    fun stop() {
        pool.stop()
        identity = null
    }

    /**
     * Wrap + sign + publish a NIP-17 DM to [recipientPubkeyHex]. Suspends until every
     * relay returns OK or fails. Returns the number of relays that ack'd.
     */
    suspend fun send(plaintext: String, recipientPubkeyHex: String, ttlSeconds: Long? = null): SendResult {
        val id = identity ?: error("NostrInboxManager.start() must be called before send()")
        val recipientPub = hexToBytes(recipientPubkeyHex)
        // ttlSeconds set only for ephemeral CALL signaling → NIP-40 expiration so the relay
        // auto-deletes it. Chat DMs pass null (must persist for offline delivery).
        val expiresAtSec = ttlSeconds?.let { (System.currentTimeMillis() / 1000) + it }
        val wrap = Nip17.send(
            senderPriv = id.privateKey,
            senderPub = id.publicKey,
            recipientPub = recipientPub,
            content = plaintext,
            expiresAtSec = expiresAtSec,
        )
        return SendResult(acks = pool.publish(wrap.giftWrapJson), rumorId = wrap.rumorId)
    }

    companion object {
        private const val TAG = "NostrInboxManager"
        private const val KIND_GIFT_WRAP = 1059

        private fun ByteArray.toLowerHex(): String =
            joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "odd hex length" }
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
