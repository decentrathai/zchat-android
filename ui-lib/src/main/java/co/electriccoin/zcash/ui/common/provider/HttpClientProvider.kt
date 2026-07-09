package co.electriccoin.zcash.ui.common.provider

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface HttpClientProvider {
    suspend fun create(): HttpClient
}

class HttpClientProviderImpl(
    private val synchronizerProvider: SynchronizerProvider,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider
) : HttpClientProvider {
    override suspend fun create(): HttpClient =
        if (isTorEnabledStorageProvider.get() == true) createTor() else createDirect()

    private suspend fun createTor() =
        synchronizerProvider
            .getSynchronizer()
            .getTorHttpClient {
                configureHttpClient()
            }

    private fun createDirect() =
        HttpClient(OkHttp) {
            configureHttpClient()
        }

    @Suppress("MagicNumber")
    private fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureHttpClient() {
        // Auto-retry transient failures on EVERY swap call, over BOTH transports. This was previously
        // installed only on the direct (non-Tor) client, so when Tor is enabled — which is the default
        // for the onboarding "I Need ZEC" / restore flow (RestoreWalletUseCase enableTor=true) — the
        // very first /tokens catalog fetch had NO retry. A single transient 5xx or cold Tor-circuit
        // connection error surfaced immediately as the "general error + Retry" state; tapping Retry
        // worked only because the circuit/endpoint was warm by then. Sharing the retry across the Tor
        // path makes the first asset-catalog load succeed without a manual Retry. retryOnExceptionOrServerErrors
        // covers connection/timeout exceptions (incl. the HttpTimeout below) and HTTP 5xx.
        install(HttpRequestRetry) {
            maxRetries = 4
            retryOnExceptionOrServerErrors(4)
            exponentialDelay()
        }
        // Without explicit timeouts a stale/half-open connection (observed reaching the 1Click
        // Cloudflare endpoint, where the app's okhttp request hung indefinitely while curl to the
        // same host succeeded) never fails, leaving the swap-asset list stuck on "Loading".
        // Bounded timeouts turn that into a retryable failure so HttpRequestRetry re-attempts.
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
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
