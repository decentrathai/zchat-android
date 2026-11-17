package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.retry
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Blossom (BUD-02) file upload client.
 *
 * Uploads files via PUT to `{serverUrl}/upload` with raw bytes in the body
 * and a kind-24242 Nostr auth event in the Authorization header.
 */
class BlossomClient(
    private val serverUrl: String,
    private val httpClientProvider: HttpClientProvider
) : FileUploadClient {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun upload(
        data: ByteArray,
        mimeType: String,
        identity: NOSTRIdentity,
        onProgress: ((Float) -> Unit)?,
    ): UploadOutcome =
        withContext(Dispatchers.IO) {
            try {
                val sha256Hex = FileUploadManager.sha256Hex(data)
                val authHeader = identity.signBlossomAuthEvent(sha256Hex, data.size.toLong())

                val (status, bodyText) =
                    httpClientProvider.create().use { client: HttpClient ->
                        val response: HttpResponse = client.put("$serverUrl/upload") {
                            // R10A-3 (F-R9P-5): FileUploadManager's host list is the upload's retry. The shared client's
                            // HttpRequestRetry would re-try each host's failure 4 times with backoff (~17 s a host offline,
                            // ~2 min 18 s in all) and re-send the whole encrypted body on a timeout or 5xx.
                            retry { noRetry() }
                            header(HttpHeaders.Authorization, "Nostr $authHeader")
                            contentType(ContentType.parse(mimeType))
                            setBody(data)
                            if (onProgress != null) {
                                onUpload { bytesSentTotal, contentLength ->
                                    val total = contentLength ?: data.size.toLong()
                                    if (total > 0) {
                                        onProgress(bytesSentTotal.toFloat() / total.toFloat())
                                    }
                                }
                            }
                        }
                        response.status to response.bodyAsText()
                    }

                if (!status.isSuccess()) {
                    return@withContext UploadOutcome.Failure(
                        error = "HTTP ${status.value} ${status.description}: ${bodyText.take(200)}",
                        serverUrl = serverUrl
                    )
                }

                parseBlossomResponse(bodyText, sha256Hex)
            } catch (e: Exception) {
                UploadOutcome.Failure(
                    error = "${e.javaClass.simpleName}: ${e.message ?: "no message"}",
                    serverUrl = serverUrl
                )
            }
        }

    private fun parseBlossomResponse(
        responseText: String,
        sha256Hex: String
    ): UploadOutcome {
        val json = Json { ignoreUnknownKeys = true }
        val response = json.decodeFromString<BlossomResponse>(responseText)

        val url = response.url ?: "$serverUrl/$sha256Hex"
        return UploadOutcome.Success(url = url, sha256 = sha256Hex)
    }
}

/**
 * Blossom JSON response envelope.
 */
@Serializable
private data class BlossomResponse(
    val url: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
    val type: String? = null
)
