package co.electriccoin.zcash.ui.screen.ai

import androidx.lifecycle.viewModelScope
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ZC-1238: a chat stopped by a change of wallet is marked failed, and its Retry resends wallet A's prompt on B's key on a
 * tap: a second charge, on the wrong wallet. A request the server already billed ends the same way (review-r3/grok.json,
 * defect 1), and the sentence the tab shows for it, chat or picture, says "Tap Retry".
 *
 * WHY IT FAILS TODAY: nothing ties a turn to its wallet. retryFailed() and regenerate() resend the turn on the key of the
 * moment, send() puts the screen's transcript in the request, and walletChanged() answers "Tap Retry in a moment".
 *
 * The tab and the real AiAccountAccess over the real client, against a mock server; "a wallet change" is what
 * AiKeyRound3TabTest does. Not pinned: whether the stale turn keeps a Retry that says the wallet changed or has none at all,
 * and what the tab says instead. The /ai shortcut has no case: its failed bubble is the incoming reply (isOutgoing = false),
 * and ChatDetailView offers Retry only on an outgoing one. Every key, account and prompt is synthetic.
 *
 * GUARDS, which pass today and pin what the fix must not change: every case named "guard".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiKeyStaleTurnRetryTest {
    private val server = MockWebServer()
    private val seen = CopyOnWriteArrayList<Seen>()
    private val routes = ConcurrentHashMap<String, (String?) -> MockResponse>()
    private val hold = ConcurrentHashMap<String, CountDownLatch>()
    private lateinit var main: ExecutorCoroutineDispatcher
    private var counter = 7L
    private var tag = "a".repeat(SEED_BYTES)
    private val store = AiTabAccessFixture.bareStore()

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        routes[CHALLENGE] = { json(AiTabAccessFixture.CHALLENGE_ANSWER) }
        routes[KEY_STATE] = { json("""{"signInKeyId":"$OWN_KEY_ID","paid":false}""") }
        routes[MODELS_PATH] = { json(MODELS) }
        routes[BALANCE_PATH] = { json("""{"balanceUsd":$BALANCE,"freeTrialAvailable":false,"onFreeTrial":false}""") }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    val key = request.getHeader("Authorization")?.removePrefix("Bearer ")
                    seen += Seen(path, key, request.body.readUtf8())
                    hold.remove(path)?.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    return routes[path]?.invoke(key) ?: json("{}")
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        hold.values.forEach { it.countDown() }
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    // ------------------------------------------------------------------------------ Retry sends nothing on another wallet

    @Test
    fun `retry - A's chat billed, its answer dropped by the wallet change - a tap sends nothing`() =
        withLinkedTab { tab ->
            stoppedByWalletChange(tab, answered())

            assertNothingOfACarried { tab.retryFailed() }
        }

    @Test
    fun `retry - A's chat refused across the wallet change - a tap does not send it on B's key`() =
        withLinkedTab { tab ->
            stoppedByWalletChange(tab) { key -> if (key == TOKEN_A) refusedKey() else answered()(key) }

            assertNothingOfACarried { tab.retryFailed() }
        }

    @Test
    fun `retry - a turn that failed on A, then the wallet changed - a tap does not send it on B's key`() =
        withLinkedTab { tab ->
            failedOnA(tab)
            changeWallet()

            assertNothingOfACarried { tab.retryFailed() }
        }

    @Test
    fun `retry - a turn A's user stopped, then the wallet changed - a tap does not send it on B's key`() =
        withLinkedTab { tab ->
            stoppedByTheUser(tab)
            changeWallet()

            assertNothingOfACarried { tab.retryFailed() }
        }

    @Test
    fun `retry - A's failed chat reopened from the list after the wallet changed - a tap does not send it on B's key`() =
        withLinkedTab { tab ->
            failedOnA(tab)
            val conversation = checkNotNull(tab.state.value.currentConversationId)
            changeWallet()
            tab.openConversation(conversation)

            assertNothingOfACarried { tab.retryFailed() }
        }

    @Test
    fun `regenerate - A's answered chat after the wallet changed - it is not asked again on B's key`() =
        withLinkedTab { tab ->
            answeredOnA(tab)
            changeWallet()

            assertNothingOfACarried { tab.regenerate() }
        }

    // ----------------------------------------------------------------------- a new prompt on B carries nothing of A's chat

    @Test
    fun `send - B's first prompt after A's stopped chat - it leaves without A's prompt`() =
        withLinkedTab { tab ->
            stoppedByWalletChange(tab, answered())

            assertBSendsOnlyItsOwn(tab)
        }

    @Test
    fun `send - B's first prompt after A's answered chat - it leaves without A's prompt`() =
        withLinkedTab { tab ->
            answeredOnA(tab)
            changeWallet()

            assertBSendsOnlyItsOwn(tab)
        }

    @Test
    fun `send - B's prompt in A's chat reopened from the list - it leaves without A's prompt`() =
        withLinkedTab { tab ->
            answeredOnA(tab)
            val conversation = checkNotNull(tab.state.value.currentConversationId)
            changeWallet()
            tab.openConversation(conversation)

            assertBSendsOnlyItsOwn(tab)
        }

    // ------------------------------------------------------------------------- the sentence does not invite a second bill

    @Test
    fun `sentence - a chat stopped by the wallet change - the tab does not say to tap Retry`() =
        withLinkedTab { tab ->
            stoppedByWalletChange(tab, answered())

            assertNoInvitation(tab)
        }

    @Test
    fun `sentence - a picture stopped by the wallet change - the tab does not say to tap Retry`() =
        withLinkedTab { tab ->
            routes[IMAGE] = { json(PICTURE) }
            val gate = holdNext(IMAGE)

            tab.generateImage(PROMPT_A)
            assertTrue("the picture never left", await { seen.any { it.path == IMAGE && it.key == TOKEN_A } })
            changeWallet()
            gate.countDown()
            assertTrue("the composer stays locked", await { !tab.state.value.sending })

            assertNoInvitation(tab)
        }

    // ------------------------------------------------------------------------------------------- the same wallet, guards

    @Test
    fun `guard - a turn that failed, same wallet - Retry sends it once on A's key`() =
        withLinkedTab { tab ->
            failedOnA(tab)

            assertRetrySendsOnA(tab)
        }

    @Test
    fun `guard - a turn the user stopped, same wallet - Retry sends it once on A's key`() =
        withLinkedTab { tab ->
            stoppedByTheUser(tab)

            assertRetrySendsOnA(tab)
        }

    @Test
    fun `guard - an answered chat, same wallet - regenerate asks again on A's key`() =
        withLinkedTab { tab ->
            answeredOnA(tab)
            seen.clear()

            tab.regenerate()

            assertTrue("regenerate sent nothing", await { chats().isNotEmpty() })
            assertEquals(listOf(TOKEN_A), chats().map { it.key })
            assertTrue("regenerate lost the prompt", PROMPT_A in chats().single().body)
        }

    @Test
    fun `guard - the next prompt, same wallet - it carries the chat so far`() =
        withLinkedTab { tab ->
            answeredOnA(tab)
            seen.clear()

            tab.send(PROMPT_B)

            assertTrue("the next prompt never left", await { chats().isNotEmpty() })
            val sent = chats().single()
            assertEquals(TOKEN_A, sent.key)
            assertTrue("the chat so far is gone from the request", PROMPT_A in sent.body && PROMPT_B in sent.body)
        }

    // ---------------------------------------------------------------------------------------------- the arrangements

    /** A's chat waits; the wallet changes to B; A's chat is answered with [route]. The tab comes back to rest. */
    private suspend fun stoppedByWalletChange(
        tab: AiTabVM,
        route: (String?) -> MockResponse,
    ) {
        routes[CHAT] = route
        val gate = holdNext(CHAT)
        tab.send(PROMPT_A)
        assertTrue("A's chat never left", await { chats().any { it.key == TOKEN_A } })
        changeWallet()
        gate.countDown()
        assertTrue("the composer stays locked", await { !tab.state.value.sending })
        delay(SETTLE_MILLIS)
    }

    private suspend fun failedOnA(tab: AiTabVM) {
        routes[CHAT] = { json("""{"error":"synthetic failure"}""").setResponseCode(BAD_REQUEST) }
        tab.send(PROMPT_A)
        assertTrue("the turn never failed", await { lastTurn(tab)?.failed == true })
        routes[CHAT] = answered()
    }

    private suspend fun stoppedByTheUser(tab: AiTabVM) {
        routes[CHAT] = answered()
        val gate = holdNext(CHAT)
        tab.send(PROMPT_A)
        assertTrue("A's chat never left", await { chats().any { it.key == TOKEN_A } })
        tab.stopGeneration()
        gate.countDown()
        assertTrue("the turn was not marked by Stop", await { lastTurn(tab)?.failed == true })
    }

    private suspend fun answeredOnA(tab: AiTabVM) {
        routes[CHAT] = answered()
        tab.send(PROMPT_A)
        assertTrue(
            "A's chat was never answered",
            await { !tab.state.value.sending && lastTurn(tab)?.role == AiChatTurn.ROLE_ASSISTANT },
        )
    }

    private suspend fun assertNothingOfACarried(tap: () -> Unit) {
        seen.clear()
        tap()
        delay(SETTLE_MILLIS)
        val carrying = seen.filter { it.path == CHAT && PROMPT_A in it.body }
        assertEquals("wallet A's prompt went out again", emptyList<String?>(), carrying.map { it.key })
    }

    /** B may ask: the request leaves on B's key, and A's prompt is not in it. */
    private suspend fun assertBSendsOnlyItsOwn(tab: AiTabVM) {
        seen.clear()
        tab.send(PROMPT_B)
        assertTrue("B's own prompt never left", await { chats().isNotEmpty() })
        val sent = chats().single()
        assertEquals(TOKEN_B, sent.key)
        assertTrue("B's prompt is not in the request", PROMPT_B in sent.body)
        assertFalse("wallet A's prompt went out on B's key", PROMPT_A in sent.body)
    }

    private fun assertNoInvitation(tab: AiTabVM) {
        val shown = tab.state.value
        val said = shown.error.orEmpty()
        assertFalse("the tab invites a second bill: $said", Regex("(?i)tap retry").containsMatchIn(said))
    }

    private suspend fun assertRetrySendsOnA(tab: AiTabVM) {
        seen.clear()
        tab.retryFailed()
        assertTrue("Retry sent nothing", await { chats().isNotEmpty() })
        delay(SETTLE_MILLIS)
        assertEquals(listOf(TOKEN_A), chats().map { it.key })
        assertTrue("Retry lost the prompt", PROMPT_A in chats().single().body)
    }

    private fun chats() = seen.filter { it.path == CHAT }

    private fun lastTurn(tab: AiTabVM): AiChatTurn? {
        val turns = tab.state.value.chatTurns
        return turns.lastOrNull()
    }

    private fun holdNext(path: String): CountDownLatch = CountDownLatch(1).also { hold[path] = it }

    private fun changeWallet() {
        tag = "b".repeat(SEED_BYTES)
        counter++
        store.saveCredentials(TOKEN_B, ID_B)
        store.writeIfStillThisWallet({ true }) { putLinkStateJson("""{"accountId":"$ID_B","verdict":"linked","paid":"yes"}""") }
    }

    private fun withLinkedTab(block: suspend (AiTabVM) -> Unit) =
        runBlocking {
            store.saveCredentials(TOKEN_A, ID_A)
            val client = client()
            val access =
                AiAccountAccess(
                    prefs = store,
                    seed = { ByteArray(SEED_BYTES) { it.toByte() } },
                    candidates = { emptyList() },
                    ownerTag = { tag },
                    scopeCounter = { counter },
                    walletSaved = { true },
                    clock = System::currentTimeMillis,
                    send = client::request,
                )
            val tab = AiTabVM(store, bareImageStore(), client, access)
            try {
                val ready =
                    await {
                        tab.state.value.balanceLoaded &&
                            tab.state.value.selectedChatModel != null &&
                            tab.state.value.selectedImageModel != null &&
                            "linked" in store.getLinkStateJson().orEmpty()
                    }
                assertTrue("the tab never got ready: ${tab.state.value.error}", ready)
                block(tab)
            } finally {
                hold.values.forEach { it.countDown() }
                withTimeoutOrNull(WAIT_MILLIS) { tab.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
                client.close()
            }
        }

    // ---------------------------------------------------------------------------------------------- the harness

    private data class Seen(
        val path: String,
        val key: String?,
        val body: String,
    )

    private fun answered(): (String?) -> MockResponse = { json(ANSWER) }

    private fun refusedKey() = json("""{"error":"Invalid token"}""").setResponseCode(UNAUTHORIZED)

    private fun client(): AiApiClient {
        val client = AiApiClient("https://ai.synthetic.invalid")
        AiApiClient::class.java
            .getDeclaredField("baseUrl")
            .apply { isAccessible = true }
            .set(client, server.url("/").toString().trimEnd('/'))
        return client
    }

    private fun bareImageStore(): AiImageStore {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, AiImageStore::class.java) as AiImageStore
    }

    private suspend fun await(condition: () -> Boolean): Boolean =
        withTimeoutOrNull(WAIT_MILLIS) {
            while (!condition()) delay(POLL_MILLIS)
            true
        } ?: false

    private fun json(body: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val CHALLENGE = "/api/v1/ai/auth/key/challenge"
        const val KEY_STATE = "/api/v1/ai/auth/key/state"
        const val BALANCE_PATH = "/api/v1/ai/balance"
        const val MODELS_PATH = "/api/v1/ai/models"
        const val CHAT = "/api/v1/ai/chat"
        const val IMAGE = "/api/v1/ai/image"

        const val TOKEN_A = "synthetic-token-a"
        const val ID_A = "acct_SYNTHETIC-a"
        const val TOKEN_B = "synthetic-token-b"
        const val ID_B = "acct_SYNTHETIC-b"
        const val PROMPT_A = "synthetic-prompt-of-wallet-a"
        const val PROMPT_B = "synthetic-prompt-of-wallet-b"
        const val BALANCE = 5.0

        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val SEED_BYTES = 64
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val SETTLE_MILLIS = 800L
        const val HOLD_SECONDS = 15L

        const val ANSWER =
            """{"choices":[{"message":{"content":"synthetic reply"}}],"usage":{"prompt_tokens":3,"completion_tokens":5},""" +
                """"zchat_meta":{"chargedUsd":0.002,"balanceAfterUsd":4.0}}"""
        const val PICTURE =
            """{"data":[{"url":"https://ai.synthetic.invalid/late.png"}],"zchat_meta":{"chargedUsd":0.5,"balanceAfterUsd":4.0}}"""
        const val MODELS =
            """{"data":[""" +
                """{"id":"synthetic-text-1","zchat_model_type":"text",""" +
                """"zchat_pricing":{"inputPer1mUsd":1.0,"outputPer1mUsd":2.0}},""" +
                """{"id":"synthetic-image-1","zchat_model_type":"image","zchat_pricing":{"imagePerCallUsd":0.01}}]}"""

        val OWN_KEY_ID: String by lazy { AiSignInApi.keyId(AiSignInApi.derive(ByteArray(SEED_BYTES) { it.toByte() }).publicKey) }
    }
}
