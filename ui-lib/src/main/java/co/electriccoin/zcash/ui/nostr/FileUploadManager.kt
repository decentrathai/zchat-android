package co.electriccoin.zcash.ui.nostr

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
    val nip96Servers: List<String> = listOf(
        "https://nostr.build",
        "https://void.cat"
    )

    val blossomServers: List<String> = listOf(
        "https://blossom.band",
        "https://blossom.nostr.build"
    )

    /**
     * Upload file data trying NIP-96 servers first, then Blossom servers.
     *
     * @return [UploadOutcome.Success] from the first server that succeeds,
     *         or [UploadOutcome.Failure] if all servers fail.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun upload(
        data: ByteArray,
        mimeType: String
    ): UploadOutcome {
        // Try NIP-96 servers first
        for (serverUrl in nip96Servers) {
            val result = NIP96Client(serverUrl, httpClientProvider).upload(data, mimeType, identity)
            if (result is UploadOutcome.Success) return result
        }
        // Fallback to Blossom servers
        for (serverUrl in blossomServers) {
            val result = BlossomClient(serverUrl, httpClientProvider).upload(data, mimeType, identity)
            if (result is UploadOutcome.Success) return result
        }
        return UploadOutcome.Failure(error = "All upload servers failed", serverUrl = "all")
    }

    companion object {
        /**
         * Compute the SHA-256 hex digest of the given data.
         */
        fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).joinToString("") { "%02x".format(it) }
        }
    }
}
