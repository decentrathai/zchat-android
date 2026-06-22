package co.electriccoin.zcash.ui.nostr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

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
        // #252: the x-only hex of OUR pubkey this gift-wrap was actually wrapped to (the key that decrypted
        // it — the current/primary key OR the rotation GRACE key). A NIP-17 wrap is encrypted to exactly one
        // recipient pubkey, so this is ground truth for "which of our rotation keys does the sender hold".
        // The chat layer maps it back to OUR rotation index and advances per-peer rotation bookkeeping to
        // EXACTLY that index — never further (TOCTOU-safe vs a live rotation), and far enough that a peer
        // who reached an OLD (grace) key still self-heals. Replaces the unsafe relay-ack signal (a relay ack
        // only proves a wrap was STORED, not that an offline peer fetched + adopted it). Blank = unknown.
        val recipientPubkeyHex: String = "",
    )

    /** Result of [send]: relay ack count + the message's STABLE rumor id (for reaction correlation). */
    data class SendResult(val acks: Int, val rumorId: String)

    // 256 (was 64) — headroom for a relay (re)subscribe replaying a gift-wrap backlog burst; overflow
    // is still logged at the dispatch site rather than silently dropped.
    private val _inbound = MutableSharedFlow<InboundDm>(replay = 0, extraBufferCapacity = 256)
    val inbound: SharedFlow<InboundDm> = _inbound.asSharedFlow()

    private var identity: NOSTRIdentity? = null

    // #225 GRACE WINDOW: the identity we just rotated AWAY from. We keep its #p subscription live for a
    // bounded window so gift-wraps a peer addressed to our OLD pubkey (before they processed our rotation
    // announcement and switched to the new key) still arrive instead of being silently lost. Cleared
    // after [GRACE_PERIOD_MS] (or on the next rotation / stop).
    private var graceIdentity: NOSTRIdentity? = null
    private val graceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var graceJob: Job? = null

    /**
     * Start subscribing to gift-wraps addressed to [identity]'s pubkey. Safe to call
     * once; subsequent calls are no-ops (subscription is replayed automatically on
     * reconnect).
     */
    fun start(identity: NOSTRIdentity) {
        if (this.identity != null) return
        startInternal(identity, null)
    }

    /**
     * (Re)subscribe under [primary]'s pubkey, and — during a rotation grace window — ALSO under
     * [grace]'s pubkey so DMs a peer still addressed to the old key are caught. Each inbound gift-wrap
     * is decrypted with whichever of the two private keys succeeds (a NIP-17 wrap is encrypted to a
     * single recipient pubkey, so only the matching key decrypts it).
     */
    private fun startInternal(primary: NOSTRIdentity, grace: NOSTRIdentity?) {
        this.identity = primary
        pool.start()
        val pubs = buildList {
            add(primary.publicKey.toLowerHex())
            grace?.let { add(it.publicKey.toLowerHex()) }
        }
        val filter = mapOf<String, Any>(
            "kinds" to listOf(KIND_GIFT_WRAP),
            "#p" to pubs,
        )
        pool.subscribe(filter) { eventJson ->
            Log.d(TAG, "inbound gift-wrap arrived (len=${eventJson.length}) — decrypting")
            // Try the primary key first, then the grace key (only one will decrypt a given wrap).
            // #252: decryptWith records WHICH of our pubkeys decrypted it (primary=current, grace=old), so
            // the chat layer can map that pubkey back to our rotation index and advance bookkeeping to
            // exactly the index the sender proved they hold.
            val dm = decryptWith(eventJson, primary) ?: grace?.let { decryptWith(eventJson, it) }
            if (dm == null) {
                Log.w(TAG, "ignoring gift-wrap not decryptable by current/grace key")
                return@subscribe
            }
            val ok = _inbound.tryEmit(dm)
            if (!ok) Log.w(TAG, "inbound buffer overflow — dropping DM")
        }
    }

    private fun decryptWith(eventJson: String, id: NOSTRIdentity): InboundDm? =
        try {
            val dm = Nip17.receive(eventJson, id.privateKey)
            InboundDm(
                senderPubkeyHex = dm.senderPubkey.toLowerHex(),
                content = dm.content,
                createdAtSec = dm.createdAtSec,
                eventId = dm.eventId,
                rumorId = dm.rumorId,
                // #252: the OUR-side pubkey that decrypted this wrap (= the key the sender holds for us).
                recipientPubkeyHex = id.publicKey.toLowerHex(),
            )
        } catch (e: Throwable) {
            null
        }

    /**
     * Hot-swap the inbound subscription to a rotated NOSTR identity WITHOUT an app restart (#188).
     * Re-subscribes under the new pubkey AND keeps the OLD pubkey subscribed for a bounded grace window
     * (#225) so gift-wraps a peer sent to the old key — before adopting our rotation announcement —
     * still land instead of being silently lost. The grace subscription is dropped after
     * [GRACE_PERIOD_MS]. No-op if already on this key.
     */
    fun rotate(newIdentity: NOSTRIdentity) {
        val current = identity
        if (current != null && current.publicKey.contentEquals(newIdentity.publicKey)) {
            Log.d(TAG, "rotate(): already subscribed under this pubkey — no-op")
            return
        }
        Log.d(TAG, "rotate(): hot-swapping inbox to rotated pubkey (old key kept for ${GRACE_PERIOD_MS / 1000}s grace)")
        graceIdentity = current
        graceJob?.cancel()
        pool.stop()
        identity = null
        startInternal(newIdentity, graceIdentity)
        // Drop the old-key subscription after the grace window so a rotated-away key doesn't linger
        // (forward privacy). If the peer is still offline past this, the durable on-chain ZBOOT + a
        // re-KEX on next contact re-establish delivery.
        if (graceIdentity != null) {
            graceJob = graceScope.launch {
                delay(GRACE_PERIOD_MS)
                endGracePeriod()
            }
        }
    }

    private fun endGracePeriod() {
        val keep = identity ?: return
        if (graceIdentity == null) return
        Log.d(TAG, "rotate(): grace window elapsed — dropping old-key subscription")
        graceIdentity = null
        pool.stop()
        identity = null
        startInternal(keep, null)
    }

    fun stop() {
        graceJob?.cancel()
        graceIdentity = null
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

        // #225 rotation grace window: keep the rotated-away pubkey subscribed this long so in-flight DMs
        // a peer addressed to the old key during announcement propagation still arrive. Bounded so a
        // rotated key doesn't linger and erode forward privacy. 15 min covers relay latency + a briefly
        // offline peer; longer-offline peers recover via the durable on-chain ZBOOT + re-KEX.
        private const val GRACE_PERIOD_MS = 15L * 60 * 1000

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
