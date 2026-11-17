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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ZC-957 (review of AI-BAL, 13 Oct, finding 3 and nit 6; money-path rule 4: a money figure on screen comes from its
 * source, never from a guess).
 *
 * - The line under the latest answer named a balance in every case. An account that was handed back and chatted
 *   before the balance call answered read "balance $0.0000" there, a figure the app had made itself.
 * - When a chat or image answer carried the server's own figure, the tab took the figure and still counted the
 *   balance as not loaded, so the header kept its spinner over a known balance.
 * - A sign-up answer whose balance could not be read failed the whole sign-up, after the server had issued the
 *   access key. AI-KEY-S: the sign-in that replaced it carries no balance (spec 8); whatever such a field holds, it
 *   neither fails the sign-in nor becomes a figure.
 *
 * Hosts, access keys, account ids and models are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiFooterNamesOnlyAServerBalanceTest {
    private val server = MockWebServer()
    private val balanceAsked = CountDownLatch(1)
    private val balanceMayAnswer = CountDownLatch(1)
    private lateinit var main: ExecutorCoroutineDispatcher
    private var registerAnswer = """{"userId":"$USER_ID","token":"$ACCESS_KEY","created":false}"""
    private var chatMeta = """{"chargedUsd":$CHARGED_USD}"""

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path.orEmpty().substringBefore('?')) {
                        AiTabAccessFixture.CHALLENGE_PATH -> json(AiTabAccessFixture.CHALLENGE_ANSWER)
                        AiTabAccessFixture.SIGN_IN_PATH -> json(registerAnswer)
                        "/api/v1/ai/balance" -> {
                            balanceAsked.countDown()
                            balanceMayAnswer.await(HOLD_SECONDS, TimeUnit.SECONDS)
                            json("""{"balanceUsd":$SERVER_BALANCE_USD,"freeTrialAvailable":false}""")
                        }
                        "/api/v1/ai/models" -> json(MODELS)
                        "/api/v1/ai/chat" ->
                            json(
                                """{"choices":[{"message":{"content":"hello"}}],""" +
                                    """"usage":{"prompt_tokens":3,"completion_tokens":5},"zchat_meta":$chatMeta}""",
                            )
                        "/api/v1/ai/image" ->
                            json("""{"data":[{"url":"$IMAGE_URL"}],"zchat_meta":$chatMeta}""")
                        else -> json("{}")
                    }
            }
        server.start()
    }

    @After
    fun tearDown() {
        balanceMayAnswer.countDown()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    // ------------------------------------------------------------------------------------------ the footer

    @Test
    fun `a handed-back account that chats before the balance call answers sees no balance under the answer`() =
        withTab { tab ->
            tab.send("hi")
            assertTrue("the answer never arrived", await { tab.state.value.chatTurns.size == 2 })

            val shown = tab.state.value
            assertFalse("no balance has come from the server, and the tab counts one as loaded", shown.balanceLoaded)
            val footer = footerOf(shown)
            assertTrue("the footer lost the charge: $footer", AiApiClient.usd(CHARGED_USD) in footer)
            assertFalse("the footer names a balance the server never sent: $footer", "balance" in footer)
        }

    @Test
    fun `the footer names the balance once the server's figure is loaded, and not while it is in doubt`() {
        val loaded =
            AiState(lastChatModel = MODEL, lastChargedUsd = CHARGED_USD, balanceUsd = AFTER_USD, balanceLoaded = true)

        assertTrue(footerOf(loaded), footerOf(loaded).endsWith("balance " + AiApiClient.usd(AFTER_USD)))
        assertTrue(footerOf(loaded), "charged " + AiApiClient.usd(CHARGED_USD) in footerOf(loaded))
        assertFalse("not loaded", "balance" in footerOf(loaded.copy(balanceLoaded = false)))
        assertFalse("the last balance call failed", "balance" in footerOf(loaded.copy(balanceStale = true)))
    }

    @Test
    fun `the screen takes the footer from the state and makes no balance text of its own`() {
        val view = AiSignInApi.read(AiSignInApi.mainAiFile("AiTabView.kt"))

        assertTrue("AiTabView.kt does not show the state's footer", "state.chargeFooter" in view)
        assertFalse("AiTabView.kt still writes a balance under the answer", "· balance" in view)
    }

    // ------------------------------------------------------------------------------------------ the loaded flag

    @Test
    fun `the server's figure in a chat answer is the loaded balance, and the header's spinner ends`() {
        chatMeta = """{"chargedUsd":$CHARGED_USD,"balanceAfterUsd":$AFTER_USD}"""
        withTab { tab ->
            tab.send("hi")
            assertTrue("the answer never arrived", await { tab.state.value.chatTurns.size == 2 })

            val shown = tab.state.value
            assertEquals(AFTER_USD, shown.balanceUsd, 0.0)
            assertTrue("the server's figure came with the answer and the header keeps its spinner", shown.balanceLoaded)
            assertTrue(footerOf(shown), footerOf(shown).endsWith("balance " + AiApiClient.usd(AFTER_USD)))
        }
    }

    @Test
    fun `the server's figure in an image answer is the loaded balance`() {
        chatMeta = """{"chargedUsd":$CHARGED_USD,"balanceAfterUsd":$AFTER_USD}"""
        withTab { tab ->
            tab.generateImage("a lighthouse")
            assertTrue("the image never arrived", await { tab.state.value.images.size == 1 })

            val shown = tab.state.value
            assertEquals(AFTER_USD, shown.balanceUsd, 0.0)
            assertTrue("the server's figure came with the image, and the header keeps its spinner", shown.balanceLoaded)
        }
    }

    @Test
    fun `guard - an image answer without a figure leaves an unloaded balance unloaded`() =
        withTab { tab ->
            tab.generateImage("a lighthouse")
            assertTrue("the image never arrived", await { tab.state.value.images.size == 1 })

            assertFalse("a balance the app worked out itself counts as loaded", tab.state.value.balanceLoaded)
        }

    // ------------------------------------------------------------------------------------------ the sign-up answer

    @Test
    fun `a sign-in answer whose balance cannot be read still signs in, with no sentence`() =
        runBlocking {
            val client = client()
            try {
                listOf("\"soon\"", "null", "true", "{}", "[1]", "-5", "1.5", "\"\"").forEach { unreadable ->
                    registerAnswer = """{"userId":"$USER_ID","token":"$ACCESS_KEY","balanceMicroUsd":$unreadable}"""

                    val answer = AiTabAccessFixture.access(bareAiStore(), client).open()

                    assertEquals("balance $unreadable failed the sign-in", ACCESS_KEY, answer.first)
                    assertNull("balance $unreadable made a sentence", answer.second)
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun `guard - a balance in a sign-in answer, readable as a number or as digits, is no figure on screen`() {
        listOf("150000", "\"150000\"", "150000.0", "0").forEach { readable ->
            registerAnswer = """{"userId":"$USER_ID","token":"$ACCESS_KEY","balanceMicroUsd":$readable}"""
            val client = client()
            val store = bareAiStore()
            val tab = AiTabVM(store, bareImageStore(), client, AiTabAccessFixture.access(store, client))
            runBlocking {
                try {
                    assertTrue("the tab never signed in", await { tab.state.value.token == ACCESS_KEY })
                    assertFalse("balance $readable was shown before the balance call answered", tab.state.value.balanceLoaded)
                } finally {
                    withTimeoutOrNull(WAIT_SECONDS * MILLIS) { tab.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
                    client.close()
                }
            }
        }
    }

    @Test
    fun `a new account whose balance cannot be read opens the tab with no figure until the balance call answers`() {
        registerAnswer = """{"userId":"$USER_ID","token":"$ACCESS_KEY","balanceMicroUsd":"soon"}"""
        withTab { tab ->
            assertNull("the tab shows an error", tab.state.value.error)
            assertFalse("a figure is shown that no answer carried", tab.state.value.balanceLoaded)

            balanceMayAnswer.countDown()
            assertTrue("the balance answer never arrived", await { tab.state.value.balanceLoaded })
            assertEquals(SERVER_BALANCE_USD, tab.state.value.balanceUsd, 0.0)
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** A signed-up tab whose models are loaded and whose balance call is asked and not yet answered. */
    private fun withTab(block: suspend (AiTabVM) -> Unit) =
        runBlocking {
            val client = client()
            val store = bareAiStore()
            val tab = AiTabVM(store, bareImageStore(), client, AiTabAccessFixture.access(store, client))
            try {
                assertTrue(
                    "the tab never signed up: ${tab.state.value.error}",
                    await { tab.state.value.token == ACCESS_KEY },
                )
                assertTrue("the tab never asked for the balance", balanceAsked.await(WAIT_SECONDS, TimeUnit.SECONDS))
                assertTrue("the models never loaded", await { tab.state.value.selectedImageModel != null })
                assertEquals(MODEL, tab.state.value.selectedChatModel)
                block(tab)
            } finally {
                balanceMayAnswer.countDown()
                withTimeoutOrNull(WAIT_SECONDS * MILLIS) { tab.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
                client.close()
            }
        }

    /** The line under the latest answer, read by name: before ZC-957 the screen made it, with a balance always. */
    private fun footerOf(state: AiState): String {
        val getter =
            AiState::class.java.methods.firstOrNull { it.name == "getChargeFooter" && it.parameterCount == 0 }
                ?: throw AssertionError(
                    "AiState has no chargeFooter: the screen makes the footer itself and names a balance in every case",
                )
        return getter.invoke(state) as String
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

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val USER_ID = "acct_SYNTHETIC-0002"
        const val ACCESS_KEY = "synthetic-access-key"
        const val IMAGE_URL = "https://images.synthetic.invalid/1.png"
        const val MODEL = "synthetic-text-1"
        const val SERVER_BALANCE_USD = 1.25
        const val AFTER_USD = 0.75
        const val CHARGED_USD = 0.002
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
