package co.electriccoin.zcash.ui.nostr

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
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

    private val subscriptions = ConcurrentHashMap<String, (String) -> Unit>()
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
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!openSignal.isCompleted) openSignal.completeExceptionally(t)
                pendingPublishes.values.forEach { it.completeExceptionally(t) }
                pendingPublishes.clear()
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
        val subId = "s" + Random.nextLong().toULong().toString(36)
        subscriptions[subId] = onEvent
        val frame = "[\"REQ\",${jsonString(subId)},${filterToJson(filter)}]"
        webSocket?.send(frame) ?: error("connect() before subscribe()")
        Log.d(TAG, "REQ -> $url sub=$subId filter=${filterToJson(filter)}")
        return subId
    }

    fun unsubscribe(subId: String) {
        subscriptions.remove(subId)
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
                Log.d(TAG, "EVENT in <- $url sub=$subId")
                subscriptions[subId]?.invoke(eventJson)
            }
            frame.startsWith("[\"OK\"") -> {
                // ["OK", eventId, ok, message]
                val idStart = frame.indexOf('"', "[\"OK\",".length) + 1
                val idEnd = frame.indexOf('"', idStart)
                if (idStart < 0 || idEnd < 0) return
                val eventId = frame.substring(idStart, idEnd)
                val okStart = frame.indexOf(',', idEnd) + 1
                val ok = frame.substring(okStart).trimStart().startsWith("true")
                pendingPublishes.remove(eventId)?.complete(ok)
            }
            frame.startsWith("[\"CLOSED\"") -> {
                // ["CLOSED", subId, reason] — the relay tore down our subscription (rate-limit,
                // transient auth, too-many-concurrent-REQs). Previously UNHANDLED: the sub went
                // permanently silent with no log. Surface it + let the pool re-subscribe.
                val s = frame.indexOf('"', "[\"CLOSED\",".length) + 1
                val e = if (s > 0) frame.indexOf('"', s) else -1
                val subId = if (e > s) frame.substring(s, e) else "?"
                Log.w(TAG, "relay $url CLOSED sub=$subId: ${if (e >= 0) frame.substring(e + 1).take(140) else ""}")
                subscriptions.remove(subId)
                onClosedSub?.invoke(subId)
            }
            frame.startsWith("[\"AUTH\"") ->
                Log.w(TAG, "relay $url requested AUTH (unsupported): ${frame.take(140)}")
            frame.startsWith("[\"NOTICE\"") ->
                Log.w(TAG, "relay $url NOTICE: ${frame.take(160)}")
            frame.startsWith("[\"EOSE\"") ->
                Log.d(TAG, "relay $url EOSE")
        }
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
