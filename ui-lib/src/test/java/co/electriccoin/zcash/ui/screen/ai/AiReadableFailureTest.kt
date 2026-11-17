package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.ai.AiApiClient.Companion.SERVICE_UNAVAILABLE_MESSAGE
import co.electriccoin.zcash.ui.screen.ai.AiApiClient.Companion.TOPUP_PAUSED_MESSAGE
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * How AiApiClient words a failed answer, beyond the two RED pins (AiReadableErrorsTest,
 * AiTopupPausedTest):
 *  - the pure mapping from (status, body) to one readable sentence, for every shape the backend and
 *    Cloudflare actually send;
 *  - the service's upstream refusal is never mistaken for the user's own lack of credit, while the
 *    backend's real out-of-credit 402 still is;
 *  - a Cloudflare 503 page on the top-up route is an outage, not a pause;
 *  - the UI swaps the fixed English sentences for the localized strings, and the English resource says
 *    exactly what the client returns.
 *
 * All hosts and tokens are synthetic.
 */
class AiReadableFailureTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun outages_read_as_one_sentence_whatever_the_status() {
        assertEquals(SERVICE_UNAVAILABLE_MESSAGE, AiApiClient.readableHttpFailure(502, HTML_PAGE))
        assertEquals(SERVICE_UNAVAILABLE_MESSAGE, AiApiClient.readableHttpFailure(500, """{"error":"Internal"}"""))
        assertEquals(SERVICE_UNAVAILABLE_MESSAGE, AiApiClient.readableHttpFailure(503, """{"error":"Venice not configured"}"""))
        assertEquals(SERVICE_UNAVAILABLE_MESSAGE, AiApiClient.readableHttpFailure(502, VENICE_RELAY))
        assertEquals(SERVICE_UNAVAILABLE_MESSAGE, AiApiClient.readableHttpFailure(504, ""))
        // An HTML page is an outage even when the status is a 4xx.
        assertEquals(SERVICE_UNAVAILABLE_MESSAGE, AiApiClient.readableHttpFailure(403, HTML_PAGE))
    }

    @Test
    fun a_4xx_keeps_the_backends_plain_sentence_and_nothing_else() {
        assertEquals("Account not found", AiApiClient.readableHttpFailure(404, """{"error":"Account not found"}"""))
        assertEquals("messages required", AiApiClient.readableHttpFailure(400, """{"error":"messages required"}"""))

        // Not a plain sentence: no body, not JSON, JSON inside the error, an upstream string, a page of text.
        listOf(
            "",
            "Bad Request",
            """{"detail":"no error field"}""",
            """{"error":"{\"nested\":true}"}""",
            """{"error":"Venice error (400)"}""",
            """{"error":"${"x".repeat(250)}"}""",
        ).forEach { body ->
            assertEquals(SERVICE_UNAVAILABLE_MESSAGE, AiApiClient.readableHttpFailure(400, body), "body: $body")
        }
    }

    @Test
    fun top_up_address_503_from_the_backend_is_a_pause_but_a_cloudflare_503_is_an_outage() {
        assertEquals(TOPUP_PAUSED_MESSAGE, AiApiClient.readableTopupAddressFailure(503, PAUSED_BODY))
        assertEquals(TOPUP_PAUSED_MESSAGE, AiApiClient.readableTopupAddressFailure(503, ""))
        assertEquals(SERVICE_UNAVAILABLE_MESSAGE, AiApiClient.readableTopupAddressFailure(503, HTML_PAGE))
        assertEquals(SERVICE_UNAVAILABLE_MESSAGE, AiApiClient.readableTopupAddressFailure(502, PAUSED_BODY))
        assertEquals("Unauthorized", AiApiClient.readableTopupAddressFailure(401, """{"error":"Unauthorized"}"""))
    }

    @Test
    fun the_services_refusal_is_an_outage_but_the_users_402_is_not() {
        assertTrue(AiApiClient.isServiceOutage(VENICE_RELAY))
        assertTrue(AiApiClient.isServiceOutage(HTML_PAGE))
        assertFalse(AiApiClient.isServiceOutage(USER_OUT_OF_CREDIT))
        assertFalse(AiApiClient.isServiceOutage("""{"error":"Insufficient credit. Please top up."}"""))
    }

    @Test
    fun the_users_own_out_of_credit_still_asks_for_a_top_up() =
        runBlocking {
            val client = clientFor(server)
            server.enqueue(json(402, USER_OUT_OF_CREDIT))

            val chat = client.chat(TOKEN, MODEL, listOf(AiChatTurn(role = "user", content = "hello", ts = 0L)))

            val error = (chat as? ChatResult.OutOfCredit)?.error ?: fail("expected OutOfCredit, got $chat")
            assertTrue(error.contains("balance is too low"), error)
            assertTrue(error.contains("Top up", ignoreCase = true), error)
        }

    @Test
    fun an_upstream_refusal_sent_as_402_is_not_the_users_debt() =
        runBlocking {
            val client = clientFor(server)

            server.enqueue(json(402, VENICE_RELAY))
            val chat = client.chat(TOKEN, MODEL, listOf(AiChatTurn(role = "user", content = "hello", ts = 0L)))
            assertEquals(ChatResult.Failure(SERVICE_UNAVAILABLE_MESSAGE), chat)

            server.enqueue(html(402))
            val image = client.image(TOKEN, IMAGE_MODEL, "a lighthouse")
            assertEquals(ImageResult.Failure(SERVICE_UNAVAILABLE_MESSAGE), image)
        }

    @Test
    fun every_other_route_reads_an_outage_the_same_way() =
        runBlocking {
            val client = clientFor(server)

            server.enqueue(html(503))
            assertEquals(TopupAddressResult.Failure(SERVICE_UNAVAILABLE_MESSAGE), client.topupAddress(TOKEN))

            server.enqueue(html(502))
            assertEquals(TopupHistoryResult.Failure(SERVICE_UNAVAILABLE_MESSAGE), client.topupHistory(TOKEN))

            server.enqueue(html(502))
            assertEquals(ModelsResult.Failure(SERVICE_UNAVAILABLE_MESSAGE), client.listModels(TOKEN))

            server.enqueue(html(502))
            assertEquals(BalanceResult.Failure(SERVICE_UNAVAILABLE_MESSAGE), client.balance(TOKEN))
        }

    @Test
    fun the_ui_swaps_the_fixed_sentences_for_the_localized_ones() {
        val unavailable = "ES-UNAVAILABLE"
        val paused = "ES-PAUSED"

        assertEquals(unavailable, localizeAiMessage(SERVICE_UNAVAILABLE_MESSAGE, unavailable, paused))
        assertEquals(paused, localizeAiMessage(TOPUP_PAUSED_MESSAGE, unavailable, paused))
        assertEquals(
            "Could not load models: $unavailable",
            localizeAiMessage("Could not load models: $SERVICE_UNAVAILABLE_MESSAGE", unavailable, paused),
        )
        assertEquals("Account not found", localizeAiMessage("Account not found", unavailable, paused))
    }

    @Test
    fun the_english_strings_say_exactly_what_the_client_returns_and_spanish_translates_them() {
        val en = strings(uiLibDir.resolve("src/main/res/ui/common/values/ai_strings.xml"))
        val es = strings(uiLibDir.resolve("src/main/res/ui/common/values-es/ai_strings.xml"))

        assertEquals(SERVICE_UNAVAILABLE_MESSAGE, en["ai_service_unavailable"])
        assertEquals(TOPUP_PAUSED_MESSAGE, en["ai_topups_paused"])
        assertEquals(en.keys, es.keys, "values-es must translate every AI string")
        listOf("ai_service_unavailable", "ai_topups_paused").forEach { key ->
            val text = es.getValue(key)
            assertTrue(text.isNotBlank(), key)
            assertNotEquals(en[key], text, "$key is not translated")
        }
    }

    private companion object {
        const val TOKEN = "synthetic-bearer-token"
        const val MODEL = "venice-uncensored-1-2"
        const val IMAGE_MODEL = "synthetic-image-model"

        const val HTML_PAGE =
            "<!DOCTYPE html>\n<html><head><title>ai.synthetic.invalid | 502: Bad gateway</title></head>" +
                "<body><h1>Bad gateway</h1></body></html>"
        const val VENICE_RELAY =
            """{"error":"Venice error (402)","detail":"{\"error\":\"Insufficient balance to complete request\"}"}"""
        const val PAUSED_BODY = """{"error":"Top-up address not configured on this server yet"}"""
        const val USER_OUT_OF_CREDIT =
            """{"error":"Estimated cost exceeds available balance.","balanceMicroUsd":1000,"estChargeMicroUsd":1500}"""

        fun json(
            code: Int,
            body: String
        ) = MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)

        fun html(code: Int) =
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody(HTML_PAGE)

        /** A real client whose https base URL is swapped for the local server after construction. */
        fun clientFor(server: MockWebServer): AiApiClient {
            val client = AiApiClient("https://ai.synthetic.invalid")
            AiApiClient::class.java
                .getDeclaredField("baseUrl")
                .apply { isAccessible = true }
                .set(client, server.url("/").toString().trimEnd('/'))
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
