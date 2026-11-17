package co.electriccoin.zcash.ui.common.provider

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface HttpClientProvider {
    suspend fun create(): HttpClient

    /**
     * ZC-249: true when [create] gives a client over the Zcash SDK's Tor engine ("Beta: Tor Protection" on). That
     * engine sends only a GET without a body and a POST whose body is a ByteArrayContent, so a caller with another
     * choice (FileUploadManager: NIP-96 POST instead of Blossom PUT) makes it here. Nothing falls back to clearnet.
     */
    suspend fun usesTor(): Boolean = false
}

class HttpClientProviderImpl(
    private val synchronizerProvider: SynchronizerProvider,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider
) : HttpClientProvider {
    override suspend fun create(): HttpClient = if (usesTor()) createTor() else createDirect()

    override suspend fun usesTor(): Boolean = isTorEnabledStorageProvider.get() == true

    /**
     * ZC-249: Ktor refuses every request of a client with HttpTimeout whose engine does not declare
     * HttpTimeoutCapability ("Engine doesn't support HttpTimeoutCapability"), before the engine is reached. The Zcash
     * SDK 3.0.0 TorHttpEngine declares no capabilities, so with Tor on every request failed: attachments up and down,
     * swap assets and quotes, TURN credentials. HttpClientConfig cannot see the engine, so the Tor client is built
     * without HttpTimeout and gets it only when its engine declares the capability (a later SDK). The returned client
     * then shares, and on close closes, the engine; the first client is left to the garbage collector unclosed, since
     * closing it would close that engine too. A stalled Tor request has no Ktor timeout (register: ZC-249 follow-up).
     */
    private suspend fun createTor(): HttpClient {
        val client =
            synchronizerProvider
                .getSynchronizer()
                .getTorHttpClient {
                    configureHttpClient(withTimeout = false)
                }
        return if (HttpTimeoutCapability in client.engine.supportedCapabilities) {
            client.config { installTimeouts() }
        } else {
            client
        }
    }

    private fun createDirect() =
        HttpClient(OkHttp) {
            configureHttpClient(withTimeout = true)
        }

    // Without explicit timeouts a stale/half-open connection (observed reaching the 1Click
    // Cloudflare endpoint, where the app's okhttp request hung indefinitely while curl to the
    // same host succeeded) never fails, leaving the swap-asset list stuck on "Loading".
    // Bounded timeouts turn that into a retryable failure so HttpRequestRetry re-attempts.
    @Suppress("MagicNumber")
    private fun HttpClientConfig<*>.installTimeouts() {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
    }

    @Suppress("MagicNumber")
    private fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureHttpClient(withTimeout: Boolean) {
        // Auto-retry transient failures on EVERY swap call, over BOTH transports. This was previously
        // installed only on the direct (non-Tor) client, so when Tor is enabled — which is the default
        // for the onboarding "I Need ZEC" / restore flow (RestoreWalletUseCase enableTor=true) — the
        // very first /tokens catalog fetch had NO retry. A single transient 5xx or cold Tor-circuit
        // connection error surfaced immediately as the "general error + Retry" state; tapping Retry
        // worked only because the circuit/endpoint was warm by then. Sharing the retry across the Tor
        // path makes the first asset-catalog load succeed without a manual Retry. retryOnExceptionOrServerErrors
        // covers connection/timeout exceptions (incl. HttpTimeout where installed) and HTTP 5xx.
        install(HttpRequestRetry) {
            maxRetries = 4
            retryOnExceptionOrServerErrors(4)
            exponentialDelay()
        }
        if (withTimeout) installTimeouts()
        install(ContentNegotiation) {
            // Tolerate fields the external APIs add over time (e.g. 1Click's `priceUpdatedAt`/
            // `contractAddress` on /tokens). The default Json rejects unknown keys, and the
            // per-class @JsonIgnoreUnknownKeys annotation is not reliably honored under the
            // current serialization-plugin/runtime combo — an empty swap-asset list was the symptom.
            json(
                Json {
                    ignoreUnknownKeys = true
                }
            )
        }
        install(Logging) {
            logger = KtorLogger()
            // HEADERS, not ALL: LogLevel.ALL makes the Logging plugin observe/read the full
            // response body, which races ContentNegotiation for the same body channel and can
            // hang the call (observed: swap /tokens request logged but never completing). It also
            // avoids logging full request/response bodies (incl. API payloads) to logcat.
            level = LogLevel.HEADERS
            // Redact BOTH the standard Authorization header and CoinMarketCap's API-key header
            // (CMCApiProvider sends X-CMC_PRO_API_KEY) so the paid key is never written to logcat.
            sanitizeHeader { header ->
                header == HttpHeaders.Authorization ||
                    header.equals("X-CMC_PRO_API_KEY", ignoreCase = true)
            }
        }
        expectSuccess = true
    }
}

private class KtorLogger : Logger {
    override fun log(message: String) {
        Log.d("HttpClient", message)
    }
}
