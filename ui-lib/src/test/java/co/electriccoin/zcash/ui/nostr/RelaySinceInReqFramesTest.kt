package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * KI-24 part B (fix-all 2.13.0, train T4; SPECS §2.3 steps 7-9, §2.7 T2-extra S1): the pool asks each relay from its
 * `since` line, on every REQ it sends, and reports each end of stored events.
 *
 * A local relay on MockWebServer records every REQ frame, replays what it holds under a new subscription id and ends
 * with EOSE; it ignores `since` (the worst case), so the client-side drop is what keeps an older wrap out. No network
 * leaves the machine. Event ids are synthetic.
 */
class RelaySinceInReqFramesTest {
    private companion object {
        val FILTER: Map<String, Any> = mapOf("kinds" to listOf(1059), "#p" to listOf("00"))

        fun event(
            id: String,
            createdAt: Long,
        ): String =
            "{\"id\":\"$id\",\"kind\":1059,\"pubkey\":\"00\",\"created_at\":$createdAt," +
                "\"tags\":[],\"content\":\"x\",\"sig\":\"00\"}"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val stored = CopyOnWriteArrayList<String>()
    private val frames = CopyOnWriteArrayList<String>()
    private var pool: NostrRelayPool? = null

    private val listener =
        object : WebSocketListener() {
            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                frames += text
                if (text.startsWith("[\"REQ\"")) {
                    val subStart = text.indexOf('"', "[\"REQ\",".length) + 1
                    val subId = text.substring(subStart, text.indexOf('"', subStart))
                    stored.forEach { webSocket.send("[\"EVENT\",\"$subId\",$it]") }
                    webSocket.send("[\"EOSE\",\"$subId\"]")
                }
            }

            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) = Unit
        }

    private class RecordingStore(
        initial: Long?,
    ) : RelaySinceStore {
        val since = AtomicLong(initial ?: 0L)
        val eoses: MutableList<String> = CopyOnWriteArrayList()

        override fun sinceFor(relayUrl: String): Long? = since.get().takeIf { it > 0 }

        override fun onEose(
            relayUrl: String,
            eoseWallSec: Long,
        ) {
            eoses += relayUrl
        }
    }

    @After
    fun tearDown() {
        runCatching { pool?.stop() }
        scope.cancel()
        runCatching { server.shutdown() }
    }

    private fun start(
        store: RelaySinceStore?,
        received: MutableList<String>,
    ): String {
        repeat(4) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
        val url = "ws://${server.hostName}:${server.port}/"
        pool =
            NostrRelayPool(listOf(url), scope, sinceStore = store).also { p ->
                p.start()
                p.subscribe(FILTER) { json -> received += ScriptedRelay.eventId(json) }
            }
        return url
    }

    private fun reqFrames() = frames.filter { it.startsWith("[\"REQ\"") }

    @Test
    fun `a REQ carries the relay's since, an older wrap the relay still serves is dropped, and the EOSE is reported`() =
        runBlocking {
            stored += event("synthetic-older", createdAt = 999)
            stored += event("synthetic-newer", createdAt = 1_000)
            val store = RecordingStore(initial = 1_000)
            val received = CopyOnWriteArrayList<String>()
            val url = start(store, received)

            assertTrue(ScriptedRelay.awaitTrue { store.eoses.isNotEmpty() })
            delay(200)
            assertTrue("the REQ asks from the since line: ${reqFrames()}", reqFrames().first().contains("\"since\":1000"))
            assertEquals("only the wrap at or after the since reaches the pool", listOf("synthetic-newer"), received.toList())
            assertEquals(listOf(url), store.eoses.distinct())
        }

    @Test
    fun `every re-sent REQ carries the since current at that moment, and the new REQ still goes before the old CLOSE`() =
        runBlocking {
            val store = RecordingStore(initial = 1_000)
            start(store, CopyOnWriteArrayList())
            assertTrue(ScriptedRelay.awaitTrue { store.eoses.isNotEmpty() })

            delay(200)
            val before = frames.size
            store.since.set(2_000)
            pool!!.redeliverBacklog()
            assertTrue(ScriptedRelay.awaitTrue { frames.drop(before).any { it.startsWith("[\"CLOSE\"") } })
            delay(200)

            val after = frames.drop(before)
            val reqAt = after.indexOfFirst { it.startsWith("[\"REQ\"") }
            val closeAt = after.indexOfFirst { it.startsWith("[\"CLOSE\"") }
            assertTrue("the re-sent REQ asks from the new since: $after", reqAt >= 0 && after[reqAt].contains("\"since\":2000"))
            assertTrue("REQ before CLOSE, as T1b-2 sends them: $after", reqAt < closeAt)
        }

    @Test
    fun `with no since store the REQ has no since and every stored wrap is delivered, as before KI-24`() =
        runBlocking {
            stored += event("synthetic-a", createdAt = 1)
            stored += event("synthetic-b", createdAt = 2)
            val received = CopyOnWriteArrayList<String>()
            start(store = null, received = received)

            assertTrue(ScriptedRelay.awaitTrue { received.size >= 2 })
            assertFalse(reqFrames().first().contains("since"))
            assertEquals(listOf("synthetic-a", "synthetic-b"), received.toList())
        }
}
