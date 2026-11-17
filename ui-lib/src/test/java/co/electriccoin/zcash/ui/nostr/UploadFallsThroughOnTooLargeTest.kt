package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 9 R9-4 (R8-fixup F3 remainder, device D-F5) — a large attachment.
 *
 * The file flow allows 25 MB, but the upload hosts that store opaque blobs were probed only up to 1.5 MB. A host that
 * refuses the size answers 413 (Payload Too Large) or 400 with a size message; a host may also answer 2xx with a url
 * on a host the recipient's ZFILE allow-list drops (a CDN of its own), and the recipient then silently never gets the
 * file. Wanted: each of these tries the next host, and the url the upload returns always passes the ZFILE allow-list.
 *
 * The hosts are answered offline by an OkHttp interceptor, as in EncryptedUploadContentTypeTest.
 */
class UploadFallsThroughOnTooLargeTest {
    private val identity = NOSTRIdentity.fromSeed(ByteArray(64) { (0x42 + it).toByte() })
    private val ciphertext = ByteArray(64 * 1024).also { SecureRandom().nextBytes(it) }

    @Test
    fun `a 413 from the first host tries the next host`() =
        runBlocking {
            val servers = Servers { host, sha, size -> if (host == first) answer(413, "Payload Too Large") else stored(host, sha, size) }
            assertFallsThrough(servers)
        }

    @Test
    fun `a 400 size error from the first host tries the next host`() =
        runBlocking {
            val servers =
                Servers { host, sha, size ->
                    if (host == first) answer(400, "File too large. Max size is 1 MB") else stored(host, sha, size)
                }
            assertFallsThrough(servers)
        }

    @Test
    fun `a host that returns a url the recipient's allow-list drops is skipped for the next host`() =
        runBlocking {
            val servers =
                Servers { host, sha, size ->
                    if (host == first) {
                        answer(201, """{"url":"https://cdn.unlisted-media.example/$sha","sha256":"$sha","size":$size}""")
                    } else {
                        stored(host, sha, size)
                    }
                }
            assertFallsThrough(servers)
        }

    // --------------------------------------------------------------- harness

    private val first: String get() =
        java.net
            .URI(
                FileUploadManager(identity, Servers { _, _, _ -> answer(500, "") }.provider).blossomServers.first()
            ).host

    private suspend fun assertFallsThrough(servers: Servers) {
        val outcome = FileUploadManager(identity, servers.provider).upload(ciphertext, FileUploadManager.ENCRYPTED_BLOB_CONTENT_TYPE)
        val success =
            outcome as? UploadOutcome.Success
                ?: fail("the upload gave up instead of trying the next host: $outcome (asked ${servers.asked})")
        assertEquals(first, servers.asked.first(), "fixture sanity: the first host was asked first")
        assertTrue(isAllowedInbound(success.url), "the recipient's allow-list drops the url the upload returned: ${success.url}")
        assertTrue(!success.url.contains(first), "the url is still the refusing first host's: ${success.url}")
        assertTrue(servers.asked.size >= 2, "the next host was never asked (${servers.asked})")
    }

    private fun isAllowedInbound(url: String): Boolean =
        ZFILEMessage.parse(
            ZFILEMessage(
                hash = sha256(ciphertext).take(32),
                type = ZFILEType.PDF,
                size = ciphertext.size.toLong(),
                url = url,
                wrappedKey = "c3ludGhldGljLXdyYXBwZWQta2V5",
                blurhash = "",
            ).serialize(),
        ) != null

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private class Answer(
        val code: Int,
        val body: String,
    )

    private fun answer(
        code: Int,
        body: String,
    ) = Answer(code, body)

    private fun stored(
        host: String,
        sha: String,
        size: Int,
    ) = Answer(201, """{"url":"https://$host/$sha","sha256":"$sha","size":$size,"type":"application/octet-stream"}""")

    private class Servers(
        private val respond: (host: String, sha: String, size: Int) -> Answer,
    ) {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private val interceptor =
            Interceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                asked += host
                val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()
                val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
                val a = respond(host, sha, body.size)
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(a.code)
                    .message(if (a.code in 200..299) "OK" else "Refused")
                    .body(a.body.toResponseBody("application/json".toMediaType()))
                    .build()
            }

        val provider =
            object : HttpClientProvider {
                override suspend fun create(): HttpClient = HttpClient(OkHttp) { engine { addInterceptor(interceptor) } }
            }
    }
}
