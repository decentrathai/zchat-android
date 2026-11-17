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
 * AI-BAL (register ZC-889; money-path rule 4: a money figure on screen comes from its source, never from a guess).
 *
 * When the server hands an AI account back to a wallet, its sign-up answer carries no balance. The app read the
 * missing field as 200,000 micro-dollars and marked the balance as loaded, so the AI tab showed "Balance: $0.2000"
 * as a fact until the balance call answered, or for good when that call failed.
 *
 * AI-KEY-S: the sign-up is gone; a sign-in answer carries no balance (spec 8), so every account shows no figure until
 * the balance call answers. Hosts, access keys and account ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiRegisterAnswerBalanceIsNotGuessedTest {
    private val server = MockWebServer()
    private val balanceAsked = CountDownLatch(1)
    private val balanceMayAnswer = CountDownLatch(1)
    private lateinit var main: ExecutorCoroutineDispatcher
    private var registerAnswer = ""

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

    @Test
    fun `a sign-in answer without a balance is unknown, not 200000`() =
        runBlocking {
            registerAnswer = """{"userId":"$USER_ID","token":"$ACCESS_KEY","created":false}"""
            val client = client()
            val store = bareAiStore()
            try {
                val answer = AiTabAccessFixture.access(store, client).open()

                assertEquals("the sign-in did not succeed", ACCESS_KEY, answer.first)
                assertNull("a sign-in that succeeded said something", answer.second)
                val tab = AiTabVM(store, bareImageStore(), client, AiTabAccessFixture.access(store, client))
                try {
                    assertTrue("the tab never took the key", await { tab.state.value.token == ACCESS_KEY })
                    assertFalse("the server sent no balance, and the app made one up", tab.state.value.balanceLoaded)
                } finally {
                    endTheTab(tab)
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun `an account that was handed back shows no figure until the balance call answers`() =
        runBlocking {
            registerAnswer = """{"userId":"$USER_ID","token":"$ACCESS_KEY","created":false}"""
            val client = client()
            val tab = tab(client)
            try {
                assertTrue("the tab never signed up", await { tab.state.value.token == ACCESS_KEY })
                assertTrue("the tab never asked for the balance", balanceAsked.await(WAIT_SECONDS, TimeUnit.SECONDS))

                val before = tab.state.value
                assertFalse(
                    "no balance has come from the server yet, and the tab shows \"Balance: \$%.4f USD\" as loaded"
                        .format(before.balanceUsd),
                    before.balanceLoaded,
                )
                assertFalse("a low-balance warning without a balance", before.lowBalance)

                balanceMayAnswer.countDown()
                assertTrue("the balance answer never arrived", await { tab.state.value.balanceLoaded })
                assertEquals(SERVER_BALANCE_USD, tab.state.value.balanceUsd, 0.0)
            } finally {
                endTheTab(tab)
                client.close()
            }
        }

    @Test
    fun `guard - a new account shows the server's balance figure, from the balance call and from nowhere else`() =
        runBlocking {
            registerAnswer = """{"userId":"$USER_ID","token":"$ACCESS_KEY","created":true,"balanceMicroUsd":$NEW_ACCOUNT_MICRO_USD}"""
            val client = client()
            val tab = tab(client)
            try {
                assertTrue("the tab never signed up", await { tab.state.value.token == ACCESS_KEY })
                assertTrue("the tab never asked for the balance", balanceAsked.await(WAIT_SECONDS, TimeUnit.SECONDS))
                assertFalse("a figure in a sign-in answer was shown (spec 8: it carries none)", tab.state.value.balanceLoaded)

                balanceMayAnswer.countDown()
                assertTrue("the balance answer never arrived", await { tab.state.value.balanceLoaded })
                assertEquals(SERVER_BALANCE_USD, tab.state.value.balanceUsd, 0.0)
            } finally {
                endTheTab(tab)
                client.close()
            }
        }

    // ---------------------------------------------------------------------------------------------- helpers

    /**
     * Ends the tab's work and waits for it. Without the wait a request of the tab that is still on its way comes
     * back to the main dispatcher after this class has given it back, and that error fails an unrelated later test.
     */
    private suspend fun endTheTab(tab: AiTabVM) {
        balanceMayAnswer.countDown()
        withTimeoutOrNull(WAIT_SECONDS * MILLIS) { tab.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
    }

    private fun tab(client: AiApiClient): AiTabVM {
        val store = bareAiStore()
        return AiTabVM(store, bareImageStore(), client, AiTabAccessFixture.access(store, client))
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
        const val USER_ID = "acct_SYNTHETIC-0001"
        const val ACCESS_KEY = "synthetic-access-key"
        const val SERVER_BALANCE_USD = 1.25
        const val NEW_ACCOUNT_MICRO_USD = 150_000L
        const val WAIT_SECONDS = 10L
        const val HOLD_SECONDS = 5L
        const val MILLIS = 1_000L
        const val POLL_MS = 20L
    }
}
