package co.electriccoin.zcash.ui.nostr

import android.util.Log
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import java.security.MessageDigest

/**
 * Orchestrates file uploads across NIP-96 and Blossom servers with fallback.
 *
 * Tries NIP-96 servers first (multipart upload, wider adoption), then falls back
 * to Blossom servers (simpler PUT-based protocol).
 */
class FileUploadManager(
    private val identity: NOSTRIdentity,
    private val httpClientProvider: HttpClientProvider
) {
    // Order matters for first-byte latency: blossom.primal.net consistently succeeds in
    // ~500ms while nostr.build has been returning HTTP 500 with a multi-second hang. Try
    // the working server first and treat the slow/broken one as a last-resort fallback.
    val blossomServers: List<String> = listOf(
        "https://blossom.primal.net",
        "https://blossom.band",
        "https://blossom.nostr.build"
    )

    val nip96Servers: List<String> = listOf(
        "https://nostr.build"
    )

    /**
     * Upload file data. Tries Blossom servers first (one of them is reliable and ~500ms),
     * then NIP-96 servers as a last fallback. Reports streaming progress (0..1) to
     * [onProgress] when the underlying client emits byte-level callbacks.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun upload(
        data: ByteArray,
        mimeType: String,
        onProgress: ((Float) -> Unit)? = null,
    ): UploadOutcome {
        val perServerErrors = mutableListOf<String>()
        for (serverUrl in blossomServers) {
            val result = BlossomClient(serverUrl, httpClientProvider).upload(data, mimeType, identity, onProgress)
            when (result) {
                is UploadOutcome.Success -> return result
                is UploadOutcome.Failure -> {
                    Log.w(TAG, "Blossom $serverUrl failed: ${result.error}")
                    perServerErrors += "${shortName(serverUrl)}: ${result.error}"
                }
            }
        }
        for (serverUrl in nip96Servers) {
            val result = NIP96Client(serverUrl, httpClientProvider).upload(data, mimeType, identity, onProgress)
            when (result) {
                is UploadOutcome.Success -> return result
                is UploadOutcome.Failure -> {
                    Log.w(TAG, "NIP-96 $serverUrl failed: ${result.error}")
                    perServerErrors += "${shortName(serverUrl)}: ${result.error}"
                }
            }
        }
        return UploadOutcome.Failure(
            error = "All upload servers failed (${perServerErrors.joinToString("; ")})",
            serverUrl = "all"
        )
    }

    private fun shortName(serverUrl: String): String =
        serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')

    companion object {
        private const val TAG = "ZCHAT_FILE"

        /**
         * Compute the SHA-256 hex digest of the given data.
         */
        fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).joinToString("") { "%02x".format(it) }
        }
    }
}
