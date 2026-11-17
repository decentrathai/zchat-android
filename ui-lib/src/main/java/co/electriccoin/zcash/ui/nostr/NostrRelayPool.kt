package co.electriccoin.zcash.ui.nostr

import android.util.Log
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Tiny pool that keeps RelayClient connections alive, retries on failure with backoff,
 * and fans subscriptions across all configured relays. Designed for use inside a
 * foreground service — owns its own SupervisorJob so a single failing relay doesn't
 * cancel the others.
 *
 * The default relays are compiled in (no user entry). RELAY-HEALTH (2.13.0): with a [health] config the pool also
 * watches its relays and swaps an unavailable public relay (slot 1 or 2) for another from [RelayCatalog]; slot 0,
 * ZChat's relay, is never swapped. Without one (null) the set is fixed, as before.
 */
class NostrRelayPool(
    private val relayUrls: List<String> = DEFAULT_RELAYS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    // KI-24 part B: each relay's `since` line (RelaySinceLine). Null: no REQ carries a `since`, as before KI-24.
    private val sinceStore: RelaySinceStore? = null,
    // RELAY-HEALTH: the health rules' numbers, catalog and clock. Null: a fixed pool (no health, no swap, no outbox).
    private val health: RelayHealthConfig? = null,
    // RELAY-HEALTH: where the relay set is saved (written only on a swap). Null: nothing is saved.
    private val relaySet: RelaySetStore? = null,
) {
    private data class RelayState(
        val url: String,
        var client: RelayClient,
        var connectJob: Job? = null,
        var connected: Boolean = false,
        // When the last re-subscribe after a CLOSED was decided (ZC-482: [resubNowMs], monotonic); null: none yet.
        @Volatile var lastResubAt: Long? = null,
        // T1b-2: the relay's position in the list (what the debug seam BLOCK_ONE_RELAY names); with relay health, its slot.
        val index: Int = 0,
        // T1b-2: the relay CLOSED our read subscription with "auth-required" (NIP-42, which we do not do), so it never
        // serves us anything. Kept for the pool's life (it survives socket rebuilds): its publish OKs are not counted
        // as delivery acks and its CLOSED no longer triggers a re-subscribe loop. RELAY-HEALTH: "restricted:" and
        // "blocked:" too.
        @Volatile var readBlocked: Boolean = false,
        // RELAY-HEALTH: swapped out: closed for good, its late callbacks do nothing.
        @Volatile var retired: Boolean = false,
        // RELAY-HEALTH: the first EOSE on the current socket has come (the outbox is flushed then).
        @Volatile var eosedOnSocket: Boolean = false,
        // T5 fixup RH-X1: a delayed re-read of read-blocked slot 0 is waiting (at most one at a time).
        val ownReadRetryPending: AtomicBoolean = AtomicBoolean(false),
        // ZC-406: the re-subscribe window in force (0: the base); it doubles for each deferred re-subscribe until the
        // relay answers a read again (an EOSE or EVENT from it), up to the cap.
        @Volatile var resubWindowMs: Long = 0L,
        // ZC-406: a deferred re-subscribe is waiting (at most one at a time per relay).
        val resubPending: AtomicBoolean = AtomicBoolean(false),
    )

    /** ZC-406: what a CLOSED (not a read block) leads to: a re-subscribe after [waitMs], [deferred] in the window. */
    private class ResubPlan(
        val waitMs: Long,
        val deferred: Boolean,
    )

    // RELAY-HEALTH: copy-on-write, so a swap replaces one slot while other threads iterate the old list.
    @Volatile
    private var relays: List<RelayState> =
        relayUrls.mapIndexed { i, url -> RelayState(url, RelayClient(url), index = i) }

    private val policy: RelayHealthPolicy? =
        health?.let { config ->
            RelayHealthPolicy(relayUrls, config) {
                // Debug seam: the DOWN/SUSPECT waits, swap spacing and compat re-probe in seconds. Release: null.
                FaultSeams.valueOf(FaultSeam.RELAY_HEALTH_CONFIRM_SEC)?.takeIf { it > 0 }?.times(1_000L)
            }
        }
    private var setResolved = false
    private val tickMutex = Mutex()
    private val outboxMutex = Mutex()
    private val outbox = RelayOutbox()

    // The relays the RELAY_UNREACHABLE bit mask names: bit 0 slot 0's relay, then the catalog in order.
    private val seamRelays: List<String> = listOf(relayUrls.first()) + (health?.catalog ?: RelayCatalog.PUBLIC)

    private fun nowMs(): Long = health?.nowMs?.invoke() ?: System.currentTimeMillis()

    // subId -> (filter, onEvent) so we can re-subscribe after a reconnect.
    private val subscriptions: MutableMap<String, Pair<Map<String, Any>, (String) -> Unit>> =
        ConcurrentHashMap()
    // Cross-relay duplicate suppression for incoming events — the same kind-1059 wrap
    // can arrive on multiple relays; we deliver it to the consumer exactly once.
    private val seenEventIds: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

    // Liveness signals for the foreground reconnect kick (reconnectIfStale). lastEventAtMs proves a
    // subscription is actually DELIVERING (a relay can stay "connected" yet go silently quiet — no
    // CLOSED frame — so connected==true is not proof of liveness). lastReconnectAtMs throttles the kick.
    @Volatile private var lastEventAtMs: Long = 0L
    @Volatile private var lastReconnectAtMs: Long = 0L

    fun start() {
        resolveRelaySet()
        relays.forEach { state ->
            wireClient(state)
            launchConnect(state)
        }
    }

    /** RELAY-HEALTH: on the first start, the saved set (checked against the catalog) replaces the defaults. */
    @Synchronized
    private fun resolveRelaySet() {
        val p = policy ?: return
        if (setResolved) return
        setResolved = true
        healthPool = WeakReference(this)
        val stored = runCatching { relaySet?.load() }.getOrNull()
        val active = p.restore(stored, nowMs())
        if (active != relays.map { it.url }) {
            relays = active.mapIndexed { i, url -> RelayState(url, RelayClient(url), index = i) }
        }
        Log.i(TAG, "RelayHealth: relays $active")
    }

    /** RELAY-HEALTH: the three relays in use now (slot order). */
    fun activeUrls(): List<String> = relays.map { it.url }

    /** RELAY-HEALTH: the relays whose `since` bound the seen list (the active ones, plus nos.lol while it is out). */
    fun floorSetUrls(): List<String> = policy?.floorSetUrls(nowMs()) ?: activeUrls()

    /** RELAY-HEALTH: [url]'s health state, or null without relay health. For logs and tests. */
    fun healthState(url: String): RelayHealthState? = policy?.state(url, nowMs())

    /** RELAY-HEALTH: how long after start the first health check runs (null without relay health). */
    fun firstHealthCheckDelayMs(): Long? = health?.firstCheckDelayMs

    /**
     * Wire a relay's CLOSED handler so a torn-down subscription self-heals. An unhandled CLOSED
     * (rate-limit / transient auth) used to kill inbound delivery permanently and silently — the
     * relay stayed "connected" (publish kept working) while our REQ was dead, so the peer's
     * messages (e.g. a WebRTC ANSWER) never arrived. Re-issuing our subs recovers inbound.
     *
     * ZC-406: a CLOSED inside the re-subscribe window (the last re-subscribe on that relay is younger than it) used to
     * be dropped, and RelayClient forgets a CLOSED subscription, so a relay that closed our read twice in a row
     * (rate-limited:) carried no read of ours until its socket was rebuilt. Now it schedules one deferred re-subscribe
     * at the window's end ([planResubscribe]).
     */
    private fun wireClient(state: RelayState) {
        // KI-24 part B: every REQ to this relay carries its `since` line, and each end of stored events may move it.
        state.client.sinceProvider = sinceStore?.let { store -> { store.sinceFor(state.url) } }
        state.client.onEose = { _ ->
            // Debug seams: events this relay sent were dropped unseen, so its stored events were not all handled.
            val seamDropped = FaultSeams.isArmed(FaultSeam.BLOCK_RELAYS) || isSeamBlocked(state)
            if (!seamDropped && !state.retired) {
                liftOwnReadBlock(state)
                state.resubWindowMs = 0L
                sinceStore?.onEose(state.url, System.currentTimeMillis() / 1000)
                policy?.onEose(state.url, nowMs())
                // RELAY-HEALTH: a relay that is back gets the wraps it missed right away, not at the next tick.
                if (policy != null && !state.eosedOnSocket) {
                    state.eosedOnSocket = true
                    scope.launch { flushOutbox() }
                }
            }
        }
        state.client.onReqSent = { if (!state.retired) policy?.onReqSent(state.url, nowMs()) }
        state.client.onOk = { eventId, ok, reason -> onRelayOk(state, eventId, ok, reason) }
        state.client.onClosedReason = { reason ->
            // T1b-2 / RELAY-HEALTH: a relay that wants AUTH (NIP-42, which we do not do), or answers restricted: or
            // blocked:, will not serve our reads: a re-subscribe only repeats the refusal and its acks are no delivery.
            // ZC-460: this is the only writer of readBlocked, by the rule the health policy uses, so the two agree.
            if (RelayHealthPolicy.isReadBlockReason(reason) && !state.readBlocked) {
                state.readBlocked = true
                Log.w(TAG, "RelayHealth: ${state.url} refuses our reads (${reason.take(80)}): read-blocked")
            }
            if (!state.retired) policy?.onClosed(state.url, reason, nowMs())
        }
        state.client.onClosedSub = {
            scope.launch {
                if (state.readBlocked) {
                    // T1b-2: an auth-gated relay closes every REQ; re-subscribing only repeats the refusal. T5 fixup
                    // RH-X1: except ZChat's relay (slot 0, never swapped), which is asked again after a long pause, one
                    // retry at a time, so a refusal from it does not last for the process.
                    val retryMs = health?.ownReadRetryMs
                    if (state.index != 0 || retryMs == null) return@launch
                    if (!state.ownReadRetryPending.compareAndSet(false, true)) return@launch
                    try {
                        delay(retryMs)
                    } finally {
                        state.ownReadRetryPending.set(false)
                    }
                    if (!state.readBlocked || !state.connected || state.retired) return@launch
                    Log.i(TAG, "RelayHealth: asking ${state.url} for our messages again (read-blocked)")
                } else {
                    val plan = planResubscribe(state) ?: return@launch
                    if (plan.deferred) {
                        resubscribeDeferred(state, plan.waitMs)
                        return@launch
                    }
                    delay(plan.waitMs)
                    if (!state.connected) return@launch
                }
                resubscribeLost(state)
            }
        }
    }

    /**
     * ZC-425: asks the socket in use now for the subscriptions it does not hold: the ones the relay CLOSED on it
     * (RelayClient forgets those before it calls back). A read the relay still serves is not asked twice. Every path
     * calls this after its wait and its own checks, and none returns because the socket was replaced meanwhile
     * (ZC-501): a rebuilt socket replays every subscription on connect, so it lacks only what was closed on it.
     */
    private fun resubscribeLost(state: RelayState) {
        val client = state.client // the socket in use now: a rebuild may have replaced the one that got the CLOSED
        val all = subscriptions.values.toList()
        val lost = all.filter { (filter, _) -> !client.holds(filter) }
        lost.forEach { (filter, onEvent) ->
            runCatching { client.subscribe(filter, fromRelay(state, onEvent)) }
                .onFailure { Log.w(TAG, "re-subscribe ${state.url} failed: ${it.message}") }
        }
        Log.d(TAG, "re-subscribed ${lost.size} of ${all.size} sub(s) on ${state.url} after CLOSED")
    }

    /**
     * ZC-406: a CLOSED (not a read block) outside the window re-subscribes after the backoff, as before. Inside it,
     * one deferred re-subscribe waits for the window's end (a second CLOSED meanwhile adds none: null), and the window
     * doubles for the next one, up to the cap, until the relay answers a read again. The window and its cap are
     * [RelayHealthConfig.resubDebounceMs] / [RelayHealthConfig.resubMaxMs] (same defaults without health), measured on
     * [resubNowMs] (ZC-482: the monotonic clock its delay waits on, never the wall clock).
     */
    private fun planResubscribe(state: RelayState): ResubPlan? {
        val base = health?.resubDebounceMs ?: RESUB_DEBOUNCE_MS
        val cap = maxOf(base, health?.resubMaxMs ?: RESUB_MAX_MS)
        synchronized(state) {
            val now = resubNowMs()
            val window = state.resubWindowMs.takeIf { it > 0 } ?: base
            val since = state.lastResubAt?.let { (now - it).coerceAtLeast(0L) } ?: Long.MAX_VALUE
            if (since >= window) {
                state.lastResubAt = now
                return ResubPlan(health?.resubBackoffMs ?: RESUB_BACKOFF_MS, deferred = false)
            }
            if (!state.resubPending.compareAndSet(false, true)) return null
            state.resubWindowMs = (window * 2).coerceAtMost(cap)
            return ResubPlan(window - since, deferred = true)
        }
    }

    /**
     * ZC-406: the deferred re-subscribe. It runs only if the relay is still connected, not retired, not read-blocked
     * (those keep their own rules), on the socket in use when it is due (ZC-501): a rebuilt socket replays every
     * subscription on connect, so only what that socket lacks, a read the relay closed on it meanwhile included, is
     * asked again (a read the relay serves is not doubled); the socket the wait was planned for is never asked
     * ([resubscribeLost]).
     */
    private suspend fun resubscribeDeferred(
        state: RelayState,
        waitMs: Long,
    ) {
        try {
            delay(waitMs)
            if (!state.connected || state.retired || state.readBlocked) return
            state.lastResubAt = resubNowMs()
        } finally {
            state.resubPending.set(false)
        }
        resubscribeLost(state)
    }

    fun stop() {
        relays.forEach { state ->
            state.eosedOnSocket = false
            runCatching { state.client.close() }
            state.connectJob?.cancel()
            state.connectJob = null
            // The connect coroutine rethrows on cancellation WITHOUT resetting this, so clear it here —
            // otherwise publish() could target a relay marked connected over a just-closed socket.
            state.connected = false
            // Rebuild the client so a subsequent start() (rotate() does stop()→start()) connects on a FRESH
            // RelayClient. An OkHttp WebSocket can't be reopened after close(): reconnecting the SAME client
            // superficially fires the connected callback but its send path is dead, so every later publish()
            // silently fails (0 acks) on the rotating device. The error-recovery path in launchConnect already
            // rebuilds for exactly this reason; the stop()→start() path must too. close() above released the
            // old socket/dispatcher, so this just swaps in a usable client for the next connect.
            state.client = RelayClient(state.url)
        }
        // Drop the accumulated subscriptions so a subsequent start() doesn't replay STALE filters. The
        // caller (NostrInboxManager.startInternal) re-subscribes with the current pubkey set immediately
        // after start(); without this, every rotation's old #p filter would be replayed on reconnect —
        // re-subscribing to rotated-away keys forever (forward-privacy leak + wasted REQs).
        subscriptions.clear()
        // Cancel the in-flight children (per-relay connect loops + onClosedSub re-subscribe debounce jobs)
        // but DO NOT cancel the scope's own Job. Cancelling the SupervisorJob permanently deads the scope,
        // so a later start() — rotate() does stop()→start() to hot-swap the identity — silently no-ops every
        // scope.launch and the pool NEVER reconnects (0-relay sends + dead inbox on the rotating device until
        // the process restarts). cancelChildren() stops the running coroutines yet leaves the scope reusable.
        scope.coroutineContext.cancelChildren()
    }

    /**
     * Foreground liveness kick. A relay can go SILENTLY quiet — stop delivering events with no CLOSED
     * frame and the socket still "open" — so the per-connection onDisconnected reconnect never fires and
     * inbound DMs/reactions stall until a process restart (especially after background/Doze suspends the
     * keep-alive ping). This rebuilds every relay socket and replays the CURRENT subscriptions (kept, unlike
     * stop()), the in-process equivalent of that restart. Call it on app-foreground.
     *
     * Guarded so it never churns a healthy, actively-delivering connection:
     *  - skipped if we reconnected within the last [FOREGROUND_RECONNECT_THROTTLE_MS] (anti-flap), and
     *  - skipped if any relay delivered an event within the last [LIVENESS_FRESH_MS] (provably live).
     * Re-subscribing refetches stored gift-wraps, and seenEventIds de-dupes, so the brief gap loses nothing.
     */
    fun reconnectIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastReconnectAtMs < FOREGROUND_RECONNECT_THROTTLE_MS) return
        if (lastEventAtMs != 0L && now - lastEventAtMs < LIVENESS_FRESH_MS) return
        if (subscriptions.isEmpty()) return // nothing subscribed yet (inbox not started) — nothing to revive
        lastReconnectAtMs = now
        Log.d(TAG, "foreground liveness kick — reconnecting ${relays.size} relay(s) to revive any silently-quiet subscription")
        // Forget the in-memory cross-relay dedup so the reconnect's REPLAY re-delivers stored gift-wraps to
        // the consumer. Without this, an event that arrived while no ChatViewModel collector was alive (left
        // deliberately un-persisted-seen so it could redeliver) would be swallowed here and never re-reach the
        // chat layer intra-process. Already-handled events are still dropped downstream by the persistent
        // hasSeenNostrEvent check, so re-delivering the backlog only surfaces the genuinely-unhandled ones.
        synchronized(seenEventIds) { seenEventIds.clear() }
        // Marshal the teardown+rebuild onto the pool's OWN scope so a single coroutine context owns all
        // RelayState mutation — never racing the (Dispatchers.Default) connect loops we're replacing. Cancel
        // AND JOIN each old loop before swapping clients: a still-terminating loop's error-recovery path would
        // otherwise reassign state.client out from under us and orphan a socket. We KEEP `subscriptions`
        // (unlike stop(), which clears them), so launchConnect replays the same filters and inbound resumes.
        scope.launch {
            relays.forEach { it.connectJob?.cancel() }
            relays.forEach { runCatching { it.connectJob?.join() } }
            relays.forEach { state ->
                runCatching { state.client.close() }
                state.connected = false
                state.eosedOnSocket = false
                // OkHttp WebSockets can't be reopened — swap in a fresh client, then relaunch the connect loop.
                state.client = RelayClient(state.url)
                wireClient(state)
                launchConnect(state)
            }
        }
    }

    /**
     * Lightweight periodic liveness refresh — the cheap counterpart to [reconnectIfStale] for a timer that
     * runs on EVERY tick (see NostrInboxManager's watchdog). Re-opens the current subscriptions on each
     * ALREADY-CONNECTED relay under NEW subIds and closes the old ones (nothing accumulates), reviving a
     * subscription a relay silently dropped while the socket stayed open. T1b-2: it used to re-send the REQ under
     * the same subId, which our own relay ignores, so it never revived anything there. Unlike [reconnectIfStale] it
     * does NOT tear down/rebuild sockets and does NOT clear [seenEventIds]: the relays replay what they hold on the
     * fresh subscription, and every event already delivered is dropped by [seenEventIds] before any decrypt, so an
     * idle foreground app pays a few REQ frames and one replay's download per tick, not fresh TLS handshakes and a
     * backlog re-decrypt. A genuinely DEAD socket is already rebuilt by the per-connection ping/onDisconnected loop;
     * this only needs the live ones. Guarded like the reconnect: skipped if a relay delivered an event within
     * [LIVENESS_FRESH_MS] (provably live — nothing to revive). Missed gift-wraps (never delivered → never in
     * seenEventIds) surface on the relay's replay; already-handled ones stay de-duped, so no message is doubled.
     */
    fun resubscribeLive() {
        if (subscriptions.isEmpty()) return
        val now = System.currentTimeMillis()
        if (lastEventAtMs != 0L && now - lastEventAtMs < LIVENESS_FRESH_MS) return
        // Marshal onto the pool's own scope so RelayState is read on the same context that mutates it
        // (the connect loops), never racing a client swap from a cross-thread caller.
        scope.launch {
            val live = relays.filter { it.connected }
            if (live.isEmpty()) return@launch // all down → launchConnect is already rebuilding them
            live.forEach { state ->
                runCatching { state.client.resubscribeFresh() }
                    .onFailure { Log.w(TAG, "resubscribeLive ${state.url} failed: ${it.message}") }
            }
            Log.d(TAG, "liveness watchdog re-issued ${subscriptions.size} sub(s) on ${live.size} live relay(s)")
        }
    }

    /**
     * One-shot backlog redelivery. Clears the cross-relay dedup ([seenEventIds]) and re-opens the current
     * subscriptions under NEW subIds on the live relays (closing the old ones) so the relays' stored gift-wraps
     * REPLAY to the consumer. T1b-2: a REQ repeated under the live subId, as before, got nothing back from our own
     * relay. Unlike [resubscribeLive]
     * it is deliberately NOT freshness-gated: the caller (NostrInboxManager's watchdog, via
     * NostrChatBridge.consumePendingRedelivery) invokes it ONLY when an event was received while no chat
     * collector was alive AND a collector is alive now — so we specifically WANT the replay even if a recent
     * event made the connection look fresh. Cost: one full re-decrypt of the stored backlog for our pubkey;
     * already-handled events are still dropped downstream by the persistent hasSeenNostrEvent check, so only the
     * genuinely-missed event surfaces. Bounded to at most once per missed-event episode by the bridge's
     * consume-once flag. This is the same dedup-clear reconnectIfStale already does, minus the socket rebuild.
     */
    fun redeliverBacklog() {
        if (subscriptions.isEmpty()) return
        synchronized(seenEventIds) { seenEventIds.clear() }
        scope.launch {
            val live = relays.filter { it.connected }
            if (live.isEmpty()) return@launch // all down → launchConnect is already rebuilding + will replay
            live.forEach { state ->
                runCatching { state.client.resubscribeFresh() }
                    .onFailure { Log.w(TAG, "redeliverBacklog ${state.url} failed: ${it.message}") }
            }
            Log.d(TAG, "backlog redelivery — cleared dedup + re-issued ${subscriptions.size} sub(s) on ${live.size} live relay(s)")
        }
    }

    /**
     * Subscribe across every relay. The provided [onEvent] receives the event JSON exactly
     * once even if multiple relays surface the same event. Returns a synthetic subscription
     * id usable with [unsubscribe].
     */
    fun subscribe(filter: Map<String, Any>, onEvent: (String) -> Unit): String {
        val poolId = "p" + System.nanoTime().toString(36)
        val wrapped: (String) -> Unit = wrapped@{ json ->
            // F0 debug seam (KI-23): relays unreachable, so nothing is delivered (and nothing is marked seen, so a
            // backlog redelivery after the seam is cleared still delivers it). Never armed in a release build.
            if (FaultSeams.isArmed(FaultSeam.BLOCK_RELAYS)) return@wrapped
            val id = parseEventId(json)
            // Deliver an event at most once across all relays. A null id means the event JSON was
            // malformed — it can't be deduped and won't parse downstream, so DROP it rather than the
            // old `id == null || …` which let every malformed (or maliciously id-less) wrap through on
            // every replay. Only a brand-new id passes.
            // ZC-1414: the id is read from unverified JSON, so it is marked seen only once the event's id hash and
            // signature verify. A copy that does not verify is deduped by its exact bytes instead: a hostile relay's
            // junk copy carrying a real wrap's id can no longer suppress the genuine copy from another relay.
            val fresh = id != null && id !in seenEventIds && seenEventIds.add(dedupKey(id, json))
            if (fresh) {
                // A freshly-delivered event proves the subscription is live right now.
                lastEventAtMs = System.currentTimeMillis()
                // Cap the seen set so we don't leak memory in long sessions. The eviction iterates the
                // set, and a Collections.synchronizedSet iterator is NOT safe against concurrent add()s
                // from other relays' callbacks — hold the set's monitor for the whole iterate+remove.
                if (seenEventIds.size > SEEN_CAP) {
                    synchronized(seenEventIds) {
                        val iter = seenEventIds.iterator()
                        repeat(SEEN_CAP / 4) { if (iter.hasNext()) { iter.next(); iter.remove() } }
                    }
                }
                onEvent(json)
            }
        }
        subscriptions[poolId] = filter to wrapped
        relays.forEach { state ->
            if (state.connected) {
                runCatching { state.client.subscribe(filter, fromRelay(state, wrapped)) }
                    .onFailure { Log.w(TAG, "subscribe ${state.url} failed: ${it.message}") }
            }
        }
        return poolId
    }

    /**
     * T1b-2 debug seam: while BLOCK_ONE_RELAY names [state]'s position, events from that relay are dropped (not marked
     * seen, so they still arrive once it is cleared). Never armed in a release build.
     */
    private fun fromRelay(
        state: RelayState,
        onEvent: (String) -> Unit,
    ): (String) -> Unit =
        { json ->
            if (!isSeamBlocked(state) && !state.retired) {
                liftOwnReadBlock(state)
                state.resubWindowMs = 0L
                policy?.onEvent(state.url, nowMs())
                onEvent(json)
            }
        }

    /**
     * T5 fixup RH-X1: ZChat's relay (slot 0, never swapped) answered a read, so a read block it had is over: its acks
     * count again and the outbox offers it our wraps again. Public relays keep theirs for the process (they are swapped).
     */
    private fun liftOwnReadBlock(state: RelayState) {
        if (state.index != 0 || !state.readBlocked || policy == null) return
        state.readBlocked = false
        Log.i(TAG, "RelayHealth: ${state.url} serves our reads again: no longer read-blocked")
    }

    private fun isSeamBlocked(state: RelayState): Boolean =
        FaultSeams.valueOf(FaultSeam.BLOCK_ONE_RELAY)?.let { it == state.index.toLong() } == true || isUnreachable(state.url)

    /**
     * RELAY-HEALTH debug seam RELAY_UNREACHABLE: a bit mask over [seamRelays] (bit 0 slot 0's relay, then the catalog
     * in order). It follows the relay, not the slot. Never armed in a release build.
     */
    private fun isUnreachable(url: String): Boolean {
        val mask = FaultSeams.valueOf(FaultSeam.RELAY_UNREACHABLE) ?: return false
        val bit = seamRelays.indexOf(url)
        return bit in 0..62 && (mask shr bit) and 1L == 1L
    }

    fun unsubscribe(poolId: String) {
        subscriptions.remove(poolId)
        // Relay sub ids are per-client; cheapest path is to leave them in place — the
        // wrapped callback no longer exists in our map so events become no-ops. The
        // server-side filter is dropped at relay disconnect.
    }

    /**
     * ZC-393: the answers to one publish. [readable]: relays that took it and serve us (any of the active relays);
     * [shared]: those of them every ZChat version reads ([sharedReadRelays]: ZChat's relay, and nos.lol in whichever
     * slot it sits). A pool without relay health counts both the same.
     */
    data class PublishCount(
        val readable: Int,
        val shared: Int,
    )

    // ZC-393: the relays of the configured list every ZChat version reads (2.12.4 reads only these two, G2).
    private val sharedRelays: Set<String> = sharedReadRelays(relayUrls)

    /**
     * Publish [eventJson] to every connected relay. Returns the count of OK acks from relays that serve us (readable),
     * whichever relay gave them; [publishCounted] also says how many of them every ZChat version reads.
     */
    suspend fun publish(eventJson: String): Int = publishCounted(eventJson).readable

    /**
     * Publish [eventJson] to every connected relay (as [publish]); with relay health the wrap is kept in the outbox under
     * [tag] (ZC-393: "<recipient>:<rumorId>", so a Retry can find it, [republishKept]).
     */
    suspend fun publishCounted(
        eventJson: String,
        tag: String? = null,
    ): PublishCount {
        // Publish to every connected relay CONCURRENTLY. The old sequential forEach awaited each
        // relay's OK in turn, so one slow/non-acking relay blocked publishing to the others —
        // a single wedged relay could strand a whole call signal (e.g. the ANSWER) on no relay.
        // F0 debug seam (KI-23): relays unreachable, so no relay acks. Never armed in a release build.
        if (FaultSeams.isArmed(FaultSeam.BLOCK_RELAYS)) return PublishCount(0, 0)
        // T1b-2 debug seam: the relay BLOCK_ONE_RELAY names is down.
        val targets = relays.filter { it.connected && !isSeamBlocked(it) }
        if (targets.isEmpty()) return PublishCount(0, 0)
        // RELAY-HEALTH: kept before it goes out, so no OK is missed; dropped again below if no relay acknowledged it. A
        // send the caller cancels stays "in flight" (never re-sent) until it ages out.
        val kept = policy != null && outbox.add(eventJson, nowMs(), health!!, tag)
        val results = coroutineScope {
            targets.map { state ->
                async {
                    runCatching { state.client.publish(eventJson) }
                        .onFailure { Log.w(TAG, "publish ${state.url} failed: ${it.message}") }
                        .getOrDefault(false)
                }
            }.awaitAll()
        }
        // T1b-2: an OK from a relay that won't serve us (read-blocked: auth-required) is not a copy anyone can read,
        // so it is not counted. In the T1 run "2 relay ack" with nos.lol down meant one readable copy (on
        // relay.zsend.xyz); relay.damus.io acknowledged every memo and served none. Callers treat 0 acks as not
        // delivered.
        val stored = results.count { it }
        val acks = targets.indices.count { i -> results[i] && !targets[i].readBlocked }
        // ZC-393: a send is delivered for every ZChat version only when ZChat's relay or nos.lol holds it. Without relay
        // health the count is the readable one, as before.
        val shared =
            if (policy == null) {
                acks
            } else {
                targets.indices.count { i -> results[i] && !targets[i].readBlocked && targets[i].url in sharedRelays }
            }
        Log.d(TAG, "published to $stored/${targets.size} connected relay(s), $acks readable, $shared shared")
        // RELAY-HEALTH: a send no relay took is FAILED for the caller (its Retry makes a new wrap): never re-sent here. A
        // send only a public relay took (ZC-393: readable, not shared) is kept and repaired, and its Retry re-sends it.
        if (kept) outbox.published(eventJson, acks)
        return PublishCount(acks, shared)
    }

    /**
     * ZC-393: a Retry of a message only a public relay took. The wrap the outbox keeps under [tag] goes again, the same
     * json (same event id), to each connected relay that does not hold it yet (not seam-blocked, not read-blocked; unlike
     * the outbox flush, also to a relay the health rules call unavailable, since the user asked now); then the count
     * comes from the relays that hold it (an OK true or "duplicate:" from a relay that serves us): [PublishCount.readable]
     * the active ones, [PublishCount.shared] ZChat's relay or nos.lol. When the outbox already put it on every active
     * relay, its completed record answers and nothing is sent. Null when nothing is kept under [tag]: never kept (no
     * relay health, an expiration-tagged call signal), dropped (no relay took it), pruned at 24 h, pushed out by the
     * outbox limits, or a restart; the caller then makes a new wrap.
     */
    suspend fun republishKept(tag: String): PublishCount? {
        val config = health ?: return null
        outbox.prune(nowMs(), config.outboxKeepMs)
        val entry = outbox.findByTag(tag)
        if (entry == null) {
            val held = outbox.completedFor(tag) ?: return null
            val count = countHeld(held)
            Log.i(TAG, "RelayHealth: Retry of a delivered wrap, nothing re-sent (${count.readable} readable, ${count.shared} shared)")
            return count
        }
        val targets =
            if (FaultSeams.isArmed(FaultSeam.BLOCK_RELAYS)) {
                emptyList()
            } else {
                relays.filter {
                    it.connected && !it.retired && !it.readBlocked && !isSeamBlocked(it) && it.url !in entry.acked
                }
            }
        if (targets.isNotEmpty()) {
            val results =
                coroutineScope {
                    targets
                        .map { state ->
                            async {
                                runCatching { state.client.publish(entry.json) }
                                    .onFailure { Log.w(TAG, "re-publish ${state.url} failed: ${it.message}") }
                                    .getOrDefault(false)
                            }
                        }.awaitAll()
                }
            // onRelayOk records each answer as it comes; an OK true counted here as well, in case the relay's callback
            // went to a publish of the same id that was waiting at the same time (the outbox flush). ZC-588: also on the
            // entry held here, since outbox.acked does nothing once a newer wrap pushed this one out while it waited.
            targets.indices
                .filter { results[it] && !targets[it].readBlocked }
                .forEach {
                    entry.acked.add(targets[it].url)
                    outbox.acked(entry.id, targets[it].url)
                }
        }
        val count = countHeld(entry.acked)
        Log.i(
            TAG,
            "RelayHealth: Retry re-sent kept ${entry.id.take(8)} to ${targets.map { it.url }} " +
                "(${count.readable} readable, ${count.shared} shared)",
        )
        return count
    }

    /**
     * ZC-588: the relays of [held] that serve us now. [PublishCount.readable]: the active relays whose read block is not
     * set; [PublishCount.shared]: ZChat's relay or nos.lol when they pass, a relay no longer active (nos.lol swapped out)
     * unless the health rules call it READ_BLOCKED. A copy on a relay that closed our read is no copy anyone can read.
     */
    private fun countHeld(held: Set<String>): PublishCount {
        val active = relays
        val now = nowMs()
        val serves = { url: String ->
            val state = active.firstOrNull { it.url == url }
            if (state != null) !state.readBlocked else policy?.state(url, now) != RelayHealthState.READ_BLOCKED
        }
        return PublishCount(
            readable = active.count { it.url in held && !it.readBlocked },
            shared = sharedRelays.count { it in held && serves(it) },
        )
    }

    /** RELAY-HEALTH: the answer of [state] to one of our publishes. */
    private fun onRelayOk(
        state: RelayState,
        eventId: String,
        ok: Boolean,
        reason: String?,
    ) {
        if (state.retired) return
        policy?.onOk(state.url, ok, reason, nowMs())
        val holds = ok || reason?.trim()?.lowercase()?.startsWith("duplicate:") == true
        if (holds && !state.readBlocked) outbox.acked(eventId, state.url)
    }

    /**
     * RELAY-HEALTH: one health check (NostrInboxManager's watchdog tick, and once [firstHealthCheckDelayMs] after
     * start): the policy decides, a candidate is probed before it is used, a swap is made and saved, and the outbox
     * re-sends what the active relays still lack. Returns the job (tests wait for it), or null without relay health.
     */
    fun healthTick(): Job? {
        val p = policy ?: return null
        return scope.launch {
            if (!tickMutex.tryLock()) return@launch
            try {
                closeUnreachable()
                val now = nowMs()
                when (val d = p.tick(now)) {
                    RelayHealthDecision.Offline -> Log.i(TAG, "RelayHealth: offline, no swap")
                    is RelayHealthDecision.Hold ->
                        if (d.reason != HEALTHY) Log.i(TAG, "RelayHealth: no swap (${d.reason}) ${healthSummary(now)}")
                    is RelayHealthDecision.Replace -> {
                        val why = "${p.state(d.old, now)}, score ${p.score(d.old, now)}"
                        val ok = probe(d.candidate)
                        Log.i(TAG, "RelayHealth: probe ${d.candidate} ${if (ok) "ok" else "failed"}")
                        if (p.onProbeResult(d.candidate, ok, nowMs())) swap(d.slot, d.candidate, seed = true, why = why)
                    }
                    is RelayHealthDecision.ProbeCompat -> {
                        val compat = relayUrls[2]
                        val ok = probe(compat)
                        Log.i(TAG, "RelayHealth: probe $compat ${if (ok) "ok" else "failed"} (compat slot)")
                        if (p.onProbeResult(compat, ok, nowMs())) swap(2, compat, seed = false, why = "compat relay answers again")
                    }
                }
                flushOutbox()
            } finally {
                tickMutex.unlock()
            }
        }
    }

    private fun healthSummary(now: Long): String {
        val p = policy ?: return ""
        return relays.joinToString(prefix = "[", postfix = "]") { "${it.url} ${p.state(it.url, now)} ${p.score(it.url, now)}" }
    }

    /** A probe of [url]: a fresh socket, one REQ for a random key, an EOSE in time (RelayClient.probe). */
    private suspend fun probe(url: String): Boolean {
        if (isUnreachable(url)) return false
        return RelayClient.probe(url, health?.probeTimeoutMs ?: DEFAULT_PROBE_TIMEOUT_MS)
    }

    /** RELAY-HEALTH debug seam: an open socket to a relay RELAY_UNREACHABLE names is closed (and cannot reopen). */
    private suspend fun closeUnreachable() {
        relays.filter { it.connected && isUnreachable(it.url) }.forEach { state ->
            Log.i(TAG, "RelayHealth: ${state.url} unreachable (debug seam): socket closed")
            state.connectJob?.let {
                it.cancel()
                it.join()
            }
            runCatching { state.client.close() }
            state.connected = false
            state.eosedOnSocket = false
            policy?.onDisconnected(state.url, nowMs())
            state.client = RelayClient(state.url)
            wireClient(state)
            launchConnect(state)
        }
    }

    /**
     * RELAY-HEALTH: [newUrl] takes [slot]. Its `since` starts at the seen list's floor + 1 when [seed] (a returning
     * nos.lol keeps its own); the old relay is closed for good at once, so only three relays ever read our key.
     */
    private suspend fun swap(
        slot: Int,
        newUrl: String,
        seed: Boolean,
        why: String,
    ) {
        val p = policy ?: return
        if (seed) {
            val floor = runCatching { health?.seenFloorSec?.invoke() ?: 0L }.getOrDefault(0L)
            if (floor > 0) sinceStore?.seed(newUrl, floor + 1)
        }
        val old = replaceSlot(slot, newUrl) ?: return
        val saved = p.commitSwap(slot, newUrl, nowMs())
        // FD-5: the relay set is written only here, on a swap (never on a tick or a probe).
        runCatching { relaySet?.save(saved) }.onFailure { Log.w(TAG, "RelayHealth: the relay set was not saved: ${it.message}") }
        Log.i(TAG, "RelayHealth: swap slot $slot ${old.url} → $newUrl ($why)")
    }

    /** Closes the relay in [slot] for good and starts [newUrl] there. Returns the old state. */
    private suspend fun replaceSlot(
        slot: Int,
        newUrl: String,
    ): RelayState? {
        val old = relays.getOrNull(slot) ?: return null
        old.retired = true
        old.connectJob?.let {
            it.cancel()
            it.join()
        }
        runCatching { old.client.close() }
        old.connected = false
        val fresh = RelayState(newUrl, RelayClient(newUrl), index = slot)
        relays = relays.toMutableList().also { it[slot] = fresh }
        wireClient(fresh)
        launchConnect(fresh)
        return old
    }

    /**
     * RELAY-HEALTH, debug builds only (the RESET_RELAY_SET action): the default relays back, cooldowns and swap history
     * cleared, the saved set removed. Phone checks run it between runs.
     */
    fun resetRelaySetForDebug(): Job? {
        if (!BuildConfig.DEBUG) return null
        val p = policy ?: return null
        return scope.launch {
            tickMutex.lock()
            try {
                val defaults = p.reset()
                runCatching { relaySet?.save(null) }
                defaults.forEachIndexed { slot, url -> if (relays.getOrNull(slot)?.url != url) replaceSlot(slot, url) }
                Log.i(TAG, "RelayHealth: reset to the defaults $defaults")
            } finally {
                tickMutex.unlock()
            }
        }
    }

    /**
     * RELAY-HEALTH outbox (W-15: our sent messages are re-sent for up to 24 h until three relays hold them): each kept
     * wrap that an active, working relay has not acknowledged goes there again, the same json (same event id), at most
     * [RelayHealthConfig.outboxPerTick] wraps per run, 1–3 s apart. A relay that has it answers "duplicate:"; receivers
     * de-duplicate by event id and rumor id.
     */
    private suspend fun flushOutbox() {
        val p = policy ?: return
        val config = health ?: return
        if (!outboxMutex.tryLock()) return
        try {
            val now = nowMs()
            outbox.prune(now, config.outboxKeepMs)
            val active = relays
            val activeUrls = active.map { it.url }
            val working =
                active.filter {
                    it.connected && !it.readBlocked && !it.retired && !isSeamBlocked(it) && !p.isUnavailable(it.url, now)
                }
            var sent = 0
            for (entry in outbox.snapshot()) {
                if (sent >= config.outboxPerTick) break
                if (entry.inFlight) continue
                if (activeUrls.all { it in entry.acked }) {
                    // ZC-393: a tagged wrap leaves a completed record, so its Retry finds it delivered.
                    outbox.complete(entry.id, config)
                    continue
                }
                val targets = working.filter { it.url !in entry.acked }
                if (targets.isEmpty()) continue
                if (sent > 0) delay(outboxGap(config))
                coroutineScope {
                    targets.map { state -> async { runCatching { state.client.publish(entry.json) } } }.awaitAll()
                }
                Log.i(TAG, "RelayHealth: outbox re-published ${entry.id.take(8)} to ${targets.map { it.url }}")
                sent++
            }
        } finally {
            outboxMutex.unlock()
        }
    }

    private fun outboxGap(config: RelayHealthConfig): Long =
        if (config.outboxGapMaxMs > config.outboxGapMinMs) {
            Random.nextLong(config.outboxGapMinMs, config.outboxGapMaxMs + 1)
        } else {
            config.outboxGapMinMs
        }

    private fun launchConnect(state: RelayState) {
        // RELAY-HEALTH: a relay swapped out is never reconnected (a reconnect that raced the swap sees this).
        if (state.retired) return
        state.connectJob?.cancel()
        state.connectJob = scope.launch {
            var backoffMs = INITIAL_BACKOFF_MS
            while (isActive && !state.retired) {
                // Per-connection death signal. RelayClient completes it from onClosed/onFailure once the
                // socket dies AFTER a successful open — including OkHttp's 20s-ping detecting a dead/
                // half-open peer during idle/Doze. Without this we used to park on awaitCancellation()
                // forever and the inbox went silently dormant until a process restart (#238).
                val disconnected = kotlinx.coroutines.CompletableDeferred<Unit>()
                state.client.onDisconnected = { disconnected.complete(Unit) }
                try {
                    // RELAY-HEALTH debug seam: this relay cannot be reached.
                    if (isUnreachable(state.url)) throw java.io.IOException("unreachable (debug seam RELAY_UNREACHABLE)")
                    state.client.connect()
                    state.connected = true
                    state.eosedOnSocket = false
                    policy?.onConnectOk(state.url, nowMs())
                    Log.d(TAG, "connected to ${state.url}")
                    // Replay subscriptions ONCE per connection so re-subscribing on a
                    // healthy socket doesn't spam REQ frames every keep-alive tick.
                    subscriptions.values.forEach { (filter, onEvent) ->
                        runCatching { state.client.subscribe(filter, fromRelay(state, onEvent)) }
                            .onFailure { Log.w(TAG, "replay sub ${state.url}: ${it.message}") }
                    }
                    backoffMs = INITIAL_BACKOFF_MS
                    // Wait until the socket dies (onDisconnected) or the pool cancels us (stop()/rotate
                    // → CancellationException → exit cleanly). A silent socket death now WAKES us here
                    // instead of sleeping forever, so we drop through to rebuild + reconnect + replay.
                    disconnected.await()
                    Log.w(TAG, "${state.url}: socket dropped after open — reconnecting + re-subscribing")
                    state.connected = false
                    policy?.onDisconnected(state.url, nowMs())
                    // OkHttp WebSockets can't be reopened — discard + rebuild (frees the old dispatcher/
                    // pool). A dropped socket reconnects PROMPTLY (no exponential backoff — that's only
                    // for a relay we can't reach at all, handled in the catch below).
                    runCatching { state.client.close() }
                    state.client = RelayClient(state.url)
                    wireClient(state)
                    delay(RESUB_BACKOFF_MS)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${state.url}: ${e.message}; retry in ${backoffMs}ms")
                    state.connected = false
                    policy?.onConnectFail(state.url, nowMs())
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    // OkHttp WebSockets can't be reopened — discard and rebuild. Close the
                    // old client FIRST: it owns a private OkHttpClient whose dispatcher
                    // executor + connection pool would otherwise leak threads/sockets on
                    // every reconnect (one extra leaked pool per network blip, unbounded
                    // over a long-lived foreground service).
                    runCatching { state.client.close() }
                    state.client = RelayClient(state.url)
                    wireClient(state)
                }
            }
        }
    }

    /**
     * ZC-482: the clock of the re-subscribe window ([RelayHealthConfig.resubNowMs]; monotonic ms without health), the one
     * the coroutine delay that waits the window out runs on. The wall clock (System.currentTimeMillis) can be stepped by
     * NTP, NITZ or the user: a step forward asked a rate-limited relay again before the window's end, a step back held
     * the deferred re-subscribe (and with it every CLOSED of that relay) for as long as the step.
     */
    private fun resubNowMs(): Long = health?.resubNowMs?.invoke() ?: (System.nanoTime() / NANOS_PER_MS)

    /** ZC-1414: the verified id for a valid event; for one that fails NostrEvent.verify, a hash of its exact bytes. */
    private fun dedupKey(
        id: String,
        json: String,
    ): String {
        val verified = runCatching { NostrEvent.verify(json) }.getOrNull()
        if (verified?.id == id) return id
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val digest = sha256.digest(json.toByteArray(Charsets.UTF_8))
        return "unverified:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun parseEventId(json: String): String? {
        val needle = "\"id\":\""
        val s = json.indexOf(needle)
        if (s < 0) return null
        val start = s + needle.length
        val end = json.indexOf('"', start)
        if (end < 0) return null
        return json.substring(start, end)
    }

    companion object {
        private const val TAG = "NostrRelayPool"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val KEEP_ALIVE_MS = 60_000L
        private const val SEEN_CAP = 4096
        private const val RESUB_DEBOUNCE_MS = 15_000L
        private const val RESUB_MAX_MS = 300_000L
        private const val RESUB_BACKOFF_MS = 3_000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val DEFAULT_PROBE_TIMEOUT_MS = 10_000L
        private const val HEALTHY = "healthy"

        // reconnectIfStale (foreground kick) tuning.
        private const val FOREGROUND_RECONNECT_THROTTLE_MS = 20_000L
        private const val LIVENESS_FRESH_MS = 60_000L

        // Dedicated relay (relay.zsend.xyz) FIRST for reliable, low-latency real-time call
        // signalling. Public relays silently stop delivering live events under load (no CLOSED
        // frame — they just go quiet), which starves WebRTC ICE candidate exchange and makes calls
        // fail to connect. The dedicated relay we control does not rate-limit our own traffic.
        // Public relays are kept as redundancy/discoverability; cross-relay dupes are de-duped in
        // subscribe() via seenEventIds.
        // RELAY-HEALTH (2.13.0, W-15): relay.damus.io refuses every kind-1059 read with auth-required, so no ZChat
        // version reads it; relay.primal.net took its place. nos.lol stays in slot 2 (the compat slot: the only public
        // relay 2.12.4 reads). Slot 0 is the relay field of every KEX, ZBOOT, invite and request, and is never swapped.
        const val OWN_RELAY = "wss://relay.zsend.xyz"
        val DEFAULT_RELAYS = listOf(OWN_RELAY, RelayCatalog.PRIMAL, RelayCatalog.NOS_LOL)

        /**
         * ZC-393: the relays of a configured list ([relayUrls] order: ZChat's relay, slot 1, the compat relay) that every
         * ZChat version reads: slot 0 (ZChat's relay) and the compat relay of slot 2 (nos.lol), wherever it sits later.
         * A send only these count as delivered; 2.12.4 reads only them (Frozen2124ReadsOnlyOurRelayAndNosLolTest, G2).
         */
        fun sharedReadRelays(relayUrls: List<String>): Set<String> =
            listOfNotNull(relayUrls.firstOrNull(), relayUrls.getOrNull(2)).toSet()

        @Volatile
        private var healthPool: WeakReference<NostrRelayPool>? = null

        /** Debug builds only (the RESET_RELAY_SET action): the pool that runs relay health in this process. */
        fun healthPoolForDebug(): NostrRelayPool? {
            if (!BuildConfig.DEBUG) return null
            return healthPool?.get()
        }
    }
}
