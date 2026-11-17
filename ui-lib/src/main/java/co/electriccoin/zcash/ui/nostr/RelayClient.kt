package co.electriccoin.zcash.ui.nostr

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Single-relay NIP-01 WebSocket client. Owns one OkHttp WebSocket; surfaces inbound
 * EVENT frames as callbacks per-subscription. Designed to be wrapped by a multi-relay
 * pool but works standalone for tests.
 *
 * Wire protocol (NIP-01):
 *   client → relay:  ["REQ", subId, filter]      subscribe
 *   client → relay:  ["EVENT", event]            publish
 *   client → relay:  ["CLOSE", subId]            unsubscribe
 *   relay → client:  ["EVENT", subId, event]
 *   relay → client:  ["EOSE", subId]             end of stored events
 *   relay → client:  ["OK", eventId, ok, message] publish ack
 *   relay → client:  ["NOTICE", message]         info/warn
 */
class RelayClient(private val url: String) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // long-lived WS — no read timeout
        .build()
    private var webSocket: WebSocket? = null

    /** Set by the pool to self-heal: invoked with the dead subId when the relay sends CLOSED. */
    var onClosedSub: ((String) -> Unit)? = null

    /**
     * Set by the pool: invoked when the WebSocket dies AFTER a successful open (clean close, relay
     * restart, network change, or — the common silent case — OkHttp's ping timer detecting a dead/
     * half-open peer during idle/Doze). Previously onClosed/onFailure only failed in-flight publishes
     * and left the parked connect coroutine asleep forever, so the inbox went dormant and silently
     * missed live messages until a process restart (#238). Wiring this lets the pool reconnect + re-
     * subscribe (and the relay replays any stored gift-wraps).
     */
    var onDisconnected: (() -> Unit)? = null

    /**
     * KI-24: set by the pool when it keeps a `since` line for this relay (RelaySinceLine). Read each time a REQ frame
     * is built (subscribe, resubscribeAll, resubscribeFresh), so every re-send carries the value current at that
     * moment. Null, or a null answer: the REQ has no `since` (the relay replays all it holds), as before KI-24.
     */
    var sinceProvider: (() -> Long?)? = null

    /** KI-24: set by the pool; invoked with the subId when the relay ends a live subscription's stored events. */
    var onEose: ((String) -> Unit)? = null

    /** RELAY-HEALTH: set by the pool; invoked once for every REQ frame sent (so it can time the EOSE). */
    var onReqSent: (() -> Unit)? = null

    /**
     * RELAY-HEALTH: set by the pool; the answer to each of our publishes: `(eventId, ok, reason)`, with the relay's
     * NIP-01 message as [reason] ("" when it gave none), or `(eventId, false, null)` when no OK came in time.
     * [publish] keeps returning only the boolean.
     */
    var onOk: ((eventId: String, ok: Boolean, reason: String?) -> Unit)? = null

    /** RELAY-HEALTH: set by the pool; the reason of every CLOSED the relay sends (before [onClosedSub]). */
    var onClosedReason: ((String) -> Unit)? = null

    /** RELAY-HEALTH: invoked when the relay sends an AUTH challenge (NIP-42, which we do not do). */
    var onAuthChallenge: (() -> Unit)? = null

    // KI-24: the `since` each live subscription was asked with. A relay that ignores it (serves an older wrap) is
    // answered here: such a wrap is dropped before it reaches the pool, as the REQ asked.
    private val sinceBySub = ConcurrentHashMap<String, Long>()

    // subId -> (filter, onEvent). The filter is retained so [resubscribeAll] can re-issue the REQ under
    // the SAME subId (a same-id REQ REPLACES the relay-side subscription — it never accumulates a second one).
    private val subscriptions = ConcurrentHashMap<String, Pair<Map<String, Any>, (String) -> Unit>>()
    private val pendingPublishes = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val openSignal: CompletableDeferred<Unit> = CompletableDeferred()

    /** Open the WebSocket and suspend until the upgrade completes. */
    suspend fun connect() {
        if (webSocket != null) return
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openSignal.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                dispatchFrame(text)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Surface as a deferred-failure if any publishes are still in flight.
                pendingPublishes.values.forEach { it.complete(false) }
                pendingPublishes.clear()
                // Null the handle so a future connect() re-opens, and wake the pool to reconnect +
                // re-subscribe — onClosed only fires after a successful open (#238).
                this@RelayClient.webSocket = null
                onDisconnected?.invoke()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                pendingPublishes.values.forEach { it.completeExceptionally(t) }
                pendingPublishes.clear()
                if (!openSignal.isCompleted) {
                    // Initial connect failed → let connect() throw so launchConnect's catch backs off.
                    openSignal.completeExceptionally(t)
                } else {
                    // Socket died AFTER open (OkHttp ping timeout on a dead/half-open peer, network
                    // drop, Doze teardown). This is the silent-death case that used to leave the inbox
                    // dormant: null the handle + wake the pool to reconnect & replay subs (#238).
                    this@RelayClient.webSocket = null
                    onDisconnected?.invoke()
                }
            }
        }
        webSocket = httpClient.newWebSocket(request, listener)
        openSignal.await()
    }

    /**
     * Subscribe with [filter]. [onEvent] receives the raw JSON of each matching event.
     * Returns the subscription id, which can be passed to [unsubscribe].
     */
    fun subscribe(filter: Map<String, Any>, onEvent: (String) -> Unit): String {
        val subId = newSubId()
        // In the table before the REQ goes out, so an event that answers it at once finds its handler.
        subscriptions[subId] = filter to onEvent
        val frame = reqFrame(subId, filter)
        // ZC-761: a REQ that never left (no socket, or a closing one that refused it) leaves nothing behind, so
        // holds() says false and the pool's resubscribeLost asks again; it used to stay and was skipped.
        val sent = webSocket?.send(frame) == true
        if (!sent) {
            subscriptions.remove(subId)
            sinceBySub.remove(subId)
            error(if (webSocket == null) "connect() before subscribe()" else "the socket refused the REQ for $url")
        }
        onReqSent?.invoke()
        Log.d(TAG, "REQ -> $url sub=$subId filter=${filterToJson(filter)} since=${sinceBySub[subId]}")
        return subId
    }

    /** KI-24: the REQ frame for [subId], with the relay's current `since` (if any) added to [filter] and remembered. */
    private fun reqFrame(
        subId: String,
        filter: Map<String, Any>,
    ): String {
        val since = runCatching { sinceProvider?.invoke() }.getOrNull()?.takeIf { it > 0 }
        val sent =
            if (since != null) {
                sinceBySub[subId] = since
                filter + ("since" to since)
            } else {
                sinceBySub.remove(subId)
                filter
            }
        return "[\"REQ\",${jsonString(subId)},${filterToJson(sent)}]"
    }

    /**
     * Re-issue the REQ frame for every current subscription, REUSING each existing subId. A NIP-01 REQ
     * with an already-active subId REPLACES that subscription server-side (it does not open a second one),
     * so the relay re-scans the filter and re-streams matching stored events WITHOUT accumulating relay-side
     * subs or client-side handlers. This is the cheap liveness path for a subscription a relay silently
     * dropped while the socket stayed open (no CLOSED frame) — no socket teardown, unlike the pool's full
     * reconnect. No-op if the socket isn't open. The pool's cross-relay [seenEventIds] de-dupe means only
     * genuinely-missed events (never delivered, so never seen) surface; already-handled ones are dropped.
     */
    fun resubscribeAll() {
        val ws = webSocket ?: return
        subscriptions.forEach { (subId, sub) ->
            ws.send(reqFrame(subId, sub.first))
            onReqSent?.invoke()
        }
    }

    /**
     * T1b-2: re-open every current subscription under a NEW subId, then CLOSE the old one. Our own relay
     * (relay.zsend.xyz) ignores a REQ repeated under a live subId (in the T1 capture it answered its 13 fresh
     * connections and none of the repeats), so [resubscribeAll] never made it replay what it holds. A new subId is a
     * new subscription on every relay: the relay re-scans the filter and streams its stored events again. The new REQ
     * goes out before the old CLOSE, so there is no gap; an event both carry is delivered once by the pool's
     * cross-relay dedup. One live subscription per filter stays on the relay. No-op if the socket isn't open.
     */
    fun resubscribeFresh() {
        val ws = webSocket ?: return
        var refused = 0
        subscriptions.entries.toList().forEach { (oldId, sub) ->
            val newId = newSubId()
            subscriptions[newId] = sub
            // ZC-959 (the ZC-761 rule of subscribe): a fresh REQ the socket refused leaves nothing behind, and the read
            // whose REQ was sent stays held and is not closed; the table used to name only the read that never left.
            if (!ws.send(reqFrame(newId, sub.first))) {
                subscriptions.remove(newId)
                sinceBySub.remove(newId)
                refused++
                return@forEach
            }
            onReqSent?.invoke()
            subscriptions.remove(oldId)
            sinceBySub.remove(oldId)
            ws.send("[\"CLOSE\",${jsonString(oldId)}]")
            Log.d(TAG, "REQ -> $url sub=$newId (fresh, replaces $oldId)")
        }
        if (refused > 0) error("the socket refused $refused fresh REQ(s) for $url")
    }

    /** ZC-406: whether a live subscription of this client was made with this very [filter] (the same object). */
    fun holds(filter: Map<String, Any>): Boolean = subscriptions.values.any { it.first === filter }

    private fun newSubId(): String = "s" + Random.nextLong().toULong().toString(36)

    fun unsubscribe(subId: String) {
        subscriptions.remove(subId)
        sinceBySub.remove(subId)
        webSocket?.send("[\"CLOSE\",${jsonString(subId)}]")
    }

    /**
     * Publish a signed event. Suspends until the relay responds with OK or fails.
     * Returns the OK ack result.
     */
    suspend fun publish(eventJson: String): Boolean {
        val eventId = NostrEvent.parsePubkeyOrId(eventJson, "id")
        val ack = CompletableDeferred<Boolean>()
        pendingPublishes[eventId] = ack
        val frame = "[\"EVENT\",$eventJson]"
        webSocket?.send(frame) ?: error("connect() before publish()")
        // Bound the wait: a relay that silently drops the event (rate-limit, auth-gate) would
        // otherwise leave this await suspended forever and wedge the caller. Treat a missing OK
        // as a failed publish so the pool falls through to the other relays.
        val ok = kotlinx.coroutines.withTimeoutOrNull(PUBLISH_ACK_TIMEOUT_MS) { ack.await() }
        if (ok == null) {
            pendingPublishes.remove(eventId)
            Log.w(TAG, "publish -> $url: no OK within ${PUBLISH_ACK_TIMEOUT_MS}ms id=${eventId.take(8)}")
            runCatching { onOk?.invoke(eventId, false, null) }
            return false
        }
        return ok
    }

    fun close() {
        webSocket?.close(1000, "client closing")
        webSocket = null
        // Tear down OkHttp resources too — tests that rapidly recreate clients otherwise
        // see MockWebServer.shutdown hang for ~20s waiting for the websocket queue.
        runCatching { httpClient.dispatcher.executorService.shutdown() }
        runCatching { httpClient.connectionPool.evictAll() }
    }

    private fun dispatchFrame(frame: String) {
        // Reject oversized frames before any substring/parse/dispatch work (memory-exhaustion guard).
        if (frame.length > MAX_FRAME_SIZE) {
            Log.w(TAG, "dropping oversized frame from $url (${frame.length} chars > $MAX_FRAME_SIZE)")
            return
        }
        // Light parse: peek at the first 8 chars to dispatch.
        when {
            frame.startsWith("[\"EVENT\"") -> {
                // ["EVENT", subId, event]
                val subStart = frame.indexOf('"', "[\"EVENT\",".length) + 1
                val subEnd = frame.indexOf('"', subStart)
                if (subStart < 0 || subEnd < 0) return
                val subId = frame.substring(subStart, subEnd)
                val eventStart = frame.indexOf('{', subEnd)
                val eventEnd = frame.lastIndexOf('}')
                if (eventStart < 0 || eventEnd < 0) return
                val eventJson = frame.substring(eventStart, eventEnd + 1)
                // KI-24: a relay that ignores the REQ's `since` serves an older wrap; drop it as the REQ asked.
                val since = sinceBySub[subId]
                if (since != null) {
                    val createdAt = parseCreatedAt(eventJson)
                    if (createdAt != null && createdAt < since) {
                        Log.d(TAG, "EVENT in <- $url sub=$subId older than its since: dropped")
                        return
                    }
                }
                Log.d(TAG, "EVENT in <- $url sub=$subId")
                subscriptions[subId]?.second?.invoke(eventJson)
            }
            frame.startsWith("[\"OK\"") -> {
                // ["OK", eventId, ok, message]
                val idStart = frame.indexOf('"', "[\"OK\",".length) + 1
                val idEnd = frame.indexOf('"', idStart)
                if (idStart < 0 || idEnd < 0) return
                val eventId = frame.substring(idStart, idEnd)
                val okStart = frame.indexOf(',', idEnd) + 1
                val rest = frame.substring(okStart).trimStart()
                val ok = rest.startsWith("true")
                // RELAY-HEALTH: the OK's message ("blocked: …", "duplicate: …"), handed up with the answer.
                val reason = quoted(rest)
                val waiting = pendingPublishes.remove(eventId)
                if (waiting != null) runCatching { onOk?.invoke(eventId, ok, reason) }
                waiting?.complete(ok)
            }
            frame.startsWith("[\"CLOSED\"") -> {
                // ["CLOSED", subId, reason] — the relay tore down our subscription (rate-limit,
                // transient auth, too-many-concurrent-REQs). Previously UNHANDLED: the sub went
                // permanently silent with no log. Surface it + let the pool re-subscribe.
                val s = frame.indexOf('"', "[\"CLOSED\",".length) + 1
                val e = if (s > 0) frame.indexOf('"', s) else -1
                val subId = if (e > s) frame.substring(s, e) else "?"
                val reason = if (e >= 0) frame.substring(e + 1) else ""
                Log.w(TAG, "relay $url CLOSED sub=$subId: ${reason.take(140)}")
                // RELAY-HEALTH: the pool reads the reason before it re-subscribes. ZC-460: this is the one place it
                // learns of a read block (T1b-2's "auth-required", restricted:, blocked:); the rule is
                // RelayHealthPolicy.isReadBlockReason.
                runCatching { onClosedReason?.invoke(quoted(reason)) }
                // T2-extra S1: a CLOSED for an id we no longer hold answers our own CLOSE (resubscribeFresh, unsubscribe);
                // it is not a lost subscription, so the pool is not asked to re-subscribe.
                val wasLive = subscriptions.remove(subId) != null
                sinceBySub.remove(subId)
                if (wasLive) onClosedSub?.invoke(subId)
            }
            frame.startsWith("[\"AUTH\"") -> {
                Log.w(TAG, "relay $url requested AUTH (unsupported): ${frame.take(140)}")
                runCatching { onAuthChallenge?.invoke() }
            }
            frame.startsWith("[\"NOTICE\"") ->
                Log.w(TAG, "relay $url NOTICE: ${frame.take(160)}")
            frame.startsWith("[\"EOSE\"") -> {
                val s = frame.indexOf('"', "[\"EOSE\",".length) + 1
                val e = if (s > 0) frame.indexOf('"', s) else -1
                val subId = if (e > s) frame.substring(s, e) else ""
                Log.d(TAG, "relay $url EOSE sub=$subId")
                // KI-24: only a live subscription's end of stored events moves the relay's `since` line.
                if (subscriptions.containsKey(subId)) onEose?.invoke(subId)
            }
        }
    }

    /** The text between the first and the last double quote of [s] ("" when there is none). */
    private fun quoted(s: String): String {
        val first = s.indexOf('"')
        val last = s.lastIndexOf('"')
        return if (first >= 0 && last > first) s.substring(first + 1, last) else ""
    }

    /** KI-24: the event's top-level created_at, or null when it cannot be read (then nothing is dropped for it). */
    private fun parseCreatedAt(eventJson: String): Long? {
        val key = eventJson.indexOf("\"created_at\"")
        if (key < 0) return null
        var i = eventJson.indexOf(':', key)
        if (i < 0) return null
        i++
        while (i < eventJson.length && eventJson[i].isWhitespace()) i++
        val start = i
        while (i < eventJson.length && eventJson[i].isDigit()) i++
        return if (i > start) eventJson.substring(start, i).toLongOrNull() else null
    }

    private fun filterToJson(filter: Map<String, Any>): String =
        filter.entries.joinToString(",", prefix = "{", postfix = "}") { (k, v) ->
            "${jsonString(k)}:${valueToJson(v)}"
        }

    @Suppress("UNCHECKED_CAST")
    private fun valueToJson(v: Any): String = when (v) {
        is Number -> v.toString()
        is Boolean -> v.toString()
        is String -> jsonString(v)
        is List<*> -> (v as List<Any>).joinToString(",", "[", "]") { valueToJson(it) }
        is Map<*, *> -> filterToJson(v as Map<String, Any>)
        else -> jsonString(v.toString())
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    companion object {
        private const val TAG = "ZCHAT_RELAY"
        private const val PUBLISH_ACK_TIMEOUT_MS = 8_000L

        /**
         * RELAY-HEALTH: can [url] serve a kind-1059 read without AUTH right now? A fresh socket asks
         * `{"kinds":[1059],"#p":[<32 random bytes>],"limit":1}` (a key nobody owns: nothing of ours is sent) and needs an
         * EOSE within [timeoutMs], with no AUTH challenge and no CLOSED; then it closes. Never publishes anything.
         */
        suspend fun probe(
            url: String,
            timeoutMs: Long,
        ): Boolean {
            val client = RelayClient(url)
            return try {
                withTimeoutOrNull(timeoutMs) {
                    val answer = CompletableDeferred<Boolean>()
                    client.onEose = { answer.complete(true) }
                    client.onClosedReason = { answer.complete(false) }
                    client.onAuthChallenge = { answer.complete(false) }
                    client.connect()
                    val randomKey = Random.nextBytes(32).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    val subId =
                        client.subscribe(mapOf("kinds" to listOf(1059), "#p" to listOf(randomKey), "limit" to 1)) { }
                    val ok = answer.await()
                    runCatching { client.unsubscribe(subId) }
                    ok
                } ?: false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "probe $url failed: ${e.message}")
                false
            } finally {
                runCatching { client.close() }
            }
        }

        // Reject absurdly large relay frames before any substring/parse work — a relay (or MITM) could
        // otherwise stream a multi-MB "event" to exhaust memory. Real NIP-17 gift-wraps are a few KB;
        // 256KB is generous headroom for events with many tags.
        private const val MAX_FRAME_SIZE = 256 * 1024
    }
}

/** Convenience wrapper around [NostrEvent.parseStringField] without forcing visibility changes. */
internal fun NostrEvent.parsePubkeyOrId(json: String, key: String): String {
    return when (key) {
        "id" -> {
            // tiny parser: take "id":"..."
            val needle = "\"id\":\""
            val s = json.indexOf(needle)
            require(s >= 0) { "missing id" }
            val start = s + needle.length
            val end = json.indexOf('"', start)
            json.substring(start, end)
        }
        else -> error("unsupported key: $key")
    }
}
