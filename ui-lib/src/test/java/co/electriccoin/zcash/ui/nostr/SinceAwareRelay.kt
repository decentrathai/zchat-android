package co.electriccoin.zcash.ui.nostr

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * KI24-D-ALT (W-12 part A): a local NIP-01 relay on MockWebServer that keeps gift-wraps and serves them by the REQ's
 * `#p` and `since`, as the public relays and ZChat's own relay do. No network leaves the machine. [ScriptedRelay] stays
 * as it is; this one differs in three ways:
 *
 * - it answers EVERY REQ, a new or a repeated subscription id, with the stored kind-1059 events whose `p` tag is in the
 *   REQ's `#p` list: in [Mode.HONOURS_SINCE] only those created at or after the REQ's `since` (a relay keeps a wrap AT
 *   its `since`), in [Mode.IGNORES_SINCE] all of them (a relay that ignores `since`; the client drops the older ones);
 * - after the stored events it may send one more event of the test's ([Latch.marker]): the client handles a socket's
 *   frames in order, so once the marker has gone through, every event before it has too;
 * - the EOSE goes out only after [Latch.beforeEose] returns. A real relay's EOSE comes after network time; without the
 *   latch the EOSE of a local relay races the dispatch of the events it follows, and the `since` line (which moves only
 *   on a settled EOSE) would hold for a reason no phone has (review B2 of the KI24-D-ALT spec).
 *
 * Every published EVENT is kept and acknowledged. [store] adds an event as if it had reached the relay earlier.
 */
internal class SinceAwareRelay(
    private val mode: Mode,
) {
    enum class Mode { HONOURS_SINCE, IGNORES_SINCE }

    /** What the test does around a REQ's answer. Runs on the relay's socket thread; the defaults do nothing. */
    interface Latch {
        /** A REQ arrived; its stored events are about to be sent. */
        fun answering() {}

        /** One more event sent after the stored ones (null for none). */
        fun marker(): String? = null

        /** The stored events and the marker were sent; returns when the EOSE may go. */
        fun beforeEose(markerId: String?) {}
    }

    @Volatile
    var latch: Latch = object : Latch {}

    private val server = MockWebServer()
    private val stored = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()

    /** The `since` of every REQ this relay answered, in arrival order (null: the REQ had none). */
    val reqSinces: MutableList<Long?> = CopyOnWriteArrayList()

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                sockets += webSocket
            }

            // Answer the client's close, so no socket keeps MockWebServer.shutdown waiting.
            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                webSocket.close(code, null)
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                when {
                    text.startsWith("[\"REQ\"") -> answer(webSocket, text)
                    text.startsWith("[\"EVENT\"") -> {
                        val event = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)
                        stored += event
                        webSocket.send("[\"OK\",\"${ScriptedRelay.eventId(event)}\",true,\"\"]")
                    }
                }
            }
        }

    private fun answer(
        webSocket: WebSocket,
        frame: String,
    ) {
        val subId = secondString(frame)
        val since =
            SINCE
                .find(frame)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        val wanted =
            P_FILTER
                .find(frame)
                ?.groupValues
                ?.get(1)
                ?.let { list -> QUOTED.findAll(list).map { it.groupValues[1] }.toSet() }
                .orEmpty()
        reqSinces += since
        val current = latch
        current.answering()
        var markerId: String? = null
        try {
            stored
                .filter { event -> pTag(event) in wanted }
                .filter { event -> mode == Mode.IGNORES_SINCE || since == null || createdAt(event) >= since }
                .forEach { webSocket.send("[\"EVENT\",\"$subId\",$it]") }
            current.marker()?.let { marker ->
                markerId = ScriptedRelay.eventId(marker)
                webSocket.send("[\"EVENT\",\"$subId\",$marker]")
            }
        } finally {
            current.beforeEose(markerId)
        }
        webSocket.send("[\"EOSE\",\"$subId\"]")
    }

    /** Starts the relay; returns its ws:// url. Room for [SOCKETS] sockets (every session and reconnect opens one). */
    fun start(): String {
        repeat(SOCKETS) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
        return "ws://${server.hostName}:${server.port}/"
    }

    fun store(eventJson: String) {
        stored += eventJson
    }

    fun shutdown() {
        sockets.forEach { runCatching { it.cancel() } }
        runCatching { server.shutdown() }
    }

    private companion object {
        const val SOCKETS = 12
        val SINCE = Regex("\"since\":(\\d+)")
        val P_FILTER = Regex("\"#p\":\\[([^\\]]*)\\]")
        val QUOTED = Regex("\"([^\"]*)\"")
        val P_TAG = Regex("\\[\"p\",\"([^\"]*)\"")
        val CREATED_AT = Regex("\"created_at\":(\\d+)")

        /** The outer event's first `p` tag (the tags come before the encrypted content). */
        fun pTag(event: String): String? = P_TAG.find(event)?.groupValues?.get(1)

        /** The outer event's created_at (the first one in the JSON; the inner events are encrypted). */
        fun createdAt(event: String): Long =
            CREATED_AT
                .find(event)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull() ?: 0L

        /** The second JSON string of a frame such as ["REQ","<id>",{…}]. */
        fun secondString(frame: String): String {
            val afterFirst = frame.indexOf(',') + 1
            val start = frame.indexOf('"', afterFirst) + 1
            return frame.substring(start, frame.indexOf('"', start))
        }
    }
}
