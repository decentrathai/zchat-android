package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.ai.AiApiClient.Companion.NETWORK_UNREACHABLE_MESSAGE
import co.electriccoin.zcash.ui.screen.ai.AiApiClient.Companion.SERVICE_UNAVAILABLE_MESSAGE
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONException
import org.junit.Test
import java.io.File
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Review follow-up, 2026-09-11: the readable-errors fix covered only non-2xx answers. A request that got
 * no answer (no connection, DNS, a timeout whose message quotes the request URL) or a 2xx that is not the
 * service's JSON (a proxy's HTML page, which fails in the parser with the page quoted) still reached the
 * banner and the Top-up dialog as the exception's own text.
 *
 * Every route now answers with one fixed sentence the UI localizes: "could not reach" for a transport
 * failure, "temporarily unavailable" for an answer the service never sends. All hosts and tokens are
 * synthetic.
 */
class AiTransportFailureTest {
    @Test
    fun the_exception_text_never_reaches_the_user() {
        val timeout = HttpRequestTimeoutException("https://ai.synthetic.invalid/api/v1/ai/chat", 180_000L, null)

        assertEquals(NETWORK_UNREACHABLE_MESSAGE, AiApiClient.readableTransportFailure(timeout))
        assertEquals(NETWORK_UNREACHABLE_MESSAGE, AiApiClient.readableTransportFailure(UnknownHostException(HOST)))
        assertEquals(NETWORK_UNREACHABLE_MESSAGE, AiApiClient.readableTransportFailure(ConnectException("refused")))
        assertEquals(
            SERVICE_UNAVAILABLE_MESSAGE,
            AiApiClient.readableTransportFailure(JSONException("Value <!DOCTYPE of type java.lang.String")),
        )
        assertEquals(
            SERVICE_UNAVAILABLE_MESSAGE,
            AiApiClient.readableTransportFailure(IllegalStateException("synthetic")),
        )
    }

    @Test
    fun a_2xx_html_page_on_every_route_reads_as_an_outage() =
        runBlocking {
            val server = MockWebServer().apply { start() }
            try {
                val client = clientFor(server.url("/").toString().trimEnd('/'))
                repeat(ROUTES) { server.enqueue(html200()) }

                val signIn = AiTabAccessFixture.access(AiTabAccessFixture.bareStore(), client).open()
                assertEquals(null to SERVICE_UNAVAILABLE_MESSAGE, signIn)
                assertEquals(BalanceResult.Failure(SERVICE_UNAVAILABLE_MESSAGE), client.balance(TOKEN))
                assertEquals(ModelsResult.Failure(SERVICE_UNAVAILABLE_MESSAGE), client.listModels(TOKEN))
                assertEquals(ChatResult.Failure(SERVICE_UNAVAILABLE_MESSAGE), client.chat(TOKEN, MODEL, HELLO))
                assertEquals(ImageResult.Failure(SERVICE_UNAVAILABLE_MESSAGE), client.image(TOKEN, MODEL, PROMPT))
                assertEquals(TopupAddressResult.Failure(SERVICE_UNAVAILABLE_MESSAGE), client.topupAddress(TOKEN))
                assertEquals(TopupHistoryResult.Failure(SERVICE_UNAVAILABLE_MESSAGE), client.topupHistory(TOKEN))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun no_connection_on_every_route_says_so_in_one_sentence() =
        runBlocking {
            // A port that was just listening and now refuses: a real transport failure, no network needed.
            val server = MockWebServer().apply { start() }
            val closedUrl = server.url("/").toString().trimEnd('/')
            server.shutdown()
            val client = clientFor(closedUrl)

            val signIn = AiTabAccessFixture.access(AiTabAccessFixture.bareStore(), client).open()
            assertEquals(null to NETWORK_UNREACHABLE_MESSAGE, signIn)
            assertEquals(BalanceResult.Failure(NETWORK_UNREACHABLE_MESSAGE), client.balance(TOKEN))
            assertEquals(ModelsResult.Failure(NETWORK_UNREACHABLE_MESSAGE), client.listModels(TOKEN))
            assertEquals(ChatResult.Failure(NETWORK_UNREACHABLE_MESSAGE), client.chat(TOKEN, MODEL, HELLO))
            assertEquals(ImageResult.Failure(NETWORK_UNREACHABLE_MESSAGE), client.image(TOKEN, MODEL, PROMPT))
            assertEquals(TopupAddressResult.Failure(NETWORK_UNREACHABLE_MESSAGE), client.topupAddress(TOKEN))
            assertEquals(TopupHistoryResult.Failure(NETWORK_UNREACHABLE_MESSAGE), client.topupHistory(TOKEN))
        }

    @Test
    fun the_ui_localizes_the_new_sentence_and_the_resource_says_exactly_what_the_client_returns() {
        assertEquals(
            "Could not load models: ES-UNREACHABLE",
            localizeAiMessage(
                text = "Could not load models: $NETWORK_UNREACHABLE_MESSAGE",
                serviceUnavailable = "ES-UNAVAILABLE",
                topupsPaused = "ES-PAUSED",
                networkUnreachable = "ES-UNREACHABLE",
            ),
        )

        val en = strings(uiLibDir.resolve("src/main/res/ui/common/values/ai_strings.xml"))
        val es = strings(uiLibDir.resolve("src/main/res/ui/common/values-es/ai_strings.xml"))
        assertEquals(NETWORK_UNREACHABLE_MESSAGE, en["ai_network_unreachable"])
        val spanish = es["ai_network_unreachable"] ?: fail("values-es is missing ai_network_unreachable")
        assertTrue(spanish.isNotBlank())
        assertNotEquals(NETWORK_UNREACHABLE_MESSAGE, spanish, "ai_network_unreachable is not translated")
    }

    private companion object {
        const val HOST = "ai.synthetic.invalid"
        const val TOKEN = "synthetic-bearer-token"
        const val MODEL = "venice-uncensored-1-2"
        const val PROMPT = "a lighthouse"
        const val ROUTES = 7

        val HELLO = listOf(AiChatTurn(role = "user", content = "hello", ts = 0L))

        fun html200() =
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<!DOCTYPE html>\n<html><head><title>$HOST</title></head><body>Just a moment</body></html>")

        /** A real client whose https base URL is swapped for [baseUrl] after construction. */
        fun clientFor(baseUrl: String): AiApiClient {
            val client = AiApiClient("https://$HOST")
            AiApiClient::class.java
                .getDeclaredField("baseUrl")
                .apply { isAccessible = true }
                .set(client, baseUrl)
            return client
        }

        fun strings(file: File): Map<String, String> {
            assertTrue(file.isFile, "missing ${file.path}")
            return Regex("""<string name="([^"]+)">([^<]*)</string>""")
                .findAll(file.readText())
                .associate { it.groupValues[1] to it.groupValues[2] }
        }

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/common/values").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }
    }
}
