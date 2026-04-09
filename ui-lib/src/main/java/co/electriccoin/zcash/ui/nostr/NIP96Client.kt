package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
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

    @Suppress("TooGenericExceptionCaught")
    override suspend fun upload(
        data: ByteArray,
        mimeType: String,
        identity: NOSTRIdentity
    ): UploadOutcome =
        withContext(Dispatchers.IO) {
            try {
                val uploadUrl = "$serverUrl/api/v2/media"
                val authHeader = identity.signNIP98Event(uploadUrl, "POST")

                val responseText: String =
                    httpClientProvider.create().use { client: HttpClient ->
                        client.submitFormWithBinaryData(
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
                        }.body()
                    }

                parseNip96Response(responseText, data)
            } catch (e: Exception) {
                UploadOutcome.Failure(
                    error = e.message ?: "Unknown NIP-96 upload error",
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
