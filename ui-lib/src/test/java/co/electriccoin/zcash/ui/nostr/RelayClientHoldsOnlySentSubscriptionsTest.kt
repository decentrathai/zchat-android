package co.electriccoin.zcash.ui.nostr

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-761 (T7b triage 13 Oct, SMALL work order; late messages): a subscription whose REQ never left stays in the
 * relay client's table.
 *
 * At 828ed7c0a `RelayClient.subscribe` puts the subscription into its table first and then sends the REQ. With no
 * socket it throws after that, and with a socket that is already closing (`WebSocket.send` answers false) it returns
 * as if the relay had the subscription. Either way `holds(filter)` answers true, so after a socket rebuild the pool's
 * `resubscribeLost` skips that read: one relay of three stays unread until the 2-minute watchdog (late, not lost).
 *
 * Wanted: the client holds a subscription only when its REQ was handed to the socket; a REQ that could not be sent
 * leaves nothing behind (and the call fails, as it does with no socket, so the pool's runCatching logs it).
 *
 * The socket is a stand-in put where `connect()` would put OkHttp's; every filter and URL is synthetic.
 */
class RelayClientHoldsOnlySentSubscriptionsTest {
    private val filter: Map<String, Any> = mapOf("kinds" to listOf(1059), "#p" to listOf("synthetic-zc761-key"))

    @Test
    fun `a subscribe with no socket leaves no subscription behind`() {
        val client = RelayClient(URL)

        val call = runCatching { client.subscribe(filter) { } }

        assertTrue("fixture: a subscribe with no socket fails", call.isFailure)
        assertFalse("the client holds a subscription whose REQ was never sent", client.holds(filter))
    }

    @Test
    fun `a subscribe whose REQ the closing socket refused leaves no subscription behind`() {
        val socket = StandInSocket(accepts = false)
        val client = RelayClient(URL).withSocket(socket)

        runCatching { client.subscribe(filter) { } }

        assertEquals("fixture: the REQ was offered to the socket once", 1, socket.offered.size)
        assertFalse("the client holds a subscription whose REQ the socket refused", client.holds(filter))
    }

    @Test
    fun `control - a subscribe whose REQ was sent is held`() {
        val socket = StandInSocket(accepts = true)
        val client = RelayClient(URL).withSocket(socket)

        client.subscribe(filter) { }

        assertTrue("the client does not hold a subscription it sent", client.holds(filter))
        assertTrue("the REQ frame was not sent: ${socket.offered}", socket.offered.single().startsWith("[\"REQ\""))
    }

    private fun RelayClient.withSocket(socket: WebSocket): RelayClient =
        apply {
            RelayClient::class.java
                .getDeclaredField("webSocket")
                .apply { isAccessible = true }
                .set(this, socket)
        }

    /** A socket that answers every send with [accepts] and records what it was offered. */
    private class StandInSocket(
        private val accepts: Boolean,
    ) : WebSocket {
        val offered = mutableListOf<String>()

        override fun request(): Request = Request.Builder().url("https://relay.synthetic.invalid/").build()

        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean {
            offered += text
            return accepts
        }

        override fun send(bytes: ByteString): Boolean = accepts

        override fun close(
            code: Int,
            reason: String?,
        ): Boolean = true

        override fun cancel() = Unit
    }

    private companion object {
        const val URL = "wss://relay.synthetic.invalid"
    }
}
