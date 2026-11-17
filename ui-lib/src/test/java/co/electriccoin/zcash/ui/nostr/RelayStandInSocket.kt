package co.electriccoin.zcash.ui.nostr

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString

/**
 * ZC-761: a socket for tests that drive a RelayClient without a relay. It takes every frame (`send` answers true) and
 * records it. Until ZC-761 such tests used a client with no socket, because RelayClient then kept a subscription whose
 * REQ was refused; it no longer does, so a test that needs a held read gives its client this socket.
 */
internal class RelayStandInSocket : WebSocket {
    val sent = mutableListOf<String>()

    override fun request(): Request = Request.Builder().url("https://relay.synthetic.invalid/").build()

    override fun queueSize(): Long = 0L

    override fun send(text: String): Boolean {
        sent += text
        return true
    }

    override fun send(bytes: ByteString): Boolean = true

    override fun close(
        code: Int,
        reason: String?,
    ): Boolean = true

    override fun cancel() = Unit
}

/** Puts a [RelayStandInSocket] where `connect()` would put OkHttp's socket; returns the client. */
internal fun RelayClient.withStandInSocket(): RelayClient =
    apply {
        RelayClient::class.java
            .getDeclaredField("webSocket")
            .apply { isAccessible = true }
            .set(this, RelayStandInSocket())
    }
