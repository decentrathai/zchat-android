package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
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
 * Round 8 R8-fixup F3 (REVIEW_R8 F3, device D-F5) — photos, files and voice notes cannot be sent.
 *
 * ChatViewModel uploads the AES ciphertext of every attachment declared as `image/jpeg`. The upload servers now read
 * the body: on 24-25 Sep 2026 (device log, and `R8-fixup/F3/probe-*.txt`, 2 KiB and 1.5 MB random blobs from a
 * throwaway key, deleted again):
 * - blossom.primal.net answers 415 "unsupported media type application/octet-stream" whatever is declared;
 * - blossom.band and blossom.nostr.build answer 400 "Content-Type header does not match the file content" for a
 *   declared image, and 415 "File type not allowed" for application/octet-stream;
 * - nostr.build (NIP-96) answers 500;
 * - nostr.download, blossom.yakihonne.com, cdn.hzrd149.com and files.sovbit.host store an opaque blob declared
 *   application/octet-stream and serve it back.
 *
 * [ProbedServers] answers exactly that, offline. Wanted:
 * - an encrypted attachment is uploaded as `application/octet-stream` (every upload site in ChatViewModel);
 * - FileUploadManager's server list reaches a server that stores opaque blobs, so the upload succeeds;
 * - every server the app uploads to is on the ZFILE inbound allow-list, or the recipient drops the file; the hosts
 *   files were uploaded to before stay on it, so attachments already sent keep loading.
 */
class EncryptedUploadContentTypeTest {
    private val identity = NOSTRIdentity.fromSeed(ByteArray(64) { (0x21 + it).toByte() })

    /** AES-GCM output is indistinguishable from random bytes. */
    private val ciphertext = ByteArray(4_096).also { SecureRandom().nextBytes(it) }

    @Test
    fun `an encrypted attachment declared as an opaque blob is stored by one of the upload servers`() =
        runBlocking {
            val servers = ProbedServers()
            val outcome = FileUploadManager(identity, servers.provider).upload(ciphertext, ENCRYPTED_BLOB)

            val success =
                outcome as? UploadOutcome.Success
                    ?: fail("every upload server refused the blob: $outcome (asked ${servers.asked})")
            assertEquals(sha256(ciphertext), success.sha256)
            assertTrue(servers.stored.isNotEmpty(), "no server stored the blob")
            assertTrue(isAllowedInbound(success.url), "the recipient's allow-list drops the url the upload returned: ${success.url}")
        }

    @Test
    fun `every attachment upload in ChatViewModel declares an opaque blob`() {
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt")
        val calls = Regex("""uploadManager\.upload\(\s*encrypted\s*,\s*([^)\s{]+)""").findAll(source).map { it.groupValues[1] }.toList()
        // R9-4: the photo, file and voice flows (and their Retry) share one upload site, sendAttachmentBlob;
        // FailedAttachmentKeepsRowAndRetriesTest checks the declared type on the wire for each of the three.
        assertTrue(calls.isNotEmpty(), "fixture sanity: the attachment upload site, found ${calls.size}")
        for (mime in calls) {
            assertTrue(
                mime == "\"$ENCRYPTED_BLOB\"" || mime.endsWith("ENCRYPTED_BLOB_CONTENT_TYPE"),
                "an encrypted attachment is still uploaded as $mime: the servers read the body and refuse it",
            )
        }
    }

    @Test
    fun `every server the app uploads to is on the inbound allow-list`() {
        val manager = FileUploadManager(identity, ProbedServers().provider)
        for (server in manager.blossomServers + manager.nip96Servers) {
            assertTrue(isAllowedInbound("$server/${sha256(ciphertext)}"), "files uploaded to $server are dropped by the recipient")
        }
    }

    /** Control: attachments already sent point at the old hosts; they keep loading. */
    @Test
    fun `control - files already sent to the old hosts still pass the inbound allow-list`() {
        for (host in listOf("blossom.primal.net", "blossom.band", "blossom.nostr.build", "nostr.build", "image.nostr.build")) {
            assertTrue(isAllowedInbound("https://$host/${sha256(ciphertext)}"), "an attachment already sent via $host no longer loads")
        }
    }

    // --------------------------------------------------------------- harness

    private fun isAllowedInbound(url: String): Boolean =
        ZFILEMessage.parse(
            ZFILEMessage(
                hash = sha256(ciphertext).take(32),
                type = ZFILEType.JPEG,
                size = ciphertext.size.toLong(),
                url = url,
                wrappedKey = "c3ludGhldGljLXdyYXBwZWQta2V5",
                blurhash = "",
            ).serialize(),
        ) != null

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** The upload servers as probed on 24-25 Sep 2026, answered offline by an OkHttp interceptor. */
    private class ProbedServers {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val stored: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private val storesOpaque = setOf("nostr.download", "blossom.yakihonne.com", "cdn.hzrd149.com", "files.sovbit.host")

        private val interceptor =
            Interceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                // Ktor's OkHttp engine sends the declared type as the Content-Type header and leaves the body's own type null.
                val declared =
                    (request.header("Content-Type") ?: request.body?.contentType()?.toString())
                        ?.substringBefore(';')
                        ?.trim()
                asked += "${request.method} $host $declared"
                val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()
                val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

                fun answer(
                    code: Int,
                    text: String,
                ): Response =
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message(if (code in 200..299) "OK" else "Refused")
                        .body(text.toResponseBody("application/json".toMediaType()))
                        .build()
                when {
                    host == "blossom.primal.net" -> answer(415, "upload rejected: unsupported media type application/octet-stream")
                    host == "blossom.band" || host == "blossom.nostr.build" ->
                        if (declared == ENCRYPTED_BLOB) {
                            answer(415, "File type not allowed, unsupported.")
                        } else {
                            answer(400, "Content-Type header does not match the file content")
                        }
                    host == "nostr.build" -> answer(500, "Internal Server Error")
                    host in storesOpaque && request.method == "PUT" && request.url.encodedPath == "/upload" -> {
                        stored += host
                        answer(201, """{"url":"https://$host/$sha","sha256":"$sha","size":${body.size},"type":"$declared"}""")
                    }
                    else -> throw java.net.UnknownHostException(host)
                }
            }

        val provider =
            object : HttpClientProvider {
                override suspend fun create(): HttpClient = HttpClient(OkHttp) { engine { addInterceptor(interceptor) } }
            }
    }

    private companion object {
        const val ENCRYPTED_BLOB = "application/octet-stream"
    }
}
