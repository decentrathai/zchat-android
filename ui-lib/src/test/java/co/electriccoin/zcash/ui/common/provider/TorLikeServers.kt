package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-249 — offline stand-ins for the Zcash SDK 3.0.0 Tor transport, for the Tor tests of HttpClientProviderImpl,
 * NIP96Client and FileUploadManager.
 *
 * [engineFactory] builds an engine that behaves like the SDK's `TorHttpEngine` (javap and a desktop-JVM run of the
 * real engine, 29 Sep 2026, zc249-20260929/RESEARCH.md):
 * - it declares no capabilities unless [capabilities] says so, so Ktor refuses, before the engine is reached, any
 *   request whose client installed HttpTimeout;
 * - a GET must carry no body; a POST must carry an `OutgoingContent.ByteArrayContent`, whose bytes, headers and
 *   Content-Type are sent; any other method is refused ("UnsupportedHttpMethodOverTor").
 *
 * [synchronizerProvider] answers `Synchronizer.getTorHttpClient(block)` as the SDK does: a new client over a new
 * engine, `HttpClient(TorHttp) { engine { … }; block(this) }`. [provider] is the real HttpClientProviderImpl over it.
 *
 * Every request that reached an engine is in [attempts] ("METHOD url"), a refused one too; the accepted ones are in
 * [requests]. [respond] answers the accepted ones (status, JSON body).
 */
internal class TorLikeServers(
    private val capabilities: Set<HttpClientEngineCapability<*>> = emptySet(),
    private val respond: (Recorded) -> Pair<Int, String> = { 200 to "{}" },
) {
    class Recorded(
        val method: String,
        val url: String,
        val headers: Headers,
        val contentType: ContentType?,
        val bodyClass: Class<*>,
        val bytes: ByteArray?,
        val timeout: HttpTimeoutConfig?,
    )

    val attempts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())
    val enginesBuilt = AtomicInteger()

    private inner class Engine : HttpClientEngineBase("tor-like-fake") {
        override val config: HttpClientEngineConfig = HttpClientEngineConfig()

        override val supportedCapabilities: Set<HttpClientEngineCapability<*>>
            get() = capabilities

        @OptIn(InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val body = data.body
            attempts += "${data.method.value} ${data.url}"
            val bytes =
                when (data.method) {
                    HttpMethod.Get -> {
                        require(body is OutgoingContent.NoContent) { "HTTP GET must not have a body" }
                        null
                    }
                    HttpMethod.Post -> {
                        require(body is OutgoingContent.ByteArrayContent) { "HTTP POST requires ByteArray body" }
                        body.bytes()
                    }
                    else -> throw UnsupportedOperationException("UnsupportedHttpMethodOverTor: ${data.method.value}")
                }
            val recorded =
                Recorded(
                    method = data.method.value,
                    url = data.url.toString(),
                    headers = data.headers,
                    contentType = body.contentType,
                    bodyClass = body.javaClass,
                    bytes = bytes,
                    timeout = data.getCapabilityOrNull(HttpTimeoutCapability),
                )
            requests += recorded
            val (code, text) = respond(recorded)
            return HttpResponseData(
                HttpStatusCode.fromValue(code),
                GMTDate(),
                headersOf(HttpHeaders.ContentType, "application/json"),
                HttpProtocolVersion.HTTP_1_1,
                ByteReadChannel(text.toByteArray()),
                callContext(),
            )
        }
    }

    val engineFactory: HttpClientEngineFactory<HttpClientEngineConfig> =
        object : HttpClientEngineFactory<HttpClientEngineConfig> {
            override fun create(block: HttpClientEngineConfig.() -> Unit): HttpClientEngine {
                enginesBuilt.incrementAndGet()
                return Engine()
            }
        }

    private val synchronizer: Synchronizer =
        Proxy.newProxyInstance(
            Synchronizer::class.java.classLoader,
            arrayOf(Synchronizer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "TorLikeSynchronizer"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "getTorHttpClient" -> {
                    @Suppress("UNCHECKED_CAST")
                    val block = args!![0] as (HttpClientConfig<HttpClientEngineConfig>) -> Unit
                    HttpClient(engineFactory) { block(this) }
                }
                else -> throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
            }
        } as Synchronizer

    val synchronizerProvider: SynchronizerProvider =
        object : SynchronizerProvider {
            override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
            override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(this@TorLikeServers.synchronizer)

            override suspend fun getSynchronizer(): Synchronizer = this@TorLikeServers.synchronizer

            override fun resetSynchronizer() = Unit
        }

    /** The real provider, with "Beta: Tor Protection" [torOn] (the setting HttpClientProviderImpl reads). */
    fun provider(torOn: Boolean): HttpClientProviderImpl = HttpClientProviderImpl(synchronizerProvider, TorSetting(torOn))
}

/** The "Beta: Tor Protection" setting, fixed. */
internal class TorSetting(
    private val on: Boolean?,
) : IsTorEnabledStorageProvider {
    override suspend fun get(): Boolean? = on

    override suspend fun store(amount: Boolean) = throw UnsupportedOperationException("read-only fixture")

    override fun observe(): Flow<Boolean?> = flowOf(on)

    override suspend fun clear() = throw UnsupportedOperationException("read-only fixture")
}
