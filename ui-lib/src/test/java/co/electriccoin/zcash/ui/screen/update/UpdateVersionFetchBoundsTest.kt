package co.electriccoin.zcash.ui.screen.update

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Regression lock for audit fable-20260915 A.9: the foss updater's version check runs on every
 * launch (UpdateCheckOverlay, first composition) and used to read the WHOLE response body into
 * memory with no size limit and no overall deadline, inside a `catch (e: Exception)`. A hostile or
 * man-in-the-middled api.zsend.xyz could send an endless body: the read grows until it raises
 * OutOfMemoryError, which is an Error, escapes the catch, and kills the process — on every launch,
 * until the server is fixed. A server that dribbles one byte at a time never trips OkHttp's
 * per-read timeout either, so the check could hang for as long as the server liked.
 *
 * These tests drive the real [fetchVersionDocument] (Ktor + OkHttp) against a local socket server
 * and pin:
 *  - a version document is never read past a small cap (64 KB): an oversized response yields null,
 *    and the client hangs up instead of consuming the rest;
 *  - the whole request has a deadline: a dribbling server yields null at the timeout;
 *  - normal documents still parse, including deeply nested unknown keys (the audit REFUTED the
 *    StackOverflowError half of the claim — kotlinx.serialization skips unknown elements
 *    iteratively — so that case is a control, not a fix).
 */
class UpdateVersionFetchBoundsTest {
    private val servers = mutableListOf<LocalHttpServer>()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    @Test
    fun `a normal version document is parsed`() {
        val server = serve { out -> out.writeResponse(VALID_DOCUMENT) }

        val result = runBlocking { fetchVersionDocument(server.url, requestTimeoutMillis = 5_000) }

        assertNotNull("a well-formed version document must parse", result)
        assertEquals(21300, result!!.versionCode)
        assertEquals("2.13.0", result.versionName)
        assertEquals(1, result.changelog.size)
    }

    @Test
    fun `deeply nested unknown keys are skipped without crashing`() {
        val depth = 10_000
        val nested = "[".repeat(depth) + "]".repeat(depth)
        val body = """{"unknown":$nested,${VALID_DOCUMENT.removePrefix("{")}"""
        val server = serve { out -> out.writeResponse(body) }

        val result = runBlocking { fetchVersionDocument(server.url, requestTimeoutMillis = 5_000) }

        assertNotNull("a nested unknown key must be skipped, not overflow the stack", result)
        assertEquals(21300, result!!.versionCode)
    }

    @Test
    fun `a document just under the cap is still parsed`() {
        val padding = "a".repeat(60 * 1024)
        val body = """{"padding":"$padding",${VALID_DOCUMENT.removePrefix("{")}"""
        val server = serve { out -> out.writeResponse(body) }

        val result = runBlocking { fetchVersionDocument(server.url, requestTimeoutMillis = 5_000) }

        assertNotNull("a ${body.length}-byte document is within the 64 KB cap and must parse", result)
    }

    @Test
    fun `a response larger than the cap is refused`() {
        val padding = "a".repeat(1024 * 1024)
        val body = """{"padding":"$padding",${VALID_DOCUMENT.removePrefix("{")}"""
        val server = serve { out -> out.writeResponse(body) }

        val result = runBlocking { fetchVersionDocument(server.url, requestTimeoutMillis = 10_000) }

        assertNull(
            "a 1 MB response is not a version document; it must be refused, not buffered and parsed",
            result
        )
    }

