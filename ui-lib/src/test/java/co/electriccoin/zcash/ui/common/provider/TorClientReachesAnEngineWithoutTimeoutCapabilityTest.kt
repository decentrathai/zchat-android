package co.electriccoin.zcash.ui.common.provider

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.retry
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ZC-249 (HIGH) — with "Beta: Tor Protection" on, photos, files and voice notes fail, received files never download,
 * the swap asset list and quotes fail and calls get no TURN credentials.
 *
 * HttpClientProviderImpl.configureHttpClient installs HttpTimeout on both clients. The Tor client is the SDK's
 * `HttpClient(TorHttp) { …; block(this) }`, and SDK 3.0.0's TorHttpEngine declares no capabilities, so Ktor refuses
 * every request of that client before the engine is reached: "Engine doesn't support HttpTimeoutCapability".
 *
 * Wanted: the Tor client installs HttpTimeout only when its engine declares HttpTimeoutCapability (a later SDK); its
 * other plugins (retry, JSON, logging, expectSuccess) stay; the direct OkHttp client keeps HttpTimeout.
 * The engine here is [TorLikeServers]' stand-in for TorHttpEngine, reached through the real provider.
 */
class TorClientReachesAnEngineWithoutTimeoutCapabilityTest {
    @Test
    fun `with Tor on, a GET reaches an engine that declares no timeout capability and returns its answer`() =
        runBlocking {
            val servers = TorLikeServers { 200 to """{"ok":true}""" }
            val text = servers.provider(torOn = true).create().use { it.get("https://tor-fake.invalid/tokens").bodyAsText() }

            assertEquals("""{"ok":true}""", text)
            assertEquals(listOf("GET https://tor-fake.invalid/tokens"), servers.attempts.toList())
            assertNull(servers.requests.single().timeout, "a timeout reached an engine that cannot honour it")
        }

    @Test
    fun `with Tor on, the client keeps retry, JSON, logging and expectSuccess`() =
        runBlocking {
            val servers = TorLikeServers { 404 to """{"error":"not found"}""" }
            servers.provider(torOn = true).create().use { client ->
                assertNotNull(client.pluginOrNull(HttpRequestRetry), "the Tor client lost HttpRequestRetry")
                assertNotNull(client.pluginOrNull(ContentNegotiation), "the Tor client lost ContentNegotiation")
                assertNotNull(client.pluginOrNull(Logging), "the Tor client lost Logging")
                assertNull(client.pluginOrNull(HttpTimeout), "HttpTimeout is installed on an engine without the capability")
                // expectSuccess: a 404 is an exception, as on the direct client (asked once: the retry is not under test).
                assertFailsWith<ClientRequestException> { client.get("https://tor-fake.invalid/missing") { retry { noRetry() } } }
            }
            assertEquals(1, servers.requests.size, "the 404 never reached the engine")
        }

    @Test
    fun `with Tor on over an engine that declares the timeout capability, requests carry the 20 s timeouts`() =
        runBlocking {
            val servers = TorLikeServers(capabilities = setOf(HttpTimeoutCapability)) { 200 to "{}" }
            servers.provider(torOn = true).create().use { client ->
                assertNotNull(client.pluginOrNull(HttpTimeout), "a Tor engine that honours timeouts got none")
                client.get("https://tor-fake.invalid/tokens")
            }
            val timeout = assertNotNull(servers.requests.single().timeout, "the request carried no timeout")
            assertEquals(20_000L, timeout.requestTimeoutMillis)
            assertEquals(15_000L, timeout.connectTimeoutMillis)
            assertEquals(20_000L, timeout.socketTimeoutMillis)
        }

    /** Control: the direct client is unchanged. Building it sends nothing. */
    @Test
    fun `control - with Tor off, the direct client keeps HttpTimeout and never builds a Tor client`() =
        runBlocking {
            val servers = TorLikeServers()
            servers.provider(torOn = false).create().use { client ->
                assertNotNull(client.pluginOrNull(HttpTimeout), "the direct client lost HttpTimeout")
                assertNotNull(client.pluginOrNull(HttpRequestRetry), "the direct client lost HttpRequestRetry")
            }
            assertEquals(0, servers.enginesBuilt.get(), "Tor off built a Tor client")
        }
}
