package co.electriccoin.zcash.ui.nostr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

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
        // Round 8 MA3: the kind-14 rumor's tags (Nip17.ReceivedMessage.rumorTags), e.g. the sender's gp1 capability.
        val rumorTags: List<List<String>> = emptyList(),
        // KI-24: the outer gift-wrap's created_at (seconds; Nip17.ReceivedMessage.wrapCreatedAtSec).
        val wrapCreatedAtSec: Long = createdAtSec,
    )

    /**
     * Result of [send]: relay ack count + the message's STABLE rumor id (for reaction correlation). ZC-393: [acks] counts
     * the relays every ZChat version reads (ZChat's relay, nos.lol) that took it, the count a "delivered" means;
     * [readableAcks] any relay that took it and serves us (the count before ZC-393). Without relay health both are one.
     */
    data class SendResult(
        val acks: Int,
        val rumorId: String,
        val readableAcks: Int = acks,
    )

    // 256 (was 64) — headroom for a relay (re)subscribe replaying a gift-wrap backlog burst; overflow
    // is still logged at the dispatch site rather than silently dropped.
    private val _inbound = MutableSharedFlow<InboundDm>(replay = 0, extraBufferCapacity = 256)
    val inbound: SharedFlow<InboundDm> = _inbound.asSharedFlow()

    // KI-24: DMs emitted on [inbound] that its collector has not yet handed to NostrChatBridge.dispatch
    // ([onDispatched]). While any is on the way, the relay `since` line does not move (see RelaySinceLine).
    private val undispatched = AtomicInteger(0)

    /** KI-24: the collector of [inbound] calls this after each NostrChatBridge.dispatch, also when it fails. */
    fun onDispatched() {
        undispatched.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /** KI-24: no DM is between the relay socket and NostrChatBridge.dispatch. */
    fun isDeliverySettled(): Boolean = undispatched.get() == 0

    // @Volatile: written on the caller/service thread (start/rotate/stop) and now also READ by the
    // long-lived liveness watchdog coroutine on Dispatchers.IO — the annotation guarantees that
    // cross-thread read sees the latest value (avoids a stale-null / stale-identity read at a tick).
    @Volatile private var identity: NOSTRIdentity? = null

    // #225 GRACE WINDOW: the identity we just rotated AWAY from. We keep its #p subscription live for a
    // bounded window so gift-wraps a peer addressed to our OLD pubkey (before they processed our rotation
    // announcement and switched to the new key) still arrive instead of being silently lost. Cleared
    // after [GRACE_PERIOD_MS] (or on the next rotation / stop).
    private var graceIdentity: NOSTRIdentity? = null
    private val graceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var graceJob: Job? = null

    // #nostr-stale-inbox (Jul 2026): PERIODIC liveness watchdog. The foreground-resume kick (refresh()) only
    // fires on ON_START, so a subscription that goes silently quiet WHILE the app stays foregrounded and idle
    // (relay stops delivering with no CLOSED frame — Doze suspends the keep-alive ping, or the relay drops our
    // REQ server-side) is never revived until a process restart. This is the NOSTR analogue of the SDK
    // sync-liveness watchdog: a timer that periodically re-issues the subscriptions. It uses the CHEAP
    // [NostrRelayPool.resubscribeLive] (re-send REQ on the live sockets, no teardown) rather than the heavy
    // full reconnect, so an idle-but-healthy foreground app pays only a few tiny REQ frames per tick — while a
    // genuinely dead socket is still rebuilt by the pool's own ping/onDisconnected loop. The pool skips the
    // re-issue entirely when a relay delivered an event within its freshness window (an active chat), so the
    // steady-state cost is negligible.
    private val watchdogScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null

    // ZC-459: the one extra health check shortly after start(). Kept, so stop() and the next start() cancel it.
    private var firstCheckJob: Job? = null

    /**
     * Start subscribing to gift-wraps addressed to [identity]'s pubkey. Safe to call
     * once; subsequent calls are no-ops (subscription is replayed automatically on
     * reconnect).
     */
    fun start(identity: NOSTRIdentity) {
        if (this.identity != null) return
        startInternal(identity, null)
        startLivenessWatchdog()
    }

    /**
     * Periodic liveness watchdog. Every [WATCHDOG_INTERVAL_MS] it re-issues the inbound subscriptions on the
     * live relays ([NostrRelayPool.resubscribeLive]), so a subscription that dies while the app stays
     * foregrounded (no ON_START to trigger [refresh]) still self-heals within one interval. The pool
     * freshness-guards the call, so it is a cheap no-op whenever inbound is actually flowing. Launched once
     * per [start]; survives [rotate]/grace transitions (they restart the pool but not this manager); cancelled
     * in [stop]. The re-issue runs in a detached coroutine inside the pool, so nothing here throws — the loop
     * simply keeps ticking.
     */
    private fun startLivenessWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = watchdogScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (identity == null) continue // inbox stopped between ticks — nothing to revive
                // A DM/reaction that arrived while no ChatViewModel collector was alive was left un-persisted-seen
                // by the bridge so a relay replay can redeliver it — but the pool's cross-relay dedup would swallow
                // that replay and resubscribeLive() does NOT clear it. When the bridge signals a pending redelivery
                // AND a collector is alive now, clear the dedup once and force the replay so the missed event
                // reaches the live collector; otherwise pay only the cheap freshness-guarded re-issue. Bounded:
                // the consume-once flag + collector-liveness gate keep the full backlog re-decrypt to at most once
                // per missed-event episode, never on every idle tick.
                if (NostrChatBridge.consumePendingRedelivery()) {
                    pool.redeliverBacklog()
                } else {
                    pool.resubscribeLive()
                }
                // RELAY-HEALTH: on the same tick, the pool judges its relays (swap, compat return, outbox).
                pool.healthTick()
            }
        }
        // RELAY-HEALTH: one extra check shortly after start, so a relay that refused the first REQ is replaced within
        // about a minute instead of a full tick later.
        // ZC-459: one per running inbox, the last start's.
        firstCheckJob?.cancel()
        firstCheckJob =
            pool.firstHealthCheckDelayMs()?.let { delayMs ->
                watchdogScope.launch {
                    delay(delayMs)
                    if (identity != null) pool.healthTick()
                }
            }
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
            undispatched.incrementAndGet()
            val ok = _inbound.tryEmit(dm)
            if (!ok) {
                undispatched.updateAndGet { (it - 1).coerceAtLeast(0) }
                // KI-24: never marked seen, so a relay replay can bring it back; ask for one, and keep the since
                // line where it is until it has come (NostrChatBridge.isRelayBacklogSettled).
                NostrChatBridge.flagRedelivery()
                Log.w(TAG, "inbound buffer overflow — dropping DM, left for relay redelivery")
            }
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
                rumorTags = dm.rumorTags,
                wrapCreatedAtSec = dm.wrapCreatedAtSec,
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
        watchdogJob?.cancel()
        watchdogJob = null
        firstCheckJob?.cancel()
        firstCheckJob = null
        graceJob?.cancel()
        graceIdentity = null
        pool.stop()
        identity = null
    }

    /**
     * Foreground liveness kick (#nostr-stale-inbox). Reconnect the relay pool if a subscription has gone
     * silently quiet (a relay can stop delivering with no CLOSED frame — common after background/Doze — so
     * the pool's socket-death reconnect never fires and inbound DMs/reactions stall until a process restart).
     * No-op before start() and throttled/staleness-guarded inside the pool, so calling it on every app
     * foreground is cheap and never churns a healthy connection.
     */
    fun refresh() {
        if (identity == null) return
        pool.reconnectIfStale()
    }

    /**
     * Wrap + sign + publish a NIP-17 DM to [recipientPubkeyHex]. Suspends until every
     * relay returns OK or fails. Returns the number of relays that ack'd.
     */
    suspend fun send(plaintext: String, recipientPubkeyHex: String, ttlSeconds: Long? = null): SendResult {
        val id = identity ?: error("NostrInboxManager.start() must be called before send()")
        return publishWrap(wrap(id, plaintext, recipientPubkeyHex, ttlSeconds, rumorAtSec = null), recipientPubkeyHex)
    }

    /**
     * ZC-421: [send] with the rumor made at [rumorAtSec] (the seal and the wrap are made now). A Retry passes the second of
     * the rumor it sent before, so the same message is the same rumor, with the same id, in a new wrap. When
     * [expectedRumorId] is given and the rumor built now has another id (our relay key rotated, another account, another
     * payload) nothing is published and the answer is null. The wrap is kept for [resendKept] exactly as [send]'s is.
     */
    suspend fun sendRumorAt(
        plaintext: String,
        recipientPubkeyHex: String,
        rumorAtSec: Long,
        expectedRumorId: String? = null,
    ): SendResult? {
        val id = identity ?: error("NostrInboxManager.start() must be called before sendRumorAt()")
        val wrap = wrap(id, plaintext, recipientPubkeyHex, ttlSeconds = null, rumorAtSec = rumorAtSec)
        if (expectedRumorId != null && wrap.rumorId != expectedRumorId) {
            Log.w(TAG, "sendRumorAt: the rumor ${wrap.rumorId.take(8)} is not the expected ${expectedRumorId.take(8)}; nothing published")
            return null
        }
        return publishWrap(wrap, recipientPubkeyHex)
    }

    /** ZC-421: the id the rumor of [sendRumorAt] would have (nothing is published); null before [start]. */
    fun rumorIdAt(
        plaintext: String,
        recipientPubkeyHex: String,
        rumorAtSec: Long,
    ): String? {
        val id = identity ?: return null
        return Nip17.rumorId(id.publicKey, hexToBytes(recipientPubkeyHex), plaintext, rumorAtSec)
    }

    private fun wrap(
        id: NOSTRIdentity,
        plaintext: String,
        recipientPubkeyHex: String,
        ttlSeconds: Long?,
        rumorAtSec: Long?,
    ): Nip17.SentMessage {
        val recipientPub = hexToBytes(recipientPubkeyHex)
        val nowSec = System.currentTimeMillis() / 1000
        // ttlSeconds set only for ephemeral CALL signaling → NIP-40 expiration so the relay
        // auto-deletes it. Chat DMs pass null (must persist for offline delivery).
        val expiresAtSec = ttlSeconds?.let { nowSec + it }
        return Nip17.send(
            senderPriv = id.privateKey,
            senderPub = id.publicKey,
            recipientPub = recipientPub,
            content = plaintext,
            createdAtSec = nowSec,
            expiresAtSec = expiresAtSec,
            rumorCreatedAtSec = rumorAtSec ?: nowSec,
        )
    }

    private suspend fun publishWrap(
        wrap: Nip17.SentMessage,
        recipientPubkeyHex: String,
    ): SendResult {
        // ZC-393: tagged, so a Retry of a send only a public relay took re-sends this wrap ([resendKept]).
        val count = pool.publishCounted(wrap.giftWrapJson, tag = keptTag(recipientPubkeyHex, wrap.rumorId))
        return SendResult(acks = count.shared, rumorId = wrap.rumorId, readableAcks = count.readable)
    }

    /**
     * ZC-393: a Retry of the message [rumorId] to [recipientPubkeyHex]: the wrap the pool's outbox keeps goes again (same
     * event id, same rumor id) to the relays that lack it, and the counts come from the relays that hold it. Null when
     * nothing is kept for it (a restart, dropped, older than 24 h): the caller makes a new wrap.
     */
    suspend fun resendKept(
        rumorId: String,
        recipientPubkeyHex: String,
    ): SendResult? {
        if (rumorId.isEmpty()) return null
        val count = pool.republishKept(keptTag(recipientPubkeyHex, rumorId)) ?: return null
        return SendResult(acks = count.shared, rumorId = rumorId, readableAcks = count.readable)
    }

    private fun keptTag(
        recipientPubkeyHex: String,
        rumorId: String,
    ): String = "${recipientPubkeyHex.lowercase()}:$rumorId"

    companion object {
        private const val TAG = "NostrInboxManager"
        private const val KIND_GIFT_WRAP = 1059

        // #nostr-stale-inbox: how often the liveness watchdog probes for a silently-quiet subscription.
        // Longer than the pool's LIVENESS_FRESH_MS (60s) freshness guard so an actively-delivering
        // subscription is always seen as "fresh" and never reconnected; short enough that a stale inbox
        // recovers within ~2 min instead of "never, until app restart" (the bug this fixes).
        private const val WATCHDOG_INTERVAL_MS = 120_000L

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
