package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * RELAY-HEALTH (fix-all 2.13.0, train T5; W-11): a local NIP-01 relay on MockWebServer whose behaviour a test can
 * change while it runs. No network leaves the machine. [ScriptedRelay] and [SinceAwareRelay] stay as they are.
 *
 * - [Mode.SERVES]: every REQ (new or repeated id) gets the stored events created at or after the REQ's `since` (all of
 *   them without one; at most `limit` when the REQ has one), then an EOSE. Every EVENT is kept and answered OK true
 *   ("duplicate:" when it already has it).
 * - [Mode.DOWN_502]: the WebSocket upgrade is answered HTTP 502, as nos.lol did in the T1 run; [dropSockets] ends the
 *   sockets that were open, so the client sees the outage at once.
 * - [Mode.AUTH_GATED]: every REQ gets an AUTH challenge and CLOSED "ERROR: auth-required: …" (relay.damus.io today).
 * - [Mode.RESTRICTED] / [Mode.BLOCKED_READ]: every REQ is CLOSED with "restricted: …" / "blocked: …".
 * - [Mode.SILENT]: REQs are never answered (no EVENT, no EOSE).
 * - [Mode.WRITE_BLOCKED]: reads are served as in SERVES; every EVENT is refused with OK false "blocked: …".
 * - [Mode.RATE_LIMITED] (T5 fixup, RH-X3): every REQ is CLOSED with "rate-limited: …" (not a read block); EVENTs are
 *   answered as in SERVES.
 * - [Mode.DROPS_BEFORE_EOSE] (T5 fixup, RH-X4): the upgrade is accepted and the socket is ended as soon as a REQ
 *   arrives, before any EVENT or EOSE.
 * - [customClosedReason] (ZC-460): while it is set, every REQ is CLOSED with exactly that text, whatever the [mode]
 *   (a reason no mode sends, such as a bare "auth-required"); EVENTs are answered as the [mode] says.
 */
