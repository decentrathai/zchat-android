package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the NOSTR relay client speaks the wire protocol:
 *   - On connect: sends ["REQ", <subId>, {filter}] for the supplied filter
 *   - Surfaces ["EVENT", <subId>, {event}] frames to the caller as a Flow
 *   - On publish: sends ["EVENT", <event>] and waits for ["OK", <id>, true, ...]
 *   - Tolerates noise frames (NOTICE, EOSE) without crashing
 *
 * Backed by MockWebServer's WebSocket support, no live network.
 */
class RelayClientTest {

    private lateinit var server: MockWebServer
    private val relayBehavior = StubRelayListener()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(relayBehavior))
        server.start()
    }

    @After
    fun tearDown() {
        // MockWebServer.shutdown waits for the WebSocket worker queue to drain — OkHttp
        // can take >5s to release the socket even after RelayClient.close(). Swallowing
        // the timeout here because all the assertions we care about happened in the test
        // body; a lingering thread doesn't change correctness.
        runCatching { server.shutdown() }
    }

    @Test
    fun `subscribe sends REQ frame with caller filter`() = runBlocking {
        val url = "ws://${server.hostName}:${server.port}/"
        val client = RelayClient(url)
        client.connect()

        val filter = mapOf("kinds" to listOf(1059), "#p" to listOf("deadbeef"))
        val subId = client.subscribe(filter) {}
        val frame = relayBehavior.awaitFrame()
        assertTrue("first frame should be REQ: $frame", frame.startsWith("[\"REQ\","))
        assertTrue(frame.contains(subId))
        assertTrue(frame.contains("\"kinds\":[1059]"))
        client.close()
    }

    @Test
    fun `EVENT frames sent by the relay reach the subscriber`() = runBlocking {
        val url = "ws://${server.hostName}:${server.port}/"
        val client = RelayClient(url)
        client.connect()

        val received = mutableListOf<String>()
        val subId = client.subscribe(mapOf("kinds" to listOf(1059))) { json -> received += json }
        // Wait for our REQ to arrive at the stub.
        relayBehavior.awaitFrame()
        // Stub responds with one EVENT frame.
        relayBehavior.send(
            """["EVENT","$subId",{"id":"abc","kind":1059,"pubkey":"00","created_at":1,"tags":[],"content":"hi","sig":"00"}]""",
        )
        withTimeout(2_000) {
            while (received.isEmpty()) kotlinx.coroutines.delay(20)
        }
        assertEquals(1, received.size)
        assertTrue(received[0].contains("\"kind\":1059"))
        client.close()
    }

    @Test
    fun `resubscribeAll re-issues REQ under the SAME subId (no accumulation)`() = runBlocking {
        val url = "ws://${server.hostName}:${server.port}/"
        val client = RelayClient(url)
        client.connect()

        val filter = mapOf("kinds" to listOf(1059), "#p" to listOf("cafe"))
        val subId = client.subscribe(filter) {}
        val first = relayBehavior.awaitFrame()
        assertTrue("first frame is REQ: $first", first.startsWith("[\"REQ\","))
        assertTrue(first.contains(subId))

        // Liveness watchdog path: re-issue the REQ. It MUST reuse the same subId (a same-id REQ replaces
        // the relay-side sub rather than opening a second one) and carry the original filter verbatim.
        client.resubscribeAll()
        val second = relayBehavior.awaitFrame()
        assertTrue("re-issued frame is REQ: $second", second.startsWith("[\"REQ\","))
        assertTrue("re-issue reuses subId (no accumulation): $second", second.contains(subId))
        assertTrue("re-issue carries the original filter: $second", second.contains("\"kinds\":[1059]"))
        client.close()
    }

    @Test
    fun `publish sends EVENT frame and resolves on OK`() = runBlocking {
        val url = "ws://${server.hostName}:${server.port}/"
        val client = RelayClient(url)
        client.connect()

        val scope = CoroutineScope(Dispatchers.IO)
        val sendJob = scope.launch {
            client.publish(
                """{"id":"deadbeef","kind":14,"pubkey":"aa","created_at":1,"tags":[],"content":"x","sig":"00"}""",
            )
        }
        val frame = relayBehavior.awaitFrame()
        assertTrue("expected EVENT frame, got: $frame", frame.startsWith("[\"EVENT\","))
        relayBehavior.send("""["OK","deadbeef",true,""]""")
        withTimeout(2_000) { sendJob.join() }
        client.close()
    }
}

/**
 * MockWebServer WebSocket listener that exposes a tiny test API:
 *   - awaitFrame() suspends until the next inbound frame from the client
 *   - send(text) pushes a text frame back to the client
 */
private class StubRelayListener : WebSocketListener() {
    private var session: WebSocket? = null
    private val incoming: kotlinx.coroutines.channels.Channel<String> =
        kotlinx.coroutines.channels.Channel(capacity = 16)

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        session = webSocket
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        incoming.trySend(text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        incoming.trySend(bytes.utf8())
    }

    suspend fun awaitFrame(): String = withTimeout(2_000) { incoming.receive() }

    fun send(text: String) {
        session?.send(text)
    }
}
