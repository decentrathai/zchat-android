package co.electriccoin.zcash.ui.screen.ai

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RED pin: when the AI service is down, the AI tab shows the user raw server output.
 *
 * [AiApiClient] turns every non-2xx answer into `"HTTP <code>: <body>"`, and AiTabVM puts that string
 * in the error banner unchanged. Two real cases from the owner's phones:
 *  - Cloudflare answers 502 with its HTML error page when the backend behind the tunnel is unreachable.
 *    The banner then shows "HTTP 502: <!DOCTYPE html> <!--[if lt IE 7]> …", a whole web page.
 *  - Venice refuses the backend's calls with 402 when the service's own Venice account is unpaid. The
 *    backend relays that as 502 `{"error":"Venice error (402)", "detail": …}`, and the banner shows
 *    "HTTP 502: Venice error (402)". The user's own credit is not the problem, so the message must not
 *    send them to Top up (top-ups are paused anyway).
 *
 * The user must read one short sentence that says the service is unavailable, never a status code,
 * HTML, JSON or an upstream error string. Chat, image and the first-open registration share the
 * pattern, so each is checked.
 *
 * Driven through [AiApiClient] against a local MockWebServer; the client's https-only base URL is
 * pointed at it by reflection (the check guards production configuration, not this test). All hosts
 * and tokens are synthetic.
 */
class AiReadableErrorsTest {
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
    fun a_cloudflare_502_page_is_not_shown_as_html() =
        runBlocking {
            val client = clientFor(server)

            server.enqueue(cloudflare502())
            val chat = client.chat(TOKEN, MODEL, listOf(AiChatTurn(role = "user", content = "hello", ts = 0L)))
            assertReadableOutage("chat", (chat as? ChatResult.Failure)?.error ?: fail("chat: expected Failure, got $chat"))

            server.enqueue(cloudflare502())
            val image = client.image(TOKEN, IMAGE_MODEL, "a lighthouse")
            assertReadableOutage("image", (image as? ImageResult.Failure)?.error ?: fail("image: expected Failure, got $image"))

            server.enqueue(cloudflare502())
            val signIn = AiTabAccessFixture.access(AiTabAccessFixture.bareStore(), client).open()
            assertEquals(null, signIn.first, "sign-in: no key")
            assertReadableOutage("sign-in", signIn.second ?: fail("sign-in: expected a sentence, got $signIn"))
        }

    @Test
    fun a_venice_payment_refusal_is_not_shown_as_raw_text_or_as_the_users_debt() =
        runBlocking {
            val client = clientFor(server)

            server.enqueue(venice402())
            val chat = client.chat(TOKEN, MODEL, listOf(AiChatTurn(role = "user", content = "hello", ts = 0L)))
            val chatError = (chat as? ChatResult.Failure)?.error ?: fail("chat: the service's debt is not the user's; got $chat")
            assertReadableOutage("chat", chatError)
            assertFalse(chatError.contains("top up", ignoreCase = true), "chat: the user's credit is fine: $chatError")

            server.enqueue(venice402())
            val image = client.image(TOKEN, IMAGE_MODEL, "a lighthouse")
            val imageError =
                (image as? ImageResult.Failure)?.error ?: fail("image: the service's debt is not the user's; got $image")
            assertReadableOutage("image", imageError)
            assertFalse(imageError.contains("top up", ignoreCase = true), "image: the user's credit is fine: $imageError")
        }

    private fun assertReadableOutage(
        call: String,
        text: String
    ) {
        RAW_OUTPUT.forEach { (what, pattern) ->
            assertFalse(pattern.containsMatchIn(text), "$call: the banner shows $what: \"${text.take(SHOWN_CHARS)}\"")
        }
        assertTrue(text.length <= MAX_BANNER_CHARS, "$call: a banner sentence, not a page (${text.length} chars)")
        assertTrue(
            OUTAGE_WORDING.containsMatchIn(text),
            "$call: say the service is unavailable / to try again later: \"$text\"",
        )
    }

    private companion object {
        const val TOKEN = "synthetic-bearer-token"
        const val MODEL = "venice-uncensored-1-2"
        const val IMAGE_MODEL = "synthetic-image-model"
        const val MAX_BANNER_CHARS = 200
        const val SHOWN_CHARS = 160
        const val BAD_GATEWAY = 502

        val RAW_OUTPUT =
            listOf(
                "an HTTP status code" to Regex("""\bHTTP\s*\d{3}\b""", RegexOption.IGNORE_CASE),
                "HTML" to Regex("""<[!a-z/]""", RegexOption.IGNORE_CASE),
                "JSON" to Regex("""[{}]|"error"\s*:"""),
                "the upstream error string" to Regex("""Venice error""", RegexOption.IGNORE_CASE),
            )

        val OUTAGE_WORDING = Regex("""unavailable|try again|later""", RegexOption.IGNORE_CASE)

        /** What Cloudflare serves when the origin behind the tunnel does not answer (trimmed). */
        val CLOUDFLARE_502_PAGE =
            """
            <!DOCTYPE html>
            <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]-->
            <!--[if gt IE 8]><!--> <html class="no-js" lang="en-US"> <!--<![endif]-->
            <head>
            <title>ai.synthetic.invalid | 502: Bad gateway</title>
            <meta charset="UTF-8" />
            <link rel="stylesheet" id="cf_styles-css" href="/cdn-cgi/styles/main.css" />
            </head>
            <body><div id="cf-error-details"><h1>Bad gateway</h1><span>Error code 502</span></div></body>
            </html>
            """.trimIndent()

        /** The backend relaying Venice's 402 for the service's own unpaid account (server.ts). */
        const val VENICE_402_BODY =
            """{"error":"Venice error (402)","detail":"{\"error\":\"Insufficient balance to complete request\"}"}"""

        fun cloudflare502() =
            MockResponse()
                .setResponseCode(BAD_GATEWAY)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody(CLOUDFLARE_502_PAGE)

        fun venice402() =
            MockResponse()
                .setResponseCode(BAD_GATEWAY)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(VENICE_402_BODY)

        /** A real client whose https base URL is swapped for the local server after construction. */
        fun clientFor(server: MockWebServer): AiApiClient {
            val client = AiApiClient("https://ai.synthetic.invalid")
            AiApiClient::class.java
                .getDeclaredField("baseUrl")
                .apply { isAccessible = true }
                .set(client, server.url("/").toString().trimEnd('/'))
            return client
        }
    }
}
