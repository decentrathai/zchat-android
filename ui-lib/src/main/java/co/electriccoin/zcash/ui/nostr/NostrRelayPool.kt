package co.electriccoin.zcash.ui.nostr

import android.util.Log
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Tiny pool that keeps RelayClient connections alive, retries on failure with backoff,
 * and fans subscriptions across all configured relays. Designed for use inside a
 * foreground service — owns its own SupervisorJob so a single failing relay doesn't
 * cancel the others.
 *
 * Default relays are hardcoded; settings exposure is a v1.1 follow-up.
 */
class NostrRelayPool(
    relayUrls: List<String> = DEFAULT_RELAYS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private data class RelayState(
        val url: String,
        var client: RelayClient,
        var connectJob: Job? = null,
        var connected: Boolean = false,
        var lastResubAt: Long = 0L,
    )

    private val relays: List<RelayState> = relayUrls.map { url -> RelayState(url, RelayClient(url)) }
    // subId -> (filter, onEvent) so we can re-subscribe after a reconnect.
    private val subscriptions: MutableMap<String, Pair<Map<String, Any>, (String) -> Unit>> =
        ConcurrentHashMap()
    // Cross-relay duplicate suppression for incoming events — the same kind-1059 wrap
    // can arrive on multiple relays; we deliver it to the consumer exactly once.
    private val seenEventIds: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

    fun start() {
        relays.forEach { state ->
            wireClient(state)
            launchConnect(state)
        }
    }

    /**
     * Wire a relay's CLOSED handler so a torn-down subscription self-heals. An unhandled CLOSED
     * (rate-limit / transient auth) used to kill inbound delivery permanently and silently — the
     * relay stayed "connected" (publish kept working) while our REQ was dead, so the peer's
     * messages (e.g. a WebRTC ANSWER) never arrived. Re-issuing our subs recovers inbound.
     */
    private fun wireClient(state: RelayState) {
        state.client.onClosedSub = {
            scope.launch {
                val now = System.currentTimeMillis()
                if (now - state.lastResubAt < RESUB_DEBOUNCE_MS) return@launch
                state.lastResubAt = now
                delay(RESUB_BACKOFF_MS)
                if (!state.connected) return@launch
                subscriptions.values.forEach { (filter, onEvent) ->
                    runCatching { state.client.subscribe(filter, onEvent) }
                        .onFailure { Log.w(TAG, "re-subscribe ${state.url} failed: ${it.message}") }
                }
                Log.d(TAG, "re-subscribed ${subscriptions.size} sub(s) on ${state.url} after CLOSED")
            }
        }
    }

    fun stop() {
        relays.forEach { state ->
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
     * Subscribe across every relay. The provided [onEvent] receives the event JSON exactly
     * once even if multiple relays surface the same event. Returns a synthetic subscription
     * id usable with [unsubscribe].
     */
    fun subscribe(filter: Map<String, Any>, onEvent: (String) -> Unit): String {
        val poolId = "p" + System.nanoTime().toString(36)
        val wrapped: (String) -> Unit = { json ->
            val id = parseEventId(json)
            // Deliver an event at most once across all relays. A null id means the event JSON was
            // malformed — it can't be deduped and won't parse downstream, so DROP it rather than the
            // old `id == null || …` which let every malformed (or maliciously id-less) wrap through on
            // every replay. Only a brand-new id passes.
            val fresh = id != null && seenEventIds.add(id)
            if (fresh) {
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
                runCatching { state.client.subscribe(filter, wrapped) }
                    .onFailure { Log.w(TAG, "subscribe ${state.url} failed: ${it.message}") }
            }
        }
        return poolId
    }

    fun unsubscribe(poolId: String) {
        subscriptions.remove(poolId)
        // Relay sub ids are per-client; cheapest path is to leave them in place — the
        // wrapped callback no longer exists in our map so events become no-ops. The
        // server-side filter is dropped at relay disconnect.
    }

    /**
     * Publish [eventJson] to every connected relay. Returns the count of OK acks.
     */
    suspend fun publish(eventJson: String): Int {
        // Publish to every connected relay CONCURRENTLY. The old sequential forEach awaited each
        // relay's OK in turn, so one slow/non-acking relay blocked publishing to the others —
        // a single wedged relay could strand a whole call signal (e.g. the ANSWER) on no relay.
        val targets = relays.filter { it.connected }
        if (targets.isEmpty()) return 0
        val results = coroutineScope {
            targets.map { state ->
                async {
                    runCatching { state.client.publish(eventJson) }
                        .onFailure { Log.w(TAG, "publish ${state.url} failed: ${it.message}") }
                        .getOrDefault(false)
                }
            }.awaitAll()
        }
        val acks = results.count { it }
        Log.d(TAG, "published to $acks/${targets.size} connected relay(s)")
        return acks
    }

    private fun launchConnect(state: RelayState) {
        state.connectJob?.cancel()
        state.connectJob = scope.launch {
            var backoffMs = INITIAL_BACKOFF_MS
            while (isActive) {
                // Per-connection death signal. RelayClient completes it from onClosed/onFailure once the
                // socket dies AFTER a successful open — including OkHttp's 20s-ping detecting a dead/
                // half-open peer during idle/Doze. Without this we used to park on awaitCancellation()
                // forever and the inbox went silently dormant until a process restart (#238).
                val disconnected = kotlinx.coroutines.CompletableDeferred<Unit>()
                state.client.onDisconnected = { disconnected.complete(Unit) }
                try {
                    state.client.connect()
                    state.connected = true
                    Log.d(TAG, "connected to ${state.url}")
                    // Replay subscriptions ONCE per connection so re-subscribing on a
                    // healthy socket doesn't spam REQ frames every keep-alive tick.
                    subscriptions.values.forEach { (filter, onEvent) ->
                        runCatching { state.client.subscribe(filter, onEvent) }
                            .onFailure { Log.w(TAG, "replay sub ${state.url}: ${it.message}") }
                    }
                    backoffMs = INITIAL_BACKOFF_MS
                    // Wait until the socket dies (onDisconnected) or the pool cancels us (stop()/rotate
                    // → CancellationException → exit cleanly). A silent socket death now WAKES us here
                    // instead of sleeping forever, so we drop through to rebuild + reconnect + replay.
                    disconnected.await()
                    Log.w(TAG, "${state.url}: socket dropped after open — reconnecting + re-subscribing")
                    state.connected = false
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
        private const val RESUB_BACKOFF_MS = 3_000L

        // Dedicated relay (relay.zsend.xyz) FIRST for reliable, low-latency real-time call
        // signalling. Public relays silently stop delivering live events under load (no CLOSED
        // frame — they just go quiet), which starves WebRTC ICE candidate exchange and makes calls
        // fail to connect. The dedicated relay we control does not rate-limit our own traffic.
        // Public relays are kept as redundancy/discoverability; cross-relay dupes are de-duped in
        // subscribe() via seenEventIds.
        val DEFAULT_RELAYS = listOf("wss://relay.zsend.xyz", "wss://relay.damus.io", "wss://nos.lol")
    }
}