    @Test
    fun `an oversized response is abandoned, not read to the end`() {
        // 32 MB of an otherwise valid document, streamed without a Content-Length. Whatever the
        // kernel and client buffers absorb is far below that, so if the server manages to write
        // every byte, the client consumed the whole body — the unbounded read that ends in an
        // OutOfMemoryError when the body never ends.
        val totalPaddingBytes = 32L * 1024 * 1024
        val wroteEverything = AtomicBoolean(false)
        val serverDone = CountDownLatch(1)
        val server =
            serve { out ->
                try {
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n" +
                                """{"padding":""""
                        ).toByteArray()
                    )
                    val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
                    var written = 0L
                    while (written < totalPaddingBytes) {
                        out.write(chunk)
                        written += chunk.size
                    }
                    out.write(""","${VALID_DOCUMENT.removePrefix("{")}""".toByteArray())
                    out.flush()
                    wroteEverything.set(true)
                } catch (expected: IOException) {
                    // The client hung up: that is the behaviour under test.
                } finally {
                    serverDone.countDown()
                }
            }

        val result = runBlocking { fetchVersionDocument(server.url, requestTimeoutMillis = 30_000) }

        assertTrue("the server side must finish", serverDone.await(30, TimeUnit.SECONDS))
        assertNull("an oversized response must yield no version document", result)
        assertFalse(
            "the client must stop reading at the cap and hang up; it read all 32 MB instead",
            wroteEverything.get()
        )
    }

    @Test
    fun `a server that dribbles its response is abandoned at the request timeout`() {
        val body = VALID_DOCUMENT.toByteArray()
        val server =
            serve { out ->
                try {
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        ).toByteArray()
                    )
                    out.flush()
                    // One byte every 100 ms: each read succeeds long before any per-read timeout,
                    // and the whole ~170-byte document takes about 17 seconds.
                    for (byte in body) {
                        out.write(byte.toInt())
                        out.flush()
                        Thread.sleep(DRIBBLE_INTERVAL_MS)
                    }
                } catch (expected: IOException) {
                    // The client gave up.
                } catch (expected: InterruptedException) {
                    // Test teardown.
                }
            }

        val started = System.nanoTime()
        val result = runBlocking { fetchVersionDocument(server.url, requestTimeoutMillis = 1_000) }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertNull("a response that does not finish within the request timeout must yield null", result)
        assertTrue(
            "the check must give up at the 1 s request timeout, not wait for the dribble; took $elapsedMs ms",
            elapsedMs < 4_000
        )
    }

    private fun serve(handler: (OutputStream) -> Unit): LocalHttpServer = LocalHttpServer(handler).also { servers += it }

    private fun OutputStream.writeResponse(body: String) {
        val bytes = body.toByteArray()
        write(
            (
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            ).toByteArray()
        )
        write(bytes)
        flush()
    }

    /** A minimal HTTP/1.1 server on loopback: reads each request's headers, then runs [handler]. */
    private class LocalHttpServer(
        private val handler: (OutputStream) -> Unit
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
        private val accepted = mutableListOf<Socket>()

        val url: String = "http://127.0.0.1:${serverSocket.localPort}/app/version"

        private val acceptor =
            thread(isDaemon = true, name = "update-fetch-test-server") {
                while (!serverSocket.isClosed) {
                    val socket =
                        try {
                            serverSocket.accept()
                        } catch (closed: IOException) {
                            break
                        }
                    synchronized(accepted) { accepted += socket }
                    thread(isDaemon = true, name = "update-fetch-test-connection") {
                        socket.use { connection ->
                            try {
                                connection.readRequestHeaders()
                                handler(connection.getOutputStream())
                            } catch (ignored: IOException) {
                                // The client went away.
                            }
                        }
                    }
                }
            }

        private fun Socket.readRequestHeaders() {
            val input = getInputStream()
            var matched = 0
            val terminator = "\r\n\r\n"
            while (matched < terminator.length) {
                val next = input.read()
                if (next == -1) return
                matched =
                    when {
                        next.toChar() == terminator[matched] -> matched + 1
                        next == '\r'.code -> 1
                        else -> 0
                    }
            }
        }

        override fun close() {
            serverSocket.close()
            synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }
            acceptor.interrupt()
        }
    }

    private companion object {
        const val BACKLOG = 4
        const val DRIBBLE_INTERVAL_MS = 100L
        const val VALID_DOCUMENT =
            """{"versionCode":21300,"versionName":"2.13.0",""" +
                """"downloadUrl":"https://api.zsend.xyz/app/download/zchat-v2.13.0.apk",""" +
                """"changelog":[{"type":"fixed","text":"Updater hardening"}]}"""
    }
}
