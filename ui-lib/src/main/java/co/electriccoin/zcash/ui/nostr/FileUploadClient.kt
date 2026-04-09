package co.electriccoin.zcash.ui.nostr

/**
 * Outcome of a file upload attempt to a NOSTR media server.
 */
sealed class UploadOutcome {
    data class Success(val url: String, val sha256: String) : UploadOutcome()

    data class Failure(val error: String, val serverUrl: String) : UploadOutcome()
}

/**
 * Common interface for NIP-96 and Blossom file upload protocols.
 */
interface FileUploadClient {
    suspend fun upload(
        data: ByteArray,
        mimeType: String,
        identity: NOSTRIdentity
    ): UploadOutcome
}
