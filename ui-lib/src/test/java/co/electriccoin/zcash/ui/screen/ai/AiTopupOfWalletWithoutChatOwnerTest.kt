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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ZC-1317: a linked AI account, on a saved wallet that has not claimed chat ownership (owner tag null), taps Top up. The
 * server answers 200 with the deposit address and the screen says "ZCHAT is still opening your wallet".
 *
 * WHY IT FAILS: AiAccountAccess.markPaid stops on `fence.tag == null`, although "no owner yet" is a state the fence
 * compares like any other and the wallet is saved. The paid mark is lost as well.
 *
 * Cases named "guard" pass today and must stay true: an address never counts across a removed wallet, a change of the
 * tag in either direction, a raised counter or another key, and an account that is not linked never asks for one.
 * Every key and account here is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiTopupOfWalletWithoutChatOwnerTest {
    private val store = AiTabAccessFixture.bareStore()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private val addressAnswered = CountDownLatch(1)
    private lateinit var main: ExecutorCoroutineDispatcher

    @Volatile private var tag: String? = null

    @Volatile private var counter = 7L

    @Volatile private var walletSaved = true

    @Volatile private var holdAddress = false

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path.orEmpty().substringBefore('?')) {
                        TOPUP_ADDRESS -> {
                            requests += TOPUP_ADDRESS
                            if (holdAddress) addressAnswered.await(HOLD_SECONDS, TimeUnit.SECONDS)
                            json(ADDRESS_ANSWER)
                        }
                        BALANCE -> json(BALANCE_ANSWER)
                        else -> json("""{"data":[]}""")
                    }
            }
        server.start()
    }

    @After
    fun tearDown() {
        addressAnswered.countDown()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    // ------------------------------------------------------------------------------------------ the bug: AiAccountAccess

    @Test
    fun `no owner yet - the address answer gives the key back`() =
        run {
            val access = linkedAccess()

            val answer = access.onTopupAddress(ticket(access))

            assertEquals("the address of a linked account was not shown", TOKEN to null, answer)
        }

    @Test
    fun `no owner yet - the address answer writes paid yes`() =
        run {
            val access = linkedAccess()

            access.onTopupAddress(ticket(access))

            assertEquals("the stored paid was not written", "yes", storedPaid())
        }

    // ------------------------------------------------------------------------------------------- the bug: the tab

    @Test
    fun `no owner yet - the tab shows the address and paid is yes`() =
        withTab { tab ->
            val shown = address(tab)

            assertEquals("the tab did not show the address", ADDRESS, (shown as? TopupAddressResult.Success)?.address)
            assertEquals("the stored paid was not written", "yes", storedPaid())
        }

    @Test
    fun `guard - a claimed wallet - the tab shows the address and paid is yes`() {
        tag = OWNER_X
        withTab { tab ->
            val shown = address(tab)

            assertEquals(ADDRESS, (shown as? TopupAddressResult.Success)?.address)
            assertEquals("yes", storedPaid())
        }
    }

    @Test
    fun `guard - no owner yet, the tag is set while the address is on its way - the tab does not show it`() =
        tabAcrossAChange { tag = OWNER_X }

    @Test
    fun `guard - no owner yet, the counter rises while the address is on its way - the tab does not show it`() =
        tabAcrossAChange { counter++ }

    @Test
    fun `guard - no owner yet, the wallet is removed while the address is on its way - the tab does not show it`() =
        tabAcrossAChange { walletSaved = false }

    @Test
    fun `guard - a claimed wallet, the owner record goes while the address is on its way - the tab does not show it`() {
        tag = OWNER_X
        tabAcrossAChange { tag = null }
    }

    // --------------------------------------------------------------------------- guards: the answer counts for its wallet

    @Test
    fun `guard - a claimed wallet - the address answer gives the key back and writes paid yes`() =
        run {
            tag = OWNER_X
            val access = linkedAccess()

            assertEquals(TOKEN to null, access.onTopupAddress(ticket(access)))
            assertEquals("yes", storedPaid())
        }

    @Test
    fun `guard - the wallet is no longer saved - stopped, nothing written`() = stoppedAcross { walletSaved = false }

    @Test
    fun `guard - a wipe raises the counter - stopped, nothing written`() = stoppedAcross { counter++ }

    @Test
    fun `guard - a wipe raises the counter and clears the pair - stopped, nothing written`() =
        stoppedAcross {
            counter++
            store.writeIfStillThisWallet({ true }) { removePair() }
        }

    @Test
    fun `guard - the tag goes from null to a wallet - stopped, nothing written`() = stoppedAcross { tag = OWNER_X }

    @Test
    fun `guard - the tag goes from a wallet to null - stopped, nothing written`() {
        tag = OWNER_X
        stoppedAcross { tag = null }
    }

    @Test
    fun `guard - the tag goes from one wallet to another - stopped, nothing written`() {
        tag = OWNER_X
        stoppedAcross { tag = OWNER_Y }
    }

    @Test
    fun `guard - another key was saved meanwhile - stopped, nothing written`() =
        stoppedAcross { store.saveCredentials(OTHER_TOKEN, ACCOUNT) }

    private fun stoppedAcross(change: () -> Unit) =
        run {
            val access = linkedAccess()
            val asked = ticket(access)
            change()
            val before = stored()

            val answer = access.onTopupAddress(asked)

            assertEquals("an address counted across the change", null to NOT_READY, answer)
            assertEquals("the answer wrote into the AI store", before, stored())
        }

    // ------------------------------------------------------------------------------- guards: the address is not asked for

    @Test
    fun `guard - no owner, no verdict - no key, the sentence, no call`() =
        notAsked { store.saveCredentials(TOKEN, ACCOUNT) }

    @Test
    fun `guard - no owner, verdict own - no key, the sentence, no call`() =
        notAsked { saved(verdict = "own") }

    @Test
    fun `guard - no owner, the link is another account's - no key, the sentence, no call`() =
        notAsked { saved(accountOfLink = "acct_SYNTHETIC-other") }

    @Test
    fun `guard - no owner, no saved key - no key, the sentence, no call`() = notAsked { }

    @Test
    fun `guard - no owner, the wallet is not saved - no key, no call`() =
        run {
            saved()
            walletSaved = false

            val answer = access().keyForTopup()

            assertEquals(null to NOT_READY, answer)
            assertEquals(emptyList<String>(), requests)
        }

    private fun notAsked(arrange: () -> Unit) =
        run {
            arrange()
            val before = stored()

            val answer = access().keyForTopup()

            assertNull("an account that is not linked got a key for the address", answer.first)
            assertEquals(NOT_LINKED, answer.second)
            assertEquals(emptyList<String>(), requests)
            assertEquals(before, stored())
        }

    // ---------------------------------------------------------------------------------------------- the arrangements

    private fun saved(
        verdict: String = "linked",
        accountOfLink: String = ACCOUNT,
    ) {
        store.saveCredentials(TOKEN, ACCOUNT)
        store.writeIfStillThisWallet({ true }) {
            putLinkStateJson("""{"accountId":"$accountOfLink","verdict":"$verdict","paid":"unknown"}""")
        }
    }

    private fun linkedAccess(): AiAccountAccess {
        saved()
        return access()
    }

    /** As the tab does it: the fence first, then the key. */
    private suspend fun ticket(access: AiAccountAccess): AiAccountAccess.Ticket {
        val asked = access.scopeNow()
        val (key, sentence) = access.keyForTopup()
        assertEquals("fixture: a linked account gets its key", TOKEN to null, key to sentence)
        return asked.forKey(checkNotNull(key))
    }

    private fun access(client: AiApiClient? = null): AiAccountAccess =
        AiAccountAccess(
            prefs = store,
            seed = { ByteArray(SEED_BYTES) { it.toByte() } },
            candidates = { emptyList() },
            ownerTag = { tag },
            scopeCounter = { counter },
            walletSaved = { walletSaved },
            clock = System::currentTimeMillis,
            send =
                client?.let { it::request }
                    ?: { method, path, _, _ ->
                        requests += "$method $path"
                        SERVER_ERROR to "{}"
                    },
        )

    private fun storedPaid(): String? = store.getLinkStateJson()?.let { JSONObject(it).getString("paid") }

    private fun stored() = listOf(store.getToken(), store.getUserId(), store.getLinkStateJson())

    private fun tabAcrossAChange(change: () -> Unit) =
        withTab { tab ->
            holdAddress = true
            val before = store.getLinkStateJson()
            val shown = CompletableDeferred<TopupAddressResult>()

            tab.loadTopupAddress { shown.complete(it) }
            assertTrue("the request never left", await { TOPUP_ADDRESS in requests })
            change()
            addressAnswered.countDown()

            assertEquals(
                "an address counted across the change",
                NOT_READY,
                (withTimeout(WAIT_MILLIS) { shown.await() } as? TopupAddressResult.Failure)?.error,
            )
            assertEquals("the answer wrote into the AI store", before, store.getLinkStateJson())
        }

    private suspend fun address(tab: AiTabVM): TopupAddressResult {
        val shown = CompletableDeferred<TopupAddressResult>()
        tab.loadTopupAddress { shown.complete(it) }
        return withTimeout(WAIT_MILLIS) { shown.await() }
    }

    private fun withTab(block: suspend (AiTabVM) -> Unit) =
        run {
            saved()
            val client = client()
            val tab = AiTabVM(store, bareImageStore(), client, access(client))
            try {
                assertTrue("the tab never got ready: ${tab.state.value.error}", await { tab.state.value.balanceLoaded })
                block(tab)
            } finally {
                addressAnswered.countDown()
                withTimeoutOrNull(WAIT_MILLIS) { tab.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
                client.close()
            }
        }

    // ---------------------------------------------------------------------------------------------- the harness

    private fun run(block: suspend () -> Unit): Unit =
        runBlocking {
            withTimeout(CASE_TIMEOUT_MILLIS) { block() }
        }

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
        const val TOKEN = "synthetic-token"
        const val OTHER_TOKEN = "synthetic-token-other"
        const val ACCOUNT = "acct_SYNTHETIC"
        const val ADDRESS = "synthetic-deposit-address"
        const val TOPUP_ADDRESS = "/api/v1/ai/topup/address"
        const val BALANCE = "/api/v1/ai/balance"
        const val ADDRESS_ANSWER = """{"address":"$ADDRESS","memo":"synthetic-memo","tiers":[5]}"""
        const val BALANCE_ANSWER = """{"balanceUsd":5.0,"freeTrialAvailable":false,"onFreeTrial":false}"""
        const val SERVER_ERROR = 500
        const val SEED_BYTES = 64
        const val HOLD_SECONDS = 15L
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val CASE_TIMEOUT_MILLIS = 60_000L
        val OWNER_X = "a".repeat(SEED_BYTES)
        val OWNER_Y = "b".repeat(SEED_BYTES)
        val NOT_READY = AiApiClient.SIGN_IN_TEXTS.getValue("ai_signin_wallet_not_ready")
        val NOT_LINKED = AiApiClient.OTHER_ACCOUNT_TEXTS.getValue("ai_topup_not_linked")
    }
}