internal class HealthRelay(
    @Volatile var mode: Mode = Mode.SERVES,
) {
    enum class Mode { SERVES, DOWN_502, AUTH_GATED, RESTRICTED, BLOCKED_READ, SILENT, WRITE_BLOCKED, RATE_LIMITED, DROPS_BEFORE_EOSE }

    private val server = MockWebServer()
    private val stored = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()

    /** ZC-460: while not null, every REQ is CLOSED with this reason instead of the [mode]'s answer. */
    @Volatile var customClosedReason: String? = null

    /** Every REQ frame, in arrival order, over all sockets (probes included). */
    val reqFrames: MutableList<String> = CopyOnWriteArrayList()

    /**
     * ZC-406: each REQ frame with the time it arrived, in arrival order. ZC-482: the time is monotonic ms (System.nanoTime,
     * the clock kotlinx delay and withTimeout wait on), never the wall clock: the wall clock can be stepped (on the T5b
     * machine it went back 0.4-1.4 s every 31 s on 1 Oct), and a step inside a measured gap shortened it by the step.
     */
    val reqLog: MutableList<Pair<Long, String>> = CopyOnWriteArrayList()

    /** The id of every EVENT published here, in arrival order (repeats included). */
    val publishedIds: MutableList<String> = CopyOnWriteArrayList()

    /** WebSocket upgrades this relay accepted. */
    val upgrades = AtomicInteger()

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                sockets += webSocket
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                when {
                    text.startsWith("[\"REQ\"") -> onReq(webSocket, text)
                    text.startsWith("[\"EVENT\"") -> onEvent(webSocket, text)
                }
            }
        }

    private fun onReq(
        webSocket: WebSocket,
        frame: String,
    ) {
        reqLog += System.nanoTime() / NANOS_PER_MS to frame
        reqFrames += frame
        val subId = secondString(frame)
        val custom = customClosedReason
        if (custom != null) {
            webSocket.send("[\"CLOSED\",\"$subId\",\"$custom\"]")
            return
        }
        when (mode) {
            Mode.AUTH_GATED -> {
                webSocket.send("[\"AUTH\",\"synthetic-challenge\"]")
                webSocket.send("[\"CLOSED\",\"$subId\",\"ERROR: auth-required: requested filter requires authentication\"]")
            }
            Mode.RESTRICTED -> webSocket.send("[\"CLOSED\",\"$subId\",\"restricted: this relay does not serve kind 1059\"]")
            Mode.BLOCKED_READ -> webSocket.send("[\"CLOSED\",\"$subId\",\"blocked: not on the allow list\"]")
            Mode.RATE_LIMITED -> webSocket.send("[\"CLOSED\",\"$subId\",\"rate-limited: slow down there chief\"]")
            Mode.DROPS_BEFORE_EOSE -> {
                sockets -= webSocket
                webSocket.cancel()
            }
            Mode.SILENT, Mode.DOWN_502 -> Unit
            Mode.SERVES, Mode.WRITE_BLOCKED -> {
                val since = sinceOf(frame)
                val limit = numberAfter(LIMIT, frame)?.toInt() ?: Int.MAX_VALUE
                stored
                    .filter { since == null || (createdAt(it) ?: 0L) >= since }
                    .take(limit)
                    .forEach { webSocket.send("[\"EVENT\",\"$subId\",$it]") }
                webSocket.send("[\"EOSE\",\"$subId\"]")
            }
        }
    }

    private fun onEvent(
        webSocket: WebSocket,
        frame: String,
    ) {
        val event = frame.substring(frame.indexOf('{'), frame.lastIndexOf('}') + 1)
        val id = ScriptedRelay.eventId(event)
        publishedIds += id
        if (mode == Mode.WRITE_BLOCKED) {
            webSocket.send("[\"OK\",\"$id\",false,\"blocked: synthetic write refusal\"]")
            return
        }
        val had = stored.any { ScriptedRelay.eventId(it) == id }
        if (!had) stored += event
        webSocket.send("[\"OK\",\"$id\",true,\"${if (had) "duplicate: already have it" else ""}\"]")
    }

    /** Starts the relay; returns its ws:// url. */
    fun start(): String {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (mode == Mode.DOWN_502) {
                        MockResponse().setResponseCode(502)
                    } else {
                        upgrades.incrementAndGet()
                        MockResponse().withWebSocketUpgrade(listener)
                    }
            }
        server.start()
        return "ws://${server.hostName}:${server.port}/"
    }

    /** Adds an event as if it had reached the relay earlier. */
    fun store(eventJson: String) {
        stored += eventJson
    }

    /** Ends every open socket from the relay's side (with [Mode.DOWN_502]: an outage the client sees at once). */
    fun dropSockets() {
        sockets.forEach { runCatching { it.cancel() } }
        sockets.clear()
    }

    /** How many times an EVENT with [id] was published here. */
    fun publishCount(id: String): Int = publishedIds.count { it == id }

    /** The REQ frames of the inbox subscription (they carry `#p` and no `limit`; a probe's REQ has `"limit":1`). */
    fun inboxReqs(): List<String> = reqFrames.filter { "\"#p\"" in it && "\"limit\"" !in it }

    /** ZC-406: when each inbox REQ ([inboxReqs]) arrived, in arrival order (ZC-482: monotonic ms, see [reqLog]). */
    fun inboxReqTimesMs(): List<Long> =
        reqLog.filter { "\"#p\"" in it.second && "\"limit\"" !in it.second }.map { it.first }

    /** The REQ frames of probes. */
    fun probeReqs(): List<String> = reqFrames.filter { "\"limit\"" in it }

    /**
     * ZC-1874: MockWebServer.shutdown() waits for every open connection to drain, and a socket the client reopened after
     * [dropSockets] can keep it waiting for ever (three full runs hung here on 13 Nov). Shut down on a daemon thread and
     * stop waiting after [SHUTDOWN_WAIT_MS], dropping late sockets once more; a server left behind holds only a port.
     */
    fun shutdown() {
        dropSockets()
        val closer = Thread { runCatching { server.shutdown() } }.apply { isDaemon = true }
        closer.start()
        closer.join(SHUTDOWN_WAIT_MS)
        if (closer.isAlive) {
            dropSockets()
            closer.join(SHUTDOWN_WAIT_MS)
        }
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val SHUTDOWN_WAIT_MS = 2_000L
        private val SINCE = Regex("\"since\":(\\d+)")
        private val LIMIT = Regex("\"limit\":(\\d+)")
        private val CREATED_AT = Regex("\"created_at\":(\\d+)")

        /** The `since` of a REQ frame, or null. */
        fun sinceOf(frame: String): Long? = numberAfter(SINCE, frame)

        fun createdAt(eventJson: String): Long? = numberAfter(CREATED_AT, eventJson)

        private fun numberAfter(
            pattern: Regex,
            text: String,
        ): Long? {
            val match = pattern.find(text) ?: return null
            return match.groupValues[1].toLongOrNull()
        }

        /** A minimal kind-1059 event (the pool reads the id; the relay reads created_at). */
        fun event(
            id: String,
            createdAtSec: Long = 1L,
            tags: String = "",
        ): String =
            "{\"id\":\"$id\",\"kind\":1059,\"pubkey\":\"00\",\"created_at\":$createdAtSec,\"tags\":[$tags]," +
                "\"content\":\"x\",\"sig\":\"00\"}"

        /** The second JSON string of a frame such as ["REQ","<id>",{…}]. */
        private fun secondString(frame: String): String {
            val afterFirst = frame.indexOf(',') + 1
            val start = frame.indexOf('"', afterFirst) + 1
            return frame.substring(start, frame.indexOf('"', start))
        }

        suspend fun awaitTrue(
            timeoutMs: Long = 10_000,
            condition: () -> Boolean,
        ): Boolean =
            withTimeoutOrNull(timeoutMs) {
                while (!condition()) delay(20)
                true
            } ?: false
    }
}
