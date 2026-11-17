package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * T1b-2 (train T1b, findings 3 and 6): a local NIP-01 relay on MockWebServer that behaves like the relays in the T1
 * capture. No network leaves the machine.
 *
 * - [Mode.OWN_RELAY] is relay.zsend.xyz as the T1 capture shows it: a REQ under a subscription id it has not seen on
 *   that socket gets the stored events and an EOSE; a REQ repeated under a live id gets nothing at all (in the whole
 *   T1 capture it answered its 13 fresh connections and none of the repeats). A CLOSE frees the id.
 * - [Mode.AUTH_GATED] is relay.damus.io as the T1 capture shows it: every REQ gets an AUTH challenge and is CLOSED
 *   with "ERROR: auth-required: …" (no events), while every EVENT we publish is still acknowledged with OK true.
 *
 * Every published EVENT is acknowledged and kept (it is not pushed live to other subscribers: the tests need only the
 * replay). [store] adds an event silently, as if it had reached the relay while our copy was never handed on.
 */
internal class ScriptedRelay(
    private val mode: Mode = Mode.OWN_RELAY,
) {
    enum class Mode { OWN_RELAY, AUTH_GATED }

    private val server = MockWebServer()
    private val stored = CopyOnWriteArrayList<String>()
    private val liveIds = ConcurrentHashMap<WebSocket, MutableSet<String>>()

    /** Every subscription id a REQ named, in arrival order, over all sockets. */
    val reqIds: MutableList<String> = CopyOnWriteArrayList()

    /** Every subscription id a CLOSE named. */
    val closedIds: MutableList<String> = CopyOnWriteArrayList()

    /** The ids of the events we published here. */
    val publishedIds: MutableList<String> = CopyOnWriteArrayList()

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                liveIds[webSocket] = ConcurrentHashMap.newKeySet()
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                when {
                    text.startsWith("[\"REQ\"") -> onReq(webSocket, secondString(text))
                    text.startsWith("[\"CLOSE\"") -> {
                        val subId = secondString(text)
                        closedIds += subId
                        liveIds[webSocket]?.remove(subId)
                    }
                    text.startsWith("[\"EVENT\"") -> {
                        val event = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)
                        val id = eventId(event)
                        publishedIds += id
                        stored += event
                        webSocket.send("[\"OK\",\"$id\",true,\"\"]")
                    }
                }
            }
        }

    private fun onReq(
        webSocket: WebSocket,
        subId: String,
    ) {
        reqIds += subId
        when (mode) {
            Mode.AUTH_GATED -> {
                webSocket.send("[\"AUTH\",\"synthetic-challenge\"]")
                webSocket.send("[\"CLOSED\",\"$subId\",\"ERROR: auth-required: requested filter requires authentication\"]")
            }
            Mode.OWN_RELAY -> {
                val live = liveIds.getOrPut(webSocket) { ConcurrentHashMap.newKeySet() }
                if (!live.add(subId)) return // a repeated REQ under a live id: nothing, not even an EOSE
                stored.forEach { webSocket.send("[\"EVENT\",\"$subId\",$it]") }
                webSocket.send("[\"EOSE\",\"$subId\"]")
            }
        }
    }

    /** Starts the relay; returns its ws:// url. Room for several sockets (reconnects). */
    fun start(): String {
        repeat(SOCKETS) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
        return "ws://${server.hostName}:${server.port}/"
    }

    fun store(eventJson: String) {
        stored += eventJson
    }

    fun shutdown() {
        runCatching { server.shutdown() }
    }

    companion object {
        private const val SOCKETS = 12

        /** A minimal kind-1059 event with this id (the pool reads only the id). */
        fun event(id: String): String =
            "{\"id\":\"$id\",\"kind\":1059,\"pubkey\":\"00\",\"created_at\":1,\"tags\":[],\"content\":\"x\",\"sig\":\"00\"}"

        fun eventId(json: String): String {
            val start = json.indexOf("\"id\":\"") + "\"id\":\"".length
            return json.substring(start, json.indexOf('"', start))
        }

        /** The second JSON string of a frame such as ["REQ","<id>",{…}] or ["CLOSE","<id>"]. */
        private fun secondString(frame: String): String {
            val afterFirst = frame.indexOf(',') + 1
            val start = frame.indexOf('"', afterFirst) + 1
            return frame.substring(start, frame.indexOf('"', start))
        }

        /** A url nothing listens on: a relay that is down (nos.lol answering 502 in the T1 run). */
        const val DOWN_RELAY = "ws://127.0.0.1:9/"

        suspend fun awaitTrue(
            timeoutMs: Long = 5_000,
            condition: () -> Boolean,
        ): Boolean =
            withTimeoutOrNull(timeoutMs) {
                while (!condition()) delay(20)
                true
            } ?: false
    }
}
