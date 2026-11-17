package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.common.provider.TorLikeServers
import fr.acinq.secp256k1.Secp256k1
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-249 (HIGH) — with "Beta: Tor Protection" on, no attachment can be uploaded.
 *
 * The SDK 3.0.0 Tor engine sends a POST only when its body is an `OutgoingContent.ByteArrayContent` (javap, 29 Sep 2026).
 * NIP96Client sends `submitFormWithBinaryData`, a streamed MultiPartFormDataContent, so the Tor engine refuses it
 * ("HTTP POST requires ByteArray body"); it also appends `/api/v2/nip96/upload` to the host, the path of nostr.build,
 * which refuses opaque blobs (500; zc249-20260929/probe_nip96-run1.txt).
 *
 * Wanted: NIP96Client takes the host's full NIP-96 api URL and POSTs a multipart/form-data body built in memory as a
 * ByteArrayContent: one part `file`, filename `upload`, the declared type, the exact bytes, with the NIP-98 header
 * signed for that URL and POST. The same request on both transports. Probed 29 Sep 2026 on nostr.download/n96 and
 * files.sovbit.host/upload (zc249-20260929/probe_nip96_hosts-run1.txt): stored, served back byte-identical.
 */
class Nip96UploadGoesThroughATorLikeEngineTest {
    private val identity = NOSTRIdentity.fromSeed(ByteArray(64) { (0x33 + it).toByte() })
    private val ciphertext = ByteArray(2_048).also { SecureRandom().nextBytes(it) }
    private val sha = sha256Hex(ciphertext)

    /** nostr.download's NIP-96 answer as probed: the url ends in a dot. */
    private val stored = """{"status":"success","nip94_event":{"tags":[["url","https://nostr.download/$sha."],["x","$sha"]]}}"""

    @Test
    fun `a NIP-96 upload is one ByteArray multipart POST to the api url, and its nip94 url is the result`() =
        runBlocking {
            val servers = TorLikeServers { 200 to stored }
            val provider =
                object : HttpClientProvider {
                    override suspend fun create(): HttpClient = HttpClient(servers.engineFactory)
                }

            val outcome = NIP96Client(API_URL, provider).upload(ciphertext, FileUploadManager.ENCRYPTED_BLOB_CONTENT_TYPE, identity, null)

            assertTrue(servers.requests.isNotEmpty(), "the Tor-like engine refused the upload (${servers.attempts}): $outcome")
            assertEquals(listOf("POST $API_URL"), servers.attempts.toList(), "the upload's requests")
            assertUploadRequest(servers.requests.single())
            val success = outcome as? UploadOutcome.Success ?: fail("the upload failed: $outcome")
            assertEquals("https://nostr.download/$sha.", success.url)
            assertEquals(sha, success.sha256)
        }

    /** The same upload through the real HttpClientProviderImpl with Tor on (its retry, JSON, logging, expectSuccess). */
    @Test
    fun `with Tor on, the NIP-96 upload goes through the real provider's Tor client`() =
        runBlocking {
            val servers = TorLikeServers { 200 to stored }

            val outcome =
                NIP96Client(API_URL, servers.provider(torOn = true))
                    .upload(ciphertext, FileUploadManager.ENCRYPTED_BLOB_CONTENT_TYPE, identity, null)

            assertTrue(servers.requests.isNotEmpty(), "the Tor-like engine refused the upload (${servers.attempts}): $outcome")
            assertEquals(listOf("POST $API_URL"), servers.attempts.toList(), "the upload's requests")
            assertUploadRequest(servers.requests.single())
            assertEquals("https://nostr.download/$sha.", (outcome as? UploadOutcome.Success ?: fail("the upload failed: $outcome")).url)
        }

    // --------------------------------------------------------------- harness

    private fun assertUploadRequest(request: TorLikeServers.Recorded) {
        assertTrue(
            OutgoingContent.ByteArrayContent::class.java.isAssignableFrom(request.bodyClass),
            "the body is a ${request.bodyClass.simpleName}, which the Tor engine refuses",
        )
        val type = request.contentType ?: fail("the body has no Content-Type")
        assertEquals("multipart/form-data", "${type.contentType}/${type.contentSubtype}")
        val boundary = type.parameter("boundary") ?: fail("the multipart body has no boundary: $type")

        val part = parts(request.bytes ?: fail("no body bytes"), boundary).singleOrNull() ?: fail("not exactly one part")
        val disposition = part.headers["content-disposition"] ?: fail("the part has no Content-Disposition: ${part.headers}")
        assertTrue(disposition.startsWith("form-data"), disposition)
        assertTrue(Regex("""(^|;)\s*name="?file"?\s*(;|$)""").containsMatchIn(disposition), "the part is not named file: $disposition")
        assertTrue(Regex("""(^|;)\s*filename="upload"\s*(;|$)""").containsMatchIn(disposition), "the filename is not upload: $disposition")
        assertEquals(FileUploadManager.ENCRYPTED_BLOB_CONTENT_TYPE, part.headers["content-type"])
        assertTrue(part.body.contentEquals(ciphertext), "the part does not hold the exact bytes (${part.body.size} bytes)")

        assertNip98(request.headers[HttpHeaders.Authorization] ?: fail("no Authorization header"))
    }

    private fun assertNip98(authorization: String) {
        assertTrue(authorization.startsWith("Nostr "), authorization.take(12))
        val event = Json.parseToJsonElement(String(Base64.getDecoder().decode(authorization.removePrefix("Nostr ")))).jsonObject
        assertEquals(27235, event.getValue("kind").jsonPrimitive.int)
        val tags = event.getValue("tags").jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
        assertEquals(listOf(listOf("u", API_URL), listOf("method", "POST")), tags)
        // The id is NIP-01's hash of the event and the signature is the event key's over it.
        val pubkey = event.getValue("pubkey").jsonPrimitive.content
        val serialized =
            JsonArray(
                listOf(
                    JsonPrimitive(0),
                    JsonPrimitive(pubkey),
                    event.getValue("created_at"),
                    event.getValue("kind"),
                    event.getValue("tags"),
                    event.getValue("content"),
                ),
            ).toString()
        val id = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray())
        assertEquals(id.toHex(), event.getValue("id").jsonPrimitive.content)
        val sig = event.getValue("sig").jsonPrimitive.content
        assertTrue(
            Secp256k1.verifySchnorr(sig.hexToBytes(), id, pubkey.hexToBytes()),
            "the NIP-98 signature does not verify",
        )
    }

    private class Part(
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    /** RFC 7578 multipart/form-data, read byte for byte (ISO-8859-1 keeps every byte). */
    private fun parts(
        bytes: ByteArray,
        boundary: String,
    ): List<Part> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val delimiter = "--$boundary"
        assertTrue(text.startsWith("$delimiter\r\n"), "the body does not start with the boundary")
        assertTrue(
            text.endsWith("\r\n$delimiter--\r\n") || text.endsWith("\r\n$delimiter--"),
            "the body does not end with the closing boundary",
        )
        val inner = text.removePrefix("$delimiter\r\n").substringBeforeLast("\r\n$delimiter--")
        return inner.split("\r\n$delimiter\r\n").map { raw ->
            val head = raw.substringBefore("\r\n\r\n")
            val headers =
                head.split("\r\n").filter { it.isNotBlank() }.associate {
                    it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
                }
            Part(headers, raw.substringAfter("\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        }
    }

    private fun sha256Hex(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val API_URL = "https://nostr.download/n96"
    }
}
