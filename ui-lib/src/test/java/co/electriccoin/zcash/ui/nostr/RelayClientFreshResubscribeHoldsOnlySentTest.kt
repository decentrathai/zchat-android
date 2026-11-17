package co.electriccoin.zcash.ui.nostr

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-959 (train T7a1 lane, 14 Oct; the ZC-761 defect in a second place; late messages): `RelayClient.resubscribeFresh`
 * keeps a table entry whose REQ was never sent.
 *
 * At 2cd72e97a `resubscribeFresh` puts each subscription into its table under a new id, offers the REQ, ignores the
 * socket's answer, then removes the old id and offers its CLOSE. With a socket that is already closing (`WebSocket.send`
 * answers false) the table ends up holding only the new id, whose REQ the relay never got, and the read the relay did get
 * is forgotten. `holds(filter)` answers true for a read that never started, so the pool's `resubscribeLost` skips it.
 *
 * Wanted, as ZC-761 does for `subscribe`: a fresh REQ the socket refused leaves nothing behind (the new id is not
 * kept), the read whose REQ was sent stays as it was (its CLOSE is not offered), and the call fails so the pool's
 * runCatching logs it. Control: a fresh REQ the socket takes replaces the old id, REQ first, then the old id's CLOSE.
 *
 * The socket is a stand-in put where `connect()` would put OkHttp's; every filter and URL is synthetic.
 */
class RelayClientFreshResubscribeHoldsOnlySentTest {
    private val filter: Map<String, Any> = mapOf("kinds" to listOf(1059), "#p" to listOf("synthetic-zc959-key"))

    @Test
    fun `a fresh re-subscribe whose REQ the closing socket refused keeps only the read that was sent`() {
        val socket = SwitchableSocket()
        val client = RelayClient(URL).withSocket(socket)
        val sentId = client.subscribe(filter) { }
        socket.accepts = false
        socket.offered.clear()

        val call = runCatching { client.resubscribeFresh() }

        assertTrue("fixture: the fresh REQ was offered to the socket: ${socket.offered}", socket.offered.any { it.startsWith(REQ) })
        assertEquals("the client holds a read whose REQ the socket refused", setOf(sentId), client.heldIds())
        assertTrue("the CLOSE of the read the relay holds was offered: ${socket.offered}", socket.offered.none { it.startsWith(CLOSE) })
        assertTrue("a refused fresh REQ returned as if the relay had it", call.isFailure)
    }

    @Test
    fun `control - a fresh re-subscribe the socket took replaces the old id, REQ first`() {
        val socket = SwitchableSocket()
        val client = RelayClient(URL).withSocket(socket)
        val oldId = client.subscribe(filter) { }
        socket.offered.clear()

        client.resubscribeFresh()

        val held = client.heldIds()
        assertEquals("one read per filter after a fresh re-subscribe: $held", 1, held.size)
        assertTrue("the old id is still held", oldId !in held)
        assertTrue("the read is not held after a fresh re-subscribe", client.holds(filter))
        assertEquals(
            "not the new REQ followed by the old id's CLOSE: ${socket.offered}",
            listOf(REQ, CLOSE),
            socket.offered.map { it.substring(0, it.indexOf(',') + 1) },
        )
    }

    private fun RelayClient.withSocket(socket: WebSocket): RelayClient =
        apply {
            RelayClient::class.java
                .getDeclaredField("webSocket")
                .apply { isAccessible = true }
                .set(this, socket)
        }

    @Suppress("UNCHECKED_CAST")
    private fun RelayClient.heldIds(): Set<String> {
        val field = RelayClient::class.java.getDeclaredField("subscriptions").apply { isAccessible = true }
        return (field.get(this) as Map<String, *>).keys.toSet()
    }

    /** A socket that answers every send with [accepts] and records what it was offered. */
    private class SwitchableSocket : WebSocket {
        var accepts = true
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
        const val REQ = "[\"REQ\","
        const val CLOSE = "[\"CLOSE\","
    }
}
