package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * T2-extra (c), review should-fix S1 (train T1b REVIEW_CROSS X-2a): a CLOSED that answers our own CLOSE starts the pool's
 * re-subscribe of every filter.
 *
 * Since T1b-2 the watchdog's RelayClient.resubscribeFresh opens each subscription under a new id and CLOSEs the old one.
 * dispatchFrame hands every CLOSED to onClosedSub, including a CLOSED for the id we closed ourselves. NIP-01 lets a relay
 * answer a client CLOSE with CLOSED; on such a relay the pool (15 s debounce) subscribes every filter again without
 * closing the fresh one, one more live subscription per filter on every idle tick.
 *
 * Must hold: a CLOSED for a subscription we no longer hold does not call onClosedSub. Control: a CLOSED the relay sends
 * for a live subscription of ours still does (the pool's self-heal). Local MockWebServer relay only.
 */
class ClosedEchoDoesNotResubscribeTest {
    private val server = MockWebServer()
    private var client: RelayClient? = null

    /** Every subscription id a REQ named, and every id a CLOSE named. */
    private val reqIds: MutableList<String> = CopyOnWriteArrayList()
    private val closeIds: MutableList<String> = CopyOnWriteArrayList()

    @After
    fun tearDown() {
        runCatching { client?.close() }
        runCatching { server.shutdown() }
    }

    /** A relay that answers each REQ with EOSE and each CLOSE with CLOSED; with [closeLiveSubs] it also CLOSES each REQ. */
    private fun startRelay(closeLiveSubs: Boolean): String {
        val listener =
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    val subId = secondString(text)
                    when {
                        text.startsWith("[\"REQ\"") -> {
                            reqIds += subId
                            webSocket.send("[\"EOSE\",\"$subId\"]")
                            if (closeLiveSubs) webSocket.send("[\"CLOSED\",\"$subId\",\"rate-limited: slow down\"]")
                        }
                        text.startsWith("[\"CLOSE\"") -> {
                            closeIds += subId
                            webSocket.send("[\"CLOSED\",\"$subId\",\"\"]")
                        }
                    }
                }

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) = Unit
            }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()
        return "ws://${server.hostName}:${server.port}/"
    }

    @Test
    fun `a CLOSED that answers our own CLOSE does not re-subscribe`() =
        runBlocking {
            val url = startRelay(closeLiveSubs = false)
            val closedCalls = CopyOnWriteArrayList<String>()
            val c = RelayClient(url).also { client = it }
            c.onClosedSub = { closedCalls += it }
            c.connect()
            c.subscribe(FILTER) { }
            assertTrue("fixture sanity: the relay got our REQ", ScriptedRelay.awaitTrue { reqIds.size == 1 })

            c.resubscribeFresh()
            assertTrue("fixture sanity: the old id was closed", ScriptedRelay.awaitTrue { closeIds.size == 1 })
            delay(500) // the relay's CLOSED answer reaches the client

            assertEquals(
                "the relay's CLOSED for the subscription we closed ourselves was taken as a lost subscription",
                emptyList<String>(),
                closedCalls.toList(),
            )
        }

    /** Control, green before and after: a relay that CLOSES a live subscription of ours still triggers the self-heal. */
    @Test
    fun `control - a CLOSED for a live subscription still reaches the pool`() =
        runBlocking {
            val url = startRelay(closeLiveSubs = true)
            val closedCalls = CopyOnWriteArrayList<String>()
            val c = RelayClient(url).also { client = it }
            c.onClosedSub = { closedCalls += it }
            c.connect()
            val subId = c.subscribe(FILTER) { }

            assertTrue(ScriptedRelay.awaitTrue { closedCalls.isNotEmpty() })
            assertEquals(listOf(subId), closedCalls.toList())
        }

    private companion object {
        val FILTER: Map<String, Any> = mapOf("kinds" to listOf(1059), "#p" to listOf("00"))

        /** The second JSON string of a frame such as ["REQ","<id>",{…}] or ["CLOSE","<id>"]. */
        fun secondString(frame: String): String {
            val afterFirst = frame.indexOf(',') + 1
            if (afterFirst <= 0) return ""
            val start = frame.indexOf('"', afterFirst) + 1
            val end = frame.indexOf('"', start)
            return if (start > 0 && end > start) frame.substring(start, end) else ""
        }
    }
}
