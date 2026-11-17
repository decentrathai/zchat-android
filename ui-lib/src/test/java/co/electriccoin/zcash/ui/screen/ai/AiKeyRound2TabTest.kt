package co.electriccoin.zcash.ui.screen.ai

import androidx.lifecycle.viewModelScope
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AI-KEY-S round 2 (W-97; train-AIKEY/S_ROUND2.md): the tab over the real AiAccountAccess and the real client, against a
 * mock server. Items 2 (ZC-1206), 4 (ZC-1208, GPT 4), 5 (GPT 3), 6 (ZC-1194), 15 and 21 (the top-up's ticket), 16 and 17
 * (the claim), the claim's half of item 12 and the client's half of item 9 (GPT 5).
 * Item numbers are in the case names; AiKeyRound2AccessTest holds the others.
 *
 * "Another path decided" is a call on the same AiAccountAccess from outside the tab, as the foreground observer and the
 * /ai shortcut make it: the tab's own copy of the key is then out of date.
 *
 * GUARDS, which pass at 7813669eb: every case named "guard". Item 4's top-up address is one: keyForTopup already resolves
 * the key at the tap.
 *
 * Item 6 is read from the source: handleAiCommand builds its client and finds its AiAccountAccess by itself, so no test
 * can put a server under it without changing it. Every key, account and figure is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiKeyRound2TabTest {
    private val server = MockWebServer()
    private val seen = CopyOnWriteArrayList<Seen>()
    private val routes = mutableMapOf<String, (String?) -> MockResponse>()
    private val holdBalanceOf = mutableMapOf<String, CountDownLatch>()
    private lateinit var main: ExecutorCoroutineDispatcher
    private val holdFirst = mutableMapOf<String, CountDownLatch>()
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
        routes[BALANCE] = { key -> json(balanceOf(key)) }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    val key = request.getHeader("Authorization")?.removePrefix("Bearer ")
                    seen += Seen(path, key)
                    if (path == BALANCE) holdBalanceOf[key]?.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    holdFirst.remove(path)?.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    return routes[path]?.invoke(key) ?: json("{}")
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        holdBalanceOf.values.forEach { it.countDown() }
        holdFirst.values.forEach { it.countDown() }
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    // --------------------------------------------------------------------- item 2, ZC-1206: another account's balance

    @Test
    fun `2 - a chat moves to a new account - the old balance and trial status are gone until the new account's arrive`() =
        withLinkedTab { tab, _ ->
            refusedOnChatThenNewAccount(chatMeta = """{"chargedUsd":$CHARGED_USD}""")
            holdBalanceOf[TOKEN_B] = CountDownLatch(1)

            tab.send("hi")
            assertTrue("the answer never arrived: ${tab.state.value.error}", await { tab.state.value.chatTurns.size == 2 })

            val shown = tab.state.value
            assertEquals(TOKEN_B, shown.token)
            assertEquals("the old account's balance reads as current", false to null, shown.balanceLoaded to shown.onFreeTrial)
        }

    @Test
    fun `2 - a chat moves to a new account - one balance call for that account`() =
        withLinkedTab { tab, _ ->
            refusedOnChatThenNewAccount(chatMeta = """{"chargedUsd":$CHARGED_USD,"balanceAfterUsd":$BALANCE_B}""")

            tab.send("hi")
            assertTrue("the answer never arrived: ${tab.state.value.error}", await { tab.state.value.chatTurns.size == 2 })
            delay(SETTLE_MILLIS)

            assertEquals(1, seen.count { it.path == BALANCE && it.key == TOKEN_B })
        }

    @Test
    fun `2 - the history sheet's refusal moves to a new account - one balance call for that account`() =
        withLinkedTab { tab, _ ->
            routes[TOPUP_HISTORY] = { key ->
                if (key == TOKEN_A) refusedKey() else json("""{"deposits":[]}""")
            }
            routes[SIGN_IN] = { json(signedIn(TOKEN_B, ID_B, created = true)) }

            tab.loadTopupHistory()
            assertTrue(await { seen.any { it.path == TOPUP_HISTORY && it.key == TOKEN_B } })
            delay(SETTLE_MILLIS)

            assertEquals(1, seen.count { it.path == BALANCE && it.key == TOKEN_B })
            assertEquals(BALANCE_B, tab.state.value.balanceUsd, 0.0)
        }

    @Test
    fun `2 guard - a newer key of the same account keeps the balance and the trial status`() =
        withLinkedTab { tab, _ ->
            routes[CHAT] = { key -> if (key == TOKEN_A) refusedKey() else json(chatAnswer("""{"chargedUsd":$CHARGED_USD}""")) }
            routes[SIGN_IN] = { json(signedIn(TOKEN_B, ID_A, created = false)) }
            holdBalanceOf[TOKEN_B] = CountDownLatch(1)

            tab.send("hi")
            assertTrue(await { tab.state.value.chatTurns.size == 2 })

            assertEquals(true to false, tab.state.value.balanceLoaded to tab.state.value.onFreeTrial)
        }

    // ------------------------------------------------------------------ item 4, ZC-1208, GPT 4: the key at the dispatch

    @Test
    fun `4 - a parked key - the balance chip's refresh does not send it`() =
        withParkedTab { tab ->
            tab.refreshBalanceNow()
            delay(SETTLE_MILLIS)
            assertEquals(emptyList<Seen>(), carriesKeyA())
        }

    @Test
    fun `4 - a parked key - the models' retry does not send it`() =
        withParkedTab { tab ->
            tab.refreshModels()
            delay(SETTLE_MILLIS)
            assertEquals(emptyList<Seen>(), carriesKeyA())
        }

    @Test
    fun `4 - a parked key - the top-up history does not send it`() =
        withParkedTab { tab ->
            tab.loadTopupHistory()
            delay(SETTLE_MILLIS)
            assertEquals(emptyList<Seen>(), carriesKeyA())
        }

    @Test
    fun `4 - a parked key - the top-up poll does not send it`() =
        withParkedTab { tab ->
            tab.startTopupPoll()
            delay(TOPUP_POLL_MILLIS + SETTLE_MILLIS)
            assertEquals(emptyList<Seen>(), carriesKeyA())
        }

    @Test
    fun `4 guard - a parked key - the top-up address does not send it and fails`() =
        withParkedTab { tab ->
            val result = CompletableDeferred<TopupAddressResult>()

            tab.loadTopupAddress { result.complete(it) }

            assertTrue(result.await() is TopupAddressResult.Failure)
            assertEquals(emptyList<Seen>(), carriesKeyA())
        }

    @Test
    fun `4 - a key parked during the start's retries - the next retry does not send it`() =
        withUndecidedTab(
            arrange = { routes[BALANCE] = { json(OUTAGE).setResponseCode(503) } },
            ready = { seen.any { it.path == BALANCE } },
        ) { tab, access ->
            parkKeyA(access)
            routes[BALANCE] = { key -> json(balanceOf(key)) }
            seen.clear()

            delay(FIRST_RETRY_MILLIS)

            assertFalse("the tab died", tab.state.value.storeUnavailable)
            assertEquals(emptyList<Seen>(), carriesKeyA())
        }

    @Test
    fun `4 - another path replaced the key - the balance chip's refresh sends the current one`() =
        withLinkedTab { tab, access ->
            routes[SIGN_IN] = { json(signedIn(TOKEN_B, ID_B, created = true)) }
            access.onRefused(TOKEN_A)
            seen.clear()

            tab.refreshBalanceNow()
            delay(SETTLE_MILLIS)

            assertEquals(listOf(TOKEN_B), seen.filter { it.path == BALANCE }.map { it.key })
        }

    @Test
    fun `4 - another path replaced the key - the top-up history sends the current one`() =
        withLinkedTab { tab, access ->
            routes[SIGN_IN] = { json(signedIn(TOKEN_B, ID_B, created = true)) }
            access.onRefused(TOKEN_A)
            seen.clear()

            tab.loadTopupHistory()
            delay(SETTLE_MILLIS)

            assertEquals(listOf(TOKEN_B), seen.filter { it.path == TOPUP_HISTORY }.map { it.key })
        }

    // ----------------------------------------------------------------------------- item 5, GPT 3: a late balance answer

    @Test
    fun `5 - a balance answer for the old key after the store moved on - the new account's link is untouched`() =
        withLinkedTab { tab, access ->
            val before = lateAnswerAfterAReplacement(tab, access)

            assertEquals("the old key's answer changed the new account", before, store.getLinkStateJson())
            assertEquals(TOKEN_B, store.getToken())
            assertEquals("a sign-in ran on the old key's answer", 1, seen.count { it.path == SIGN_IN })
        }

    @Test
    fun `5 - a balance answer for the old key after the store moved on - its figure is not shown`() =
        withLinkedTab { tab, access ->
            lateAnswerAfterAReplacement(tab, access)

            assertFalse("the old account's figure reads as the new one's", tab.state.value.balanceUsd == LATE_FIGURE)
        }

    @Test
    fun `5 - a balance answer across a change of the wallet scope - nothing written, no sign-in`() =
        withLinkedTab { tab, _ ->
            val before = store.getLinkStateJson()
            routes[BALANCE] = { json(lateAnswerWithNoKey()) }
            val gate = CountDownLatch(1)
            holdBalanceOf[TOKEN_A] = gate
            seen.clear()

            tab.refreshBalanceNow()
            assertTrue(await { seen.any { it.path == BALANCE } })
            counter++
            gate.countDown()
            delay(SETTLE_MILLIS)

            assertEquals("a late answer wrote across the fence", before, store.getLinkStateJson())
            assertEquals(0, seen.count { it.path == SIGN_IN })
        }

    @Test
    fun `5 guard - an answer in time with this wallet's key id still writes paid`() =
        withLinkedTab { tab, _ ->
            routes[BALANCE] = {
                json("""{"balanceUsd":$BALANCE_A,"onFreeTrial":false,"signInKeyId":"$OWN_KEY_ID","paid":true}""")
            }

            tab.refreshBalanceNow()

            assertTrue(await { "\"yes\"" in store.getLinkStateJson().orEmpty() })
        }

    // ------------------------------------------------------------------------------- item 6, ZC-1194: the /ai shortcut

    @Test
    fun `6 - the shortcut hands a refused chat to AiAccountAccess and repeats it`() {
        val chat =
            AiSignInApi.read(
                AiSignInApi.uiLib.resolve("src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"),
            )
        val body = chat.substringAfter("fun handleAiCommand(").substringBefore("\"AI command failed")

        assertTrue("the shortcut does not hand a 401 on", "onRefused(" in body)
        assertTrue("the shortcut does not read the refusal", ".refused" in body)
    }

    // ---------------------------------------------------------- items 15, 21: the top-up's answer belongs to its request

    @Test
    fun `15 guard - a wipe while the address is asked for - not shown, paid not written`() =
        lateAddress(disturb = { counter++ }) { first, before ->
            assertTrue(first is TopupAddressResult.Failure)
            assertEquals(before, store.getLinkStateJson())
        }

    @Test
    fun `15 guard - a change of wallet while the address is asked for - not shown, paid not written`() =
        lateAddress(disturb = { tag = "b".repeat(SEED_BYTES) }) { first, before ->
            assertTrue(first is TopupAddressResult.Failure)
            assertEquals(before, store.getLinkStateJson())
        }

    @Test
    fun `15 - a wipe, then a second request while the first is out - the first's address is not shown`() =
        withLinkedTab { tab, _ ->
            routes[TOPUP_ADDRESS] = { json(ADDRESS_ANSWER) }
            val gate = CountDownLatch(1)
            holdFirst[TOPUP_ADDRESS] = gate
            val first = CompletableDeferred<TopupAddressResult>()
            val second = CompletableDeferred<TopupAddressResult>()

            tab.loadTopupAddress { first.complete(it) }
            assertTrue(await { seen.any { it.path == TOPUP_ADDRESS } })
            counter++
            tab.loadTopupAddress { second.complete(it) }
            assertTrue(second.await() is TopupAddressResult.Success)
            gate.countDown()

            assertTrue("the old wallet's request took the new one's ticket", first.await() is TopupAddressResult.Failure)
        }

    @Test
    fun `15 guard - two requests of one account at once - both shown`() =
        withLinkedTab { tab, _ ->
            routes[TOPUP_ADDRESS] = { json(ADDRESS_ANSWER) }
            val gate = CountDownLatch(1)
            holdFirst[TOPUP_ADDRESS] = gate
            val first = CompletableDeferred<TopupAddressResult>()
            val second = CompletableDeferred<TopupAddressResult>()

            tab.loadTopupAddress { first.complete(it) }
            assertTrue(await { seen.any { it.path == TOPUP_ADDRESS } })
            tab.loadTopupAddress { second.complete(it) }
            assertTrue(second.await() is TopupAddressResult.Success)
            gate.countDown()

            assertTrue(first.await() is TopupAddressResult.Success)
        }

    @Test
    fun `21 - the pair changed while the address is asked for - not shown, the new pair is not marked paid`() =
        withLinkedTab { tab, access ->
            routes[TOPUP_ADDRESS] = { json(ADDRESS_ANSWER) }
            routes[SIGN_IN] = { json(signedIn(TOKEN_B, ID_B, created = true)) }
            val gate = CountDownLatch(1)
            holdFirst[TOPUP_ADDRESS] = gate
            val first = CompletableDeferred<TopupAddressResult>()

            tab.loadTopupAddress { first.complete(it) }
            assertTrue(await { seen.any { it.path == TOPUP_ADDRESS } })
            access.onRefused(TOKEN_A)
            val before = store.getLinkStateJson()
            gate.countDown()
            val shown = first.await()

            assertEquals("the new pair was marked paid for the old one's address", before, store.getLinkStateJson())
            assertTrue("the old account's address was shown", shown is TopupAddressResult.Failure)
        }

    private fun lateAddress(
        disturb: () -> Unit,
        check: (TopupAddressResult, String?) -> Unit,
    ) = withLinkedTab { tab, _ ->
        routes[TOPUP_ADDRESS] = { json(ADDRESS_ANSWER) }
        val gate = CountDownLatch(1)
        holdFirst[TOPUP_ADDRESS] = gate
        val first = CompletableDeferred<TopupAddressResult>()
        val before = store.getLinkStateJson()

        tab.loadTopupAddress { first.complete(it) }
        assertTrue(await { seen.any { it.path == TOPUP_ADDRESS } })
        disturb()
        gate.countDown()

        check(first.await(), before)
    }

    // ------------------------------------------------------------------ items 16, 17, 12: the claim, from the tap

    @Test
    fun `16 - the fence moves while the fingerprint is read - no claim`() =
        withLinkedTab { tab, _ ->
            var reads = 0

            tab.lookForOldCredit {
                reads++
                counter++
                FP_A
            }
            assertTrue(await { reads == 1 })
            delay(SETTLE_MILLIS)

            assertEquals("a claim went out across the fence", 0, seen.count { it.path == CLAIM })
        }

    @Test
    fun `16 - the wallet changes while the fingerprint is read - no claim`() =
        withLinkedTab { tab, _ ->
            var reads = 0

            tab.lookForOldCredit {
                reads++
                tag = "b".repeat(SEED_BYTES)
                FP_A
            }
            assertTrue(await { reads == 1 })
            delay(SETTLE_MILLIS)

            assertEquals("a claim went out across the fence", 0, seen.count { it.path == CLAIM })
        }

    @Test
    fun `16 guard - a tap with a steady fence sends one claim`() =
        withLinkedTab { tab, _ ->
            routes[CLAIM] = { json("""{"found":false}""") }

            tab.lookForOldCredit { FP_A }

            assertTrue(await { seen.any { it.path == CLAIM } })
        }

    @Test
    fun `17 - a second tap while a claim runs is ignored, and a tap after it works`() =
        withLinkedTab { tab, _ ->
            routes[CLAIM] = { json("""{"found":false}""") }
            val gate = CountDownLatch(1)
            holdFirst[CLAIM] = gate
            var reads = 0
            val fingerprint: suspend () -> String? = {
                reads++
                FP_A
            }

            tab.lookForOldCredit(fingerprint)
            assertTrue(await { seen.any { it.path == CLAIM } })
            tab.lookForOldCredit(fingerprint)
            delay(SETTLE_MILLIS)
            gate.countDown()
            delay(SETTLE_MILLIS)

            assertEquals("a second claim went out", 1, seen.count { it.path == CLAIM })
            assertEquals("the second tap read the fingerprint", 1, reads)

            tab.lookForOldCredit(fingerprint)
            assertTrue("the claim stayed blocked", await { seen.count { it.path == CLAIM } == 2 })
        }

    @Test
    fun `12 guard - the claim answered changed_meanwhile - no sentence, nothing stored`() =
        withLinkedTab { tab, _ ->
            routes[CLAIM] = { json("""{"error":"synthetic sentence","code":"changed_meanwhile"}""").setResponseCode(409) }
            val before = store.getToken() to store.getLinkStateJson()

            tab.lookForOldCredit { FP_A }
            assertTrue(await { seen.any { it.path == CLAIM } })
            delay(SETTLE_MILLIS)

            assertNull(tab.state.value.error)
            assertEquals(before, store.getToken() to store.getLinkStateJson())
        }

    // -------------------------------------------------------------------------- item 9, GPT 5: the client's half

    @Test
    fun `9 - a paid on the balance answer that is no JSON Boolean is not said`() =
        runBlocking {
            val client = client()
            try {
                listOf("\"unknown\"", "\"false\"", "\"true\"", "0", "1", "{}").forEach { value ->
                    routes[BALANCE] = { json("""{"balanceUsd":1.0,"signInKeyId":null,"paid":$value}""") }
                    val answer = client.balance(TOKEN_A) as BalanceResult.Success
                    assertNull("paid $value", answer.paid)
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun `9 guard - a real Boolean on the balance answer is said`() =
        runBlocking {
            val client = client()
            try {
                listOf(true, false).forEach { value ->
                    routes[BALANCE] = { json("""{"balanceUsd":1.0,"signInKeyId":null,"paid":$value}""") }
                    assertEquals(value, (client.balance(TOKEN_A) as BalanceResult.Success).paid)
                }
            } finally {
                client.close()
            }
        }

    // ---------------------------------------------------------------------------------------------- the arrangements

    private fun refusedOnChatThenNewAccount(chatMeta: String) {
        routes[CHAT] = { key -> if (key == TOKEN_A) refusedKey() else json(chatAnswer(chatMeta)) }
        routes[SIGN_IN] = { json(signedIn(TOKEN_B, ID_B, created = true)) }
    }

    /** The balance call of the tab's old key waits while another path replaces the key; then it answers. */
    private suspend fun lateAnswerAfterAReplacement(
        tab: AiTabVM,
        access: AiAccountAccess,
    ): String? {
        routes[BALANCE] = { key -> if (key == TOKEN_A) json(lateAnswerWithNoKey()) else json(balanceOf(key)) }
        routes[SIGN_IN] = { json(signedIn(TOKEN_B, ID_B, created = true)) }
        val gate = CountDownLatch(1)
        holdBalanceOf[TOKEN_A] = gate
        seen.clear()

        tab.refreshBalanceNow()
        assertTrue(await { seen.any { it.path == BALANCE } })
        access.onRefused(TOKEN_A)
        assertEquals(TOKEN_B, store.getToken())
        val before = store.getLinkStateJson()
        gate.countDown()
        delay(SETTLE_MILLIS)
        return before
    }

    private fun lateAnswerWithNoKey() = """{"balanceUsd":$LATE_FIGURE,"onFreeTrial":false,"signInKeyId":null,"paid":true}"""

    /** A open tab on key A, linked, with its balance and models. */
    private fun withLinkedTab(block: suspend (AiTabVM, AiAccountAccess) -> Unit) =
        withTab(
            arrange = {},
            ready = { tab ->
                tab.state.value.balanceLoaded &&
                    tab.state.value.selectedChatModel != null &&
                    "linked" in store.getLinkStateJson().orEmpty()
            },
            block = block,
        )

    /** A open tab on key A, which has no verdict yet: key/state answered 500 at the start. */
    private fun withUndecidedTab(
        arrange: () -> Unit,
        ready: () -> Boolean,
        block: suspend (AiTabVM, AiAccountAccess) -> Unit,
    ) = withTab(
        arrange = {
            routes[KEY_STATE] = { json(OUTAGE).setResponseCode(500) }
            arrange()
        },
        ready = { ready() },
        block = block,
    )

    /** A open tab still holding key A, which the foreground has just parked; the sign-in after it failed. */
    private fun withParkedTab(block: suspend (AiTabVM) -> Unit) =
        withUndecidedTab(
            arrange = {},
            ready = { seen.any { it.path == BALANCE } && seen.any { it.path == MODELS_PATH } },
        ) { tab, access ->
            assertTrue(await { tab.state.value.balanceLoaded && tab.state.value.selectedChatModel != null })
            parkKeyA(access)
            assertEquals("the tab must still hold the parked key", TOKEN_A, tab.state.value.token)
            seen.clear()
            block(tab)
        }

    private suspend fun parkKeyA(access: AiAccountAccess) {
        routes[KEY_STATE] = { json("""{"signInKeyId":"$FOREIGN_KEY_ID","paid":true}""") }
        routes[SIGN_IN] = { json(OUTAGE).setResponseCode(503) }
        access.onForeground()
        assertEquals(TOKEN_A, store.parkedToken(ID_A))
        assertNull(store.getToken())
    }

    private fun carriesKeyA(): List<Seen> = seen.filter { it.key == TOKEN_A }

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
            holdBalanceOf.values.forEach { it.countDown() }
            withTimeoutOrNull(WAIT_MILLIS) { tab.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            client.close()
        }
    }

    // ---------------------------------------------------------------------------------------------- the harness

    private data class Seen(
        val path: String,
        val key: String?,
    )

    private fun balanceOf(key: String?): String {
        val figure = if (key == TOKEN_A) BALANCE_A else BALANCE_B
        return """{"balanceUsd":$figure,"freeTrialAvailable":false${if (key == TOKEN_A) ""","onFreeTrial":false""" else ""}}"""
    }

    private fun chatAnswer(meta: String) =
        """{"choices":[{"message":{"content":"hello"}}],"usage":{"prompt_tokens":3,"completion_tokens":5},"zchat_meta":$meta}"""

    private fun signedIn(
        token: String,
        id: String,
        created: Boolean,
    ) = """{"userId":"$id","token":"$token","created":$created}"""

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
        const val CHALLENGE = "/api/v1/ai/auth/key/challenge"
        const val KEY_STATE = "/api/v1/ai/auth/key/state"
        const val SIGN_IN = "/api/v1/ai/auth/key/signin"
        const val BALANCE = "/api/v1/ai/balance"
        const val MODELS_PATH = "/api/v1/ai/models"
        const val CHAT = "/api/v1/ai/chat"
        const val TOPUP_HISTORY = "/api/v1/ai/topup/status"
        const val TOPUP_ADDRESS = "/api/v1/ai/topup/address"
        const val CLAIM = "/api/v1/ai/auth/key/claim"
        const val ADDRESS_ANSWER = """{"address":"synthetic-deposit-address","memo":"synthetic-memo","tiers":[5]}"""

        const val TOKEN_A = "synthetic-token-a"
        const val ID_A = "acct_SYNTHETIC-a"
        const val TOKEN_B = "synthetic-token-b"
        const val ID_B = "acct_SYNTHETIC-b"
        const val BALANCE_A = 5.0
        const val BALANCE_B = 0.2
        const val LATE_FIGURE = 9.0
        const val CHARGED_USD = 0.002
        const val OUTAGE = """{"error":"internal"}"""

        const val SEED_BYTES = 64
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val SETTLE_MILLIS = 800L
        const val FIRST_RETRY_MILLIS = 1_500L
        const val TOPUP_POLL_MILLIS = 20_000L
        const val HOLD_SECONDS = 15L

        const val MODELS =
            """{"data":[""" +
                """{"id":"synthetic-text-1","zchat_model_type":"text",""" +
                """"zchat_pricing":{"inputPer1mUsd":1.0,"outputPer1mUsd":2.0}},""" +
                """{"id":"synthetic-image-1","zchat_model_type":"image","zchat_pricing":{"imagePerCallUsd":0.01}}]}"""

        val OWN_KEY_ID: String by lazy { AiSignInApi.keyId(AiSignInApi.derive(ByteArray(SEED_BYTES) { it.toByte() }).publicKey) }
        val FOREIGN_KEY_ID = AiSignInApi.sha256Hex("synthetic-foreign-key".toByteArray())
        val FP_A = AiSignInApi.sha256Hex("synthetic-address-a".toByteArray())
    }
}
