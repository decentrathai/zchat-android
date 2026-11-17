package co.electriccoin.zcash.ui.screen.ai

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.CompletableDeferred
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AI key round 3 (W-97; train-AIKEY/ROUND3.md item 1, the tab's half of item 5; GPT 1, Grok 2 and 4 of review-final1): every AI
 * request belongs to the wallet it went out for. A refusal of wallet A's request never resends it on B's key, and an answer
 * to A's request writes nothing once B is the wallet.
 *
 * WHY IT FAILS TODAY: AiAccountAccess.onRefused answers the saved key whenever it differs from the refused one, under the
 * fence of its own start, so B's key comes back and withRefusal sends A's payload on it. Trial, models, history, chat and
 * picture answers are written with no check at all. refreshBalance and the top-up address have the check already.
 *
 * "A wallet change" is what the fence reads: the owner tag and the scope counter move, and the store holds B's pair, linked.
 * The tab and the real AiAccountAccess over the real client, against a mock server. Not pinned: the name of the helper, and
 * what the tab says after a stopped request. The /ai shortcut is read from the source: handleAiCommand builds its own client
 * and finds its AiAccountAccess by itself. Every key, account and figure is synthetic.
 *
 * GUARDS, which pass today and pin what the fix must not change: every case named "guard".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiKeyRound3TabTest {
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
        routes[BALANCE] = { json("""{"balanceUsd":$BALANCE_A,"freeTrialAvailable":false,"onFreeTrial":false}""") }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    val key = request.getHeader("Authorization")?.removePrefix("Bearer ")
                    seen += Seen(path, key)
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

    // -------------------------------------------------------------------- a refusal of A's request is not resent on B

    @Test
    fun `1 chat - A's chat refused after the wallet changed - it is not sent on B's key`() =
        refusedAcrossAWalletChange(CHAT, ok = chatAnswer("hello", META)) { it.send("hi") }

    @Test
    fun `1 image - A's picture refused after the wallet changed - it is not sent on B's key`() =
        refusedAcrossAWalletChange(IMAGE, ok = imageAnswer()) { it.generateImage("a cat") }

    @Test
    fun `1 balance - A's balance refused after the wallet changed - it is not asked on B's key`() =
        refusedAcrossAWalletChange(BALANCE, ok = """{"balanceUsd":$BALANCE_A}""") { it.refreshBalanceNow() }

    @Test
    fun `1 models - A's models refused after the wallet changed - they are not asked on B's key`() =
        refusedAcrossAWalletChange(MODELS_PATH, ok = MODELS) { it.refreshModels() }

    @Test
    fun `1 history - A's history refused after the wallet changed - it is not asked on B's key`() =
        refusedAcrossAWalletChange(TOPUP_HISTORY, ok = """{"deposits":[]}""") { it.loadTopupHistory() }

    @Test
    fun `1 guard top-up - A's address refused after the wallet changed - it is not asked on B's key`() =
        refusedAcrossAWalletChange(TOPUP_ADDRESS, ok = ADDRESS_ANSWER) { tab ->
            val answered = CompletableDeferred<TopupAddressResult>()
            tab.loadTopupAddress { answered.complete(it) }
        }

    @Test
    fun `1 guard - a chat refused after the wallet changed - the tab does not stay in sending`() =
        withLinkedTab { tab, _ ->
            routes[CHAT] = { key -> if (key == TOKEN_A) refusedKey() else json(chatAnswer("hello", META)) }
            val gate = holdNext(CHAT)

            tab.send("hi")
            assertTrue(await { seen.any { it.path == CHAT } })
            changeWallet()
            gate.countDown()

            assertTrue("the composer stays locked", await { !tab.state.value.sending })
        }

    // ------------------------------------------------------------------------- an answer to A's request writes nothing

    @Test
    fun `1 chat - A's answer after the wallet changed - neither the reply nor A's balance lands on B`() =
        withLinkedTab { tab, _ ->
            routes[CHAT] = { json(chatAnswer(LATE_REPLY, """{"chargedUsd":0.5,"balanceAfterUsd":$LATE_FIGURE}""")) }
            val gate = holdNext(CHAT)

            tab.send("hi")
            assertTrue(await { seen.any { it.path == CHAT } })
            changeWallet()
            gate.countDown()
            delay(SETTLE_MILLIS)

            assertFalse("A's reply is saved in B's history", LATE_REPLY in store.getConversationsJson().orEmpty())
            val turns = tab.state.value.chatTurns
            assertFalse("A's reply is on B's screen", turns.any { it.content == LATE_REPLY })
            assertFalse("A's balance reads as B's", tab.state.value.balanceUsd == LATE_FIGURE)
        }

    @Test
    fun `1 image - A's picture after the wallet changed - neither the picture nor A's balance lands on B`() =
        withLinkedTab { tab, _ ->
            routes[IMAGE] = { json(imageAnswer()) }
            val gate = holdNext(IMAGE)

            tab.generateImage("a cat")
            assertTrue(await { seen.any { it.path == IMAGE } })
            changeWallet()
            gate.countDown()
            delay(SETTLE_MILLIS)

            assertEquals("A's picture is in B's gallery", emptyList<AiImageItem>(), tab.state.value.images)
            assertFalse("A's picture is saved in B's list", LATE_URL in store.getImagesJson().orEmpty())
            assertFalse("A's balance reads as B's", tab.state.value.balanceUsd == LATE_FIGURE)
        }

    @Test
    fun `1 models - A's list after the wallet changed - it is not written to the screen`() =
        withLinkedTab { tab, _ ->
            routes[MODELS_PATH] = { json(LATE_MODELS) }
            val gate = holdNext(MODELS_PATH)
            seen.clear()

            tab.refreshModels()
            assertTrue(await { seen.any { it.path == MODELS_PATH } })
            changeWallet()
            gate.countDown()
            delay(SETTLE_MILLIS)

            val models = tab.state.value.models
            assertFalse("A's models are on B's picker", models.any { it.id == LATE_MODEL })
        }

    @Test
    fun `1 history - A's deposits after the wallet changed - they are not shown`() =
        withLinkedTab { tab, _ ->
            routes[TOPUP_HISTORY] = { json("""{"deposits":[{"id":"$LATE_DEPOSIT","status":"credited"}]}""") }
            val gate = holdNext(TOPUP_HISTORY)
            seen.clear()

            tab.loadTopupHistory()
            assertTrue(await { seen.any { it.path == TOPUP_HISTORY } })
            changeWallet()
            gate.countDown()
            delay(SETTLE_MILLIS)

            val shown = (tab.topupHistory.value as? TopupHistoryState.Data)?.deposits.orEmpty()
            assertFalse("A's deposits are on B's sheet", shown.any { it.id == LATE_DEPOSIT })
        }

    @Test
    fun `1 trial - A's history after the wallet changed - the trial flag stays unknown`() {
        var gate: CountDownLatch? = null
        withTab(
            arrange = {
                routes[BALANCE] = { json("""{"balanceUsd":$BALANCE_A,"freeTrialAvailable":false}""") }
                routes[TOPUP_HISTORY] = { json("""{"deposits":[]}""") }
                gate = holdNext(TOPUP_HISTORY)
            },
            ready = { seen.any { it.path == TOPUP_HISTORY } },
        ) { tab, _ ->
            changeWallet()
            gate?.countDown()
            delay(SETTLE_MILLIS)

            assertNull("A's deposits decided B's model locks", tab.state.value.onFreeTrial)
        }
    }

    // ---------------------------------------------------------------------------------------------- the /ai shortcut

    @Test
    fun `1 shortcut - source - the refusal goes under the request's ticket, not on a bare key`() {
        val shortcut = shortcutSource()
        assertFalse("a refusal on a bare key answers the next wallet's key", "access.onRefused(token)" in shortcut)
        assertTrue(
            "the shortcut notes no fence before its request",
            Regex("""(?i)scopeNow|ticket""").containsMatchIn(shortcut.substringBefore("client.chat(")),
        )
    }

    @Test
    fun `1 shortcut - source - the retry looks at the ticket before it sends`() {
        val shortcut = shortcutSource()
        assertTrue(
            "the shortcut's second send checks nothing",
            Regex("""(?i)stillCurrent|ticket""").containsMatchIn(shortcut.substringAfter("val first")),
        )
    }

    // ---------------------------------------------------------------------------------------------- item 5, the tab

    @Test
    fun `5 guard - the tab shows an owed notice and tells the access, so the next open does not say it again`() {
        var opened: Pair<String?, String?>? = null
        withTab(
            arrange = {
                store.writeIfStillThisWallet({ true }) {
                    putLinkStateJson("""{"accountId":"$ID_A","verdict":"linked","paid":"no","owed":"ai_notice_other_wallet_dropped"}""")
                }
            },
            ready = { it.state.value.error != null },
        ) { tab, access ->
            assertEquals(aiSignInText("ai_notice_other_wallet_dropped"), tab.state.value.error)
            assertTrue(await { "owed" !in store.getLinkStateJson().orEmpty() })
            opened = access.open()
        }
        assertNull("said twice", opened?.second)
    }

    // ---------------------------------------------------------------------------------------------- the arrangements

    /** A's request waits; the wallet changes to B; A's request is refused; B's key must not carry it. */
    private fun refusedAcrossAWalletChange(
        path: String,
        ok: String,
        request: (AiTabVM) -> Unit,
    ) = withLinkedTab { tab, _ ->
        routes[path] = { key -> if (key == TOKEN_A) refusedKey() else json(ok) }
        val gate = holdNext(path)
        seen.clear()

        request(tab)
        assertTrue("the request never left", await { seen.any { it.path == path && it.key == TOKEN_A } })
        changeWallet()
        gate.countDown()
        delay(SETTLE_MILLIS)

        assertEquals("A's request went out on B's key", emptyList<Seen>(), seen.filter { it.path == path && it.key == TOKEN_B })
    }

    private fun holdNext(path: String): CountDownLatch = CountDownLatch(1).also { hold[path] = it }

    private fun changeWallet() {
        tag = "b".repeat(SEED_BYTES)
        counter++
        store.saveCredentials(TOKEN_B, ID_B)
        store.writeIfStillThisWallet({ true }) { putLinkStateJson("""{"accountId":"$ID_B","verdict":"linked","paid":"yes"}""") }
    }

    private fun withLinkedTab(block: suspend (AiTabVM, AiAccountAccess) -> Unit) =
        withTab(
            arrange = {},
            ready = { tab ->
                tab.state.value.balanceLoaded &&
                    tab.state.value.selectedChatModel != null &&
                    tab.state.value.selectedImageModel != null &&
                    "linked" in store.getLinkStateJson().orEmpty()
            },
            block = block,
        )

    private fun withTab(
        arrange: () -> Unit,
        ready: (AiTabVM) -> Boolean,
        block: suspend (AiTabVM, AiAccountAccess) -> Unit,
    ) = runBlocking {
        arrange()
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
            assertTrue("the tab never got ready: ${tab.state.value.error}", await { ready(tab) })
            block(tab, access)
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
    )

    private fun shortcutSource(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        val source = SourceScan.stripComments(File(checkNotNull(dir), CHAT_VIEW_MODEL).readText())
        return source
            .substringAfter("fun handleAiCommand(")
            .substringBefore("private fun replaceAiMessage(")
            .also { assertTrue("fixture: handleAiCommand", "client.chat(" in it) }
    }

    private fun chatAnswer(
        reply: String,
        meta: String,
    ) = """{"choices":[{"message":{"content":"$reply"}}],"usage":{"prompt_tokens":3,"completion_tokens":5},"zchat_meta":$meta}"""

    private fun imageAnswer() = """{"data":[{"url":"$LATE_URL"}],"zchat_meta":{"chargedUsd":0.5,"balanceAfterUsd":$LATE_FIGURE}}"""

    private fun refusedKey() = json("""{"error":"Invalid token"}""").setResponseCode(401)

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
        const val CHAT_VIEW_MODEL = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
        const val CHALLENGE = "/api/v1/ai/auth/key/challenge"
        const val KEY_STATE = "/api/v1/ai/auth/key/state"
        const val BALANCE = "/api/v1/ai/balance"
        const val MODELS_PATH = "/api/v1/ai/models"
        const val CHAT = "/api/v1/ai/chat"
        const val IMAGE = "/api/v1/ai/image"
        const val TOPUP_HISTORY = "/api/v1/ai/topup/status"
        const val TOPUP_ADDRESS = "/api/v1/ai/topup/address"
        const val ADDRESS_ANSWER = """{"address":"synthetic-deposit-address","memo":"synthetic-memo","tiers":[5]}"""
        const val META = """{"chargedUsd":0.002}"""

        const val TOKEN_A = "synthetic-token-a"
        const val ID_A = "acct_SYNTHETIC-a"
        const val TOKEN_B = "synthetic-token-b"
        const val ID_B = "acct_SYNTHETIC-b"
        const val BALANCE_A = 5.0
        const val LATE_FIGURE = 9.0
        const val LATE_REPLY = "synthetic-late-reply"
        const val LATE_URL = "https://ai.synthetic.invalid/late.png"
        const val LATE_MODEL = "synthetic-late-model"
        const val LATE_DEPOSIT = "synthetic-late-deposit"

        const val SEED_BYTES = 64
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val SETTLE_MILLIS = 800L
        const val HOLD_SECONDS = 15L

        const val MODELS =
            """{"data":[""" +
                """{"id":"synthetic-text-1","zchat_model_type":"text",""" +
                """"zchat_pricing":{"inputPer1mUsd":1.0,"outputPer1mUsd":2.0}},""" +
                """{"id":"synthetic-image-1","zchat_model_type":"image","zchat_pricing":{"imagePerCallUsd":0.01}}]}"""
        const val LATE_MODELS =
            """{"data":[{"id":"$LATE_MODEL","zchat_model_type":"text",""" +
                """"zchat_pricing":{"inputPer1mUsd":1.0,"outputPer1mUsd":2.0}}]}"""

        val OWN_KEY_ID: String by lazy { AiSignInApi.keyId(AiSignInApi.derive(ByteArray(SEED_BYTES) { it.toByte() }).publicKey) }
    }
}
