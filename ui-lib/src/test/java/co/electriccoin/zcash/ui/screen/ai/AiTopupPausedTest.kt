package co.electriccoin.zcash.ui.screen.ai

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RED pin: AI top-ups are paused, and the app does not say so.
 *
 * Top-ups sent since the Ironwood upgrade cannot be credited yet, so the owner paused them server-side:
 * `GET /api/v1/ai/topup/address` now answers 503 `{"error":"Top-up address not configured on this server
 * yet"}`. [AiApiClient.topupAddress] turns that into `"HTTP 503: {\"error\":…}"`, and the AI tab's Top up
 * button toasts "Top-up not configured yet: " followed by that text. The user reads a status code, JSON
 * and a server-configuration remark instead of the one fact that matters: top-ups are paused for now.
 * The release notes promise exactly that wording ("AI top-ups are temporarily paused").
 *
 * The failure text and the toast that wraps it are both checked. The toast is built inside the
 * AndroidAiTab composable, which a JVM test cannot run, so its literal is read from the source.
 * All hosts and tokens are synthetic.
 */
class AiTopupPausedTest {
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
    fun a_paused_top_up_says_top_ups_are_paused() =
        runBlocking {
            val client = AiApiClient("https://ai.synthetic.invalid")
            AiApiClient::class.java
                .getDeclaredField("baseUrl")
                .apply { isAccessible = true }
                .set(client, server.url("/").toString().trimEnd('/'))
            server.enqueue(
                MockResponse()
                    .setResponseCode(SERVICE_UNAVAILABLE)
                    .setHeader("Content-Type", "application/json; charset=utf-8")
                    .setBody(PAUSED_BODY),
            )

            val result = client.topupAddress("synthetic-bearer-token")
            val text = (result as? TopupAddressResult.Failure)?.error ?: fail("expected Failure, got $result")

            assertFalse(Regex("""\bHTTP\s*\d{3}\b""").containsMatchIn(text), "no status code: \"$text\"")
            assertFalse(Regex("""[{}]|"error"\s*:""").containsMatchIn(text), "no JSON: \"$text\"")
            assertFalse(text.contains("configured", ignoreCase = true), "no server-configuration remark: \"$text\"")
            assertTrue(text.contains("paused", ignoreCase = true), "say that top-ups are paused: \"$text\"")
        }

    @Test
    fun the_top_up_toast_does_not_call_a_pause_a_missing_configuration() {
        val source = uiLibDir.resolve("src/main/java/co/electriccoin/zcash/ui/screen/ai/AndroidAiTab.kt")
        assertTrue(source.isFile, "missing ${source.path}")

        val toastLiterals =
            Regex(""""(?:[^"\\]|\\.)*"""")
                .findAll(source.readText())
                .map { it.value }
                .filter { it.contains("not configured", ignoreCase = true) }
                .toList()
        assertTrue(toastLiterals.isEmpty(), "AndroidAiTab still wraps the failure as $toastLiterals")
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
        const val PAUSED_BODY = """{"error":"Top-up address not configured on this server yet"}"""

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/java/co/electriccoin/zcash/ui/screen/ai").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }
    }
}
