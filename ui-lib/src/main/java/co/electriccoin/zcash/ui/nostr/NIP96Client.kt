package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.retry
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/**
 * NIP-96 file upload client.
 *
 * Uploads files via a multipart/form-data POST to the host's NIP-96 api URL ([apiUrl], the `api_url` of its
 * `/.well-known/nostr/nip96.json`, given in full) with a NIP-98 auth header (kind 27235, Schnorr-signed,
 * base64-encoded) signed for that URL and POST.
 *
 * ZC-249: the body is built in memory and sent as one ByteArrayContent, the only POST body the Zcash SDK 3.0.0 Tor
 * engine sends (a streamed submitFormWithBinaryData body is refused: "HTTP POST requires ByteArray body"). The same
 * request goes over both transports. No progress is reported (as before).
 */
class NIP96Client(
    private val apiUrl: String,
    private val httpClientProvider: HttpClientProvider
) : FileUploadClient {
    @Suppress("TooGenericExceptionCaught", "UnusedParameter")
    override suspend fun upload(
        data: ByteArray,
        mimeType: String,
        identity: NOSTRIdentity,
        onProgress: ((Float) -> Unit)?,
    ): UploadOutcome =
        withContext(Dispatchers.IO) {
            try {
                val authHeader = identity.signNIP98Event(apiUrl, "POST")
                val body = multipartBody(data, mimeType)

                // Capture HttpResponse first so non-2xx codes surface as status+body, not as a
                // generic "no transformation found" deserialization error.
                val (status, bodyText) =
                    httpClientProvider.create().use { client: HttpClient ->
                        val response: HttpResponse =
                            client.post(apiUrl) {
                                // R10A-3 (F-R9P-5): the host list in FileUploadManager is the upload's retry; see BlossomClient.
                                retry { noRetry() }
                                header(HttpHeaders.Authorization, "Nostr $authHeader")
                                setBody(body)
                            }
                        response.status to response.bodyAsText()
                    }

                if (!status.isSuccess()) {
                    return@withContext UploadOutcome.Failure(
                        error = "HTTP ${status.value} ${status.description}: ${bodyText.take(200)}",
                        serverUrl = apiUrl
                    )
                }

                parseNip96Response(bodyText, data)
            } catch (e: Exception) {
                UploadOutcome.Failure(
                    error = "${e.javaClass.simpleName}: ${e.message ?: "no message"}",
                    serverUrl = apiUrl
                )
            }
        }

    /**
     * RFC 7578 multipart/form-data with one part: name `file`, filename `upload`, Content-Type [mimeType], [data].
     * The boundary holds 128 random bits, so it never occurs in the (encrypted) data.
     */
    private fun multipartBody(
        data: ByteArray,
        mimeType: String
    ): ByteArrayContent {
        val random = ByteArray(BOUNDARY_RANDOM_BYTES).also { SecureRandom().nextBytes(it) }
        val boundary = "zchat-" + random.joinToString("") { "%02x".format(it) }
        val head =
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"upload\"\r\n" +
                "Content-Type: $mimeType\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val bytes = head.toByteArray(Charsets.US_ASCII) + data + tail.toByteArray(Charsets.US_ASCII)
        return ByteArrayContent(bytes, ContentType.MultiPart.FormData.withParameter("boundary", boundary))
    }

    private fun parseNip96Response(
        responseText: String,
        data: ByteArray
    ): UploadOutcome {
        val json = Json { ignoreUnknownKeys = true }
        val response = json.decodeFromString<Nip96Response>(responseText)

        val url =
            response.nip94Event
                ?.tags
                ?.firstOrNull { it.size >= 2 && it[0] == "url" }
                ?.get(1)

        return if (url != null) {
            UploadOutcome.Success(url = url, sha256 = FileUploadManager.sha256Hex(data))
        } else {
            UploadOutcome.Failure(
                error = response.message ?: "No URL in NIP-96 response",
                serverUrl = apiUrl
            )
        }
    }
}

private const val BOUNDARY_RANDOM_BYTES = 16

/**
 * NIP-96 JSON response envelope.
 */
@Serializable
private data class Nip96Response(
    val status: String? = null,
    val message: String? = null,
    // The NIP-96 response key is snake_case `nip94_event`; without this @SerialName the (ignoreUnknownKeys)
    // parse silently leaves this null on a SUCCESSFUL upload and the file is wrongly reported as failed.
    @SerialName("nip94_event") val nip94Event: Nip94Event? = null
) {
    @Serializable
    data class Nip94Event(
        val tags: List<List<String>>? = null
    )
}
