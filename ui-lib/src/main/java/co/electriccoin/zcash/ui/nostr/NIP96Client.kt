package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * NIP-96 file upload client.
 *
 * Uploads files via multipart POST to `{serverUrl}/api/v2/media` with a
 * NIP-98 auth header (kind 27235, Schnorr-signed, base64-encoded).
 */
class NIP96Client(
    private val serverUrl: String,
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
                // Endpoint discovered from /.well-known/nostr/nip96.json (api_url). nostr.build
                // moved off /api/v2/media; the new path is /api/v2/nip96/upload. Stale path
                // returns HTTP 405 Method Not Allowed.
                val uploadUrl = "$serverUrl/api/v2/nip96/upload"
                val authHeader = identity.signNIP98Event(uploadUrl, "POST")

                // Capture HttpResponse first so non-2xx codes surface as status+body, not as a
                // generic "no transformation found" deserialization error.
                val (status, bodyText) =
                    httpClientProvider.create().use { client: HttpClient ->
                        val response: HttpResponse = client.submitFormWithBinaryData(
                            url = uploadUrl,
                            formData = formData {
                                append(
                                    key = "file",
                                    value = data,
                                    headers = Headers.build {
                                        append(HttpHeaders.ContentType, mimeType)
                                        append(HttpHeaders.ContentDisposition, "filename=\"upload\"")
                                    }
                                )
                            }
                        ) {
                            header(HttpHeaders.Authorization, "Nostr $authHeader")
                        }
                        response.status to response.bodyAsText()
                    }

                if (!status.isSuccess()) {
                    return@withContext UploadOutcome.Failure(
                        error = "HTTP ${status.value} ${status.description}: ${bodyText.take(200)}",
                        serverUrl = serverUrl
                    )
                }

                parseNip96Response(bodyText, data)
            } catch (e: Exception) {
                UploadOutcome.Failure(
                    error = "${e.javaClass.simpleName}: ${e.message ?: "no message"}",
                    serverUrl = serverUrl
                )
            }
        }

    private fun parseNip96Response(
        responseText: String,
        data: ByteArray
    ): UploadOutcome {
        val json = Json { ignoreUnknownKeys = true }
        val response = json.decodeFromString<Nip96Response>(responseText)

        val url = response.nip94Event?.tags
            ?.firstOrNull { it.size >= 2 && it[0] == "url" }
            ?.get(1)

        return if (url != null) {
            UploadOutcome.Success(url = url, sha256 = FileUploadManager.sha256Hex(data))
        } else {
            UploadOutcome.Failure(
                error = response.message ?: "No URL in NIP-96 response",
                serverUrl = serverUrl
            )
        }
    }
}

/**
 * NIP-96 JSON response envelope.
 */
@Serializable
private data class Nip96Response(
    val status: String? = null,
    val message: String? = null,
    val nip94Event: Nip94Event? = null
) {
    @Serializable
    data class Nip94Event(
        val tags: List<List<String>>? = null
    )
}
