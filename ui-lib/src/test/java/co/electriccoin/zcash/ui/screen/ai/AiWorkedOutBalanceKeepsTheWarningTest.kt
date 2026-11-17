package co.electriccoin.zcash.ui.screen.ai

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-957, review of the night follow-ups (14 Oct, finding 1; money-path rule 4: a money figure on screen comes from
 * its source, never from a guess).
 *
 * At 828ed7c0a a chat or image answer that carries no balance figure sets `balanceStale = false`. So after the balance
 * had loaded and a later balance call failed ("Balance unknown"), such an answer took the warning away, and the
 * header, the low-balance bar and the footer showed the old figure minus the charge as the server's. The image path
 * never asked the server again (only the chat path does), so this lasted until the next resume.
 *
 * Wanted: an answer without a figure leaves the warning as it was, and the image path asks the server for the
 * balance, as the chat path does.
 *
 * The real AiTabVM and AiApiClient over a MockWebServer: the first balance call answers, the second fails, and every
 * later one is held until the test has read the state, then fails. Hosts, access keys and models are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiWorkedOutBalanceKeepsTheWarningTest {
    private val server = MockWebServer()
    private val balanceCalls = AtomicInteger()
    private val laterCallsMayAnswer = CountDownLatch(1)
    private lateinit var main: ExecutorCoroutineDispatcher
    private var answerMeta = """{"chargedUsd":$CHARGED_USD}"""

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path.orEmpty().substringBefore('?')) {
                        AiTabAccessFixture.CHALLENGE_PATH -> json(AiTabAccessFixture.CHALLENGE_ANSWER)
                        AiTabAccessFixture.SIGN_IN_PATH ->
                            json("""{"userId":"$USER_ID","token":"$ACCESS_KEY","created":false}""")
                        "/api/v1/ai/balance" -> balanceAnswer(balanceCalls.incrementAndGet())
                        "/api/v1/ai/models" -> json(MODELS)
                        "/api/v1/ai/chat" ->
                            json(
                                """{"choices":[{"message":{"content":"hello"}}],""" +
                                    """"usage":{"prompt_tokens":3,"completion_tokens":5},"zchat_meta":$answerMeta}""",
                            )
                        "/api/v1/ai/image" -> json("""{"data":[{"url":"$IMAGE_URL"}],"zchat_meta":$answerMeta}""")
                        else -> json("{}")
                    }
            }
        server.start()
    }

    @After
    fun tearDown() {
        laterCallsMayAnswer.countDown()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    @Test
    fun `an image answer without a figure keeps the warning of a failed balance call and asks the server again`() =
        withStaleTab { tab ->
            tab.generateImage("a lighthouse")
            assertTrue("the image never arrived", await { tab.state.value.images.size == 1 })

            val shown = tab.state.value
            assertTrue("an image answer without a figure took the 'Balance unknown' warning away", shown.balanceStale)
            assertFalse("a figure worked out here is shown as the server's: ${shown.chargeFooter}", "balance" in shown.chargeFooter)
            assertFalse("a figure worked out here raises the low-balance bar", shown.lowBalance)
            assertTrue(
                "an image answer without a figure never asks the server for the balance",
                await { balanceCalls.get() >= THIRD_CALL },
            )
        }

    @Test
    fun `a chat answer without a figure keeps the warning of a failed balance call`() =
        withStaleTab { tab ->
            tab.send("hi")
            assertTrue("the answer never arrived", await { tab.state.value.chatTurns.size == 2 })

            val shown = tab.state.value
            assertTrue("a chat answer without a figure took the 'Balance unknown' warning away", shown.balanceStale)
            assertFalse("a figure worked out here is shown as the server's: ${shown.chargeFooter}", "balance" in shown.chargeFooter)
            assertTrue("guard: the chat path asks the server again", await { balanceCalls.get() >= THIRD_CALL })
        }

    @Test
    fun `guard - an answer that carries the server's figure ends the warning`() {
        answerMeta = """{"chargedUsd":$CHARGED_USD,"balanceAfterUsd":$AFTER_USD}"""
        withStaleTab { tab ->
            tab.generateImage("a lighthouse")
            assertTrue("the image never arrived", await { tab.state.value.images.size == 1 })

            val shown = tab.state.value
            assertFalse("the server's own figure left the warning up", shown.balanceStale)
            assertEquals(AFTER_USD, shown.balanceUsd, 0.0)
            assertTrue(shown.chargeFooter, shown.chargeFooter.endsWith("balance " + AiApiClient.usd(AFTER_USD)))
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** The first call answers, the second fails, and every later one waits for [laterCallsMayAnswer], then fails. */
    private fun balanceAnswer(call: Int): MockResponse =
        when (call) {
            1 -> json("""{"balanceUsd":$SERVER_BALANCE_USD,"freeTrialAvailable":false}""")
            2 -> MockResponse().setResponseCode(SERVER_ERROR).setBody("""{"error":"synthetic outage"}""")
            else -> {
                laterCallsMayAnswer.await(HOLD_SECONDS, TimeUnit.SECONDS)
                MockResponse().setResponseCode(SERVER_ERROR).setBody("""{"error":"synthetic outage"}""")
            }
        }

    /** A signed-up tab whose balance loaded from the server and whose next balance call then failed. */
    private fun withStaleTab(block: suspend (AiTabVM) -> Unit) =
        runBlocking {
            val client = client()
            val store = bareAiStore()
            val tab = AiTabVM(store, bareImageStore(), client, AiTabAccessFixture.access(store, client))
            try {
                assertTrue("the tab never signed up: ${tab.state.value.error}", await { tab.state.value.token == ACCESS_KEY })
                assertTrue("fixture: the balance never loaded", await { tab.state.value.balanceLoaded })
                assertEquals("fixture: the server's figure", SERVER_BALANCE_USD, tab.state.value.balanceUsd, 0.0)
                assertTrue("the models never loaded", await { tab.state.value.selectedImageModel != null })
                assertEquals(MODEL, tab.state.value.selectedChatModel)

                tab.refreshBalanceNow()
                assertTrue(
                    "fixture: the failed balance call did not raise the warning",
                    await { tab.state.value.balanceStale && !tab.state.value.balanceRefreshing },
                )
                assertEquals("fixture: two balance calls", 2, balanceCalls.get())
                block(tab)
            } finally {
                laterCallsMayAnswer.countDown()
                withTimeoutOrNull(WAIT_SECONDS * MILLIS) { tab.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
                client.close()
            }
        }

    private fun client(): AiApiClient {
        val client = AiApiClient("https://ai.synthetic.invalid")
        AiApiClient::class.java
            .getDeclaredField("baseUrl")
            .apply { isAccessible = true }
            .set(client, server.url("/").toString().trimEnd('/'))
        return client
    }

    /** An AI store without the Android Keystore: the real class over an in-memory file, with nothing saved. */
    private fun bareAiStore(): AiPreferences {
        val store = allocateBare(AiPreferences::class.java) as AiPreferences
        AiPreferences::class.java
            .getDeclaredField("prefs\$delegate")
            .apply { isAccessible = true }
            .set(store, lazyOf(MemorySharedPreferences()))
        return store
    }

    private fun bareImageStore(): AiImageStore = allocateBare(AiImageStore::class.java) as AiImageStore

    private fun allocateBare(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, clazz)!!
    }

    private suspend fun await(condition: () -> Boolean): Boolean =
        withTimeoutOrNull(WAIT_SECONDS * MILLIS) {
            while (!condition()) delay(POLL_MS)
            true
        } ?: false

    private fun json(body: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val USER_ID = "acct_SYNTHETIC-0003"
        const val ACCESS_KEY = "synthetic-access-key-3"
        const val IMAGE_URL = "https://images.synthetic.invalid/3.png"
        const val MODEL = "synthetic-text-1"
        const val SERVER_BALANCE_USD = 1.25
        const val AFTER_USD = 0.75
        const val CHARGED_USD = 0.002
        const val SERVER_ERROR = 500
        const val THIRD_CALL = 3
        const val WAIT_SECONDS = 10L
        const val HOLD_SECONDS = 5L
        const val MILLIS = 1_000L
        const val POLL_MS = 20L
        const val MODELS =
            """{"data":[""" +
                """{"id":"synthetic-text-1","zchat_model_type":"text",""" +
                """"zchat_pricing":{"inputPer1mUsd":1.0,"outputPer1mUsd":2.0}},""" +
                """{"id":"synthetic-image-1","zchat_model_type":"image","zchat_pricing":{"imagePerCallUsd":0.01}}]}"""
    }
}
