package co.electriccoin.zcash.ui.nostr

import android.util.Log
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import java.security.MessageDigest

/**
 * Orchestrates file uploads across Blossom and NIP-96 servers with fallback.
 *
 * With Tor off it tries the Blossom servers first (a PUT of the raw bytes), then the NIP-96 servers (a multipart POST).
 * ZC-249: with Tor on it tries only the NIP-96 servers, since the Zcash SDK's Tor engine never sends a PUT; nothing
 * falls back to clearnet.
 */
class FileUploadManager(
    private val identity: NOSTRIdentity,
    private val httpClientProvider: HttpClientProvider,
    /**
     * SR-A (R-A1, C-SRA-5) — a url this upload may hand back. A Shielded attachment was priced for a memo count; a host
     * whose url would change it is skipped like a url off the allow-list, and the next host is tried. Tunnel and Open
     * take every allowed url.
     */
    private val acceptUrl: (String) -> Boolean = { true },
) {
    // R8-fixup F3 (REVIEW_R8 F3, device D-F5): every attachment is uploaded as AES ciphertext, which these servers now
    // read and judge by its bytes. Probed on 24-25 Sep 2026 (round8 R8-fixup/F3/probe-*.txt, random blobs up to 1.5 MB,
    // deleted again): the first four store an opaque blob declared application/octet-stream and serve it back;
    // blossom.primal.net (415), blossom.band and blossom.nostr.build (400 / 415) refuse any encrypted blob. Those three
    // stay last, in case they accept opaque blobs again. Every host here must be on ZFILEMessage's inbound allow-list.
    val blossomServers: List<String> =
        listOf(
            "https://nostr.download",
            "https://blossom.yakihonne.com",
            "https://cdn.hzrd149.com",
            "https://files.sovbit.host",
            "https://blossom.primal.net",
            "https://blossom.band",
            "https://blossom.nostr.build"
        )

    // ZC-249: full NIP-96 api URLs (each host's /.well-known/nostr/nip96.json api_url), no live discovery. Probed on
    // 29 Sep 2026 (zc249-20260929/probe_nip96*.txt, 2 KiB random blobs from a throwaway key): nostr.download and
    // files.sovbit.host store an opaque blob declared application/octet-stream and serve it back byte-identical;
    // nostr.build answers 500 to one and re-encodes a real JPEG, so it can never store an attachment and left this
    // list. It stays on the inbound allow-list, so files already sent there keep loading. These are the only hosts
    // an upload with Tor on can reach.
    val nip96Servers: List<String> =
        listOf(
            "https://nostr.download/n96",
            "https://files.sovbit.host/upload"
        )

    /**
     * Upload file data. With Tor off it tries the Blossom servers first (one of them is reliable and ~500ms), then
     * the NIP-96 servers as a last fallback; with Tor on (ZC-249) only the NIP-96 servers, in order. Reports
     * streaming progress (0..1) to [onProgress] when the underlying client emits byte-level callbacks (Blossom only).
     *
     * R9-4: any refusal moves on to the next server, including a size refusal (413 Payload Too Large, or 400 with a
     * size message) from a server whose limit is below the file flow's 25 MB. A server that stores the blob but answers
     * with a url on a host the recipient's ZFILE allow-list drops is skipped too: the recipient would silently never
     * get the file.
     */
    suspend fun upload(
        data: ByteArray,
        mimeType: String,
        onProgress: ((Float) -> Unit)? = null,
    ): UploadOutcome {
        // Read once per upload: the setting HttpClientProviderImpl reads to pick the Tor or the direct client.
        val attempts: List<Pair<String, FileUploadClient>> =
            if (httpClientProvider.usesTor()) {
                nip96Servers.map { it to NIP96Client(it, httpClientProvider) }
            } else {
                blossomServers.map { it to BlossomClient(it, httpClientProvider) } +
                    nip96Servers.map { it to NIP96Client(it, httpClientProvider) }
            }
        val perServerErrors = mutableListOf<String>()
        for ((serverUrl, client) in attempts) {
            val protocol = if (client is BlossomClient) "Blossom" else "NIP-96"
            when (val result = client.upload(data, mimeType, identity, onProgress)) {
                is UploadOutcome.Success ->
                    if (!ZFILEMessage.isAllowedMediaUrl(result.url)) {
                        Log.w(TAG, "$protocol $serverUrl returned a url off the allow-list")
                        perServerErrors += "${shortName(serverUrl)}: $URL_NOT_ALLOWED"
                    } else if (!acceptUrl(result.url)) {
                        // SR-A: the link would need another memo count than the price the user paid for.
                        Log.w(TAG, "$protocol $serverUrl returned a url that would change the quoted price")
                        perServerErrors += "${shortName(serverUrl)}: $URL_CHANGES_PRICE"
                    } else {
                        return result
                    }
                is UploadOutcome.Failure -> {
                    Log.w(TAG, "$protocol $serverUrl failed: ${result.error}")
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
        private const val URL_NOT_ALLOWED = "returned a url the recipient cannot load"
        private const val URL_CHANGES_PRICE = "returned a url that would change the quoted price"

        /**
         * R8-fixup F3 — the type every attachment is uploaded as: its body is AES ciphertext, never an image. Servers
         * that read the body refuse a blob declared as an image whose bytes are not one.
         */
        const val ENCRYPTED_BLOB_CONTENT_TYPE = "application/octet-stream"

        /**
         * Compute the SHA-256 hex digest of the given data.
         */
        fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).joinToString("") { "%02x".format(it) }
        }
    }
}
