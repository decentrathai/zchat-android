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
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ZC-1409 (swarm 1 Nov, lens ai-tab) — an AI chat stream that ends early is saved as a complete reply; a mid-stream reset
 * throws the partial away and Retry pays again.
 *
 * AiApiClient.readChatStream loops `while (!channel.isClosedForRead)` with `readLine() ?: break` and keeps no flag for
 * `data: [DONE]` or a finish_reason: any non-empty reply returns ChatResult.Success, and AiTabVM.send stores it as an
 * ordinary assistant turn (and feeds it back as context). A thrown readLine (connection reset) becomes
 * ChatResult.Failure; AiTabVM.failSend clears streamingReply and marks the user turn failed, unlike stopGeneration
 * which keeps the partial, so Retry sends the same prompt again: a second charge if the server billed the cut stream.
 *
 * Wanted: (1) a stream that closes without [DONE] is not saved as a complete reply (the turn is marked cut off or the
 * send fails); (2) after a reset mid-stream the partial reply is kept, or Retry does not send the same prompt again.
 *
 * Seams: the real AiTabVM + AiAccountAccess + AiApiClient against a MockWebServer (the AiKeyStaleTurnRetryTest
 * harness); a "cut-off mark" is AiChatTurn.failed or any boolean getter of AiChatTurn named like
 * cut/incomplete/truncated/partial that reads true (or `complete` that reads false). Every key and prompt is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiStreamCutOffIsNotACompleteReplyTest {
    private val server = MockWebServer()
    private val seen = CopyOnWriteArrayList<Seen>()
    private val routes = ConcurrentHashMap<String, (String?) -> MockResponse>()
    private lateinit var main: ExecutorCoroutineDispatcher
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
                    seen += Seen(path, request.body.readUtf8())
                    return routes[path]?.invoke(request.getHeader("Authorization")) ?: json("{}")
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    @Test
    fun `a stream that closes without DONE is not saved as a complete reply`() =
        withLinkedTab { tab ->
            routes[CHAT] = { sse(sseChunks(listOf(PARTIAL_1, " and then"), done = false)) }

            tab.send(PROMPT)
            assertTrue("the send never finished", await { !tab.state.value.sending })
            delay(SETTLE_MILLIS)

            val unmarkedReply =
                tab.state.value.chatTurns.filter {
                    it.role == AiChatTurn.ROLE_ASSISTANT && PARTIAL_1 in it.content && !isMarkedCutOff(it)
                }
            assertTrue(
                "a stream cut before [DONE] was saved as a complete assistant reply: ${unmarkedReply.map { it.content }}",
                unmarkedReply.isEmpty(),
            )
        }

    @Test
    fun `a reset mid-stream keeps the partial reply or Retry does not pay again for the same prompt`() =
        withLinkedTab { tab ->
            val tail = (1..TAIL_CHUNKS).map { " synthetic-tail-$it" }
            routes[CHAT] = {
                sse(sseChunks(listOf(PARTIAL_1) + tail, done = true))
                    .throttleBody(THROTTLE_BYTES, THROTTLE_MILLIS, TimeUnit.MILLISECONDS)
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }

            tab.send(PROMPT)
            assertTrue("the send never finished", await { !tab.state.value.sending })
            delay(SETTLE_MILLIS)
            val partialKept =
                tab.state.value.chatTurns
                    .any { it.role == AiChatTurn.ROLE_ASSISTANT && PARTIAL_1 in it.content }

            seen.clear()
            routes[CHAT] = { sse(sseChunks(listOf("synthetic full reply"), done = true)) }
            tab.retryFailed()
            delay(SETTLE_MILLIS)
            val paidAgain = seen.count { it.path == CHAT && PROMPT in it.body }

            assertTrue(
                "after a reset mid-stream the partial reply was thrown away (kept=$partialKept) and Retry sent the " +
                    "same prompt again ($paidAgain chat request(s)): a second charge",
                partialKept || paidAgain == 0,
            )
        }

    // ---------------------------------------------------------------------------------------------- the harness

    private fun isMarkedCutOff(turn: AiChatTurn): Boolean {
        if (turn.failed) return true
        val booleanTypes = setOf(java.lang.Boolean.TYPE, Boolean::class.javaObjectType)
        return AiChatTurn::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType in booleanTypes }
            .any { m ->
                val name =
                    m.name
                        .removePrefix("get")
                        .removePrefix("is")
                        .lowercase()
                val value = runCatching { m.invoke(turn) as Boolean? }.getOrNull() ?: return@any false
                when {
                    name == "complete" || name == "completed" -> !value
                    Regex("cut|incomplete|truncat|partial").containsMatchIn(name) -> value
                    else -> false
                }
            }
    }

    private fun sseChunks(
        deltas: List<String>,
        done: Boolean,
    ): String =
        buildString {
            deltas.forEach { d -> append("data: {\"choices\":[{\"delta\":{\"content\":\"$d\"}}]}\n\n") }
            if (done) append("data: [DONE]\n\n")
        }

    private fun sse(body: String): MockResponse = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun json(body: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private data class Seen(
        val path: String,
        val body: String,
    )

    private fun withLinkedTab(block: suspend (AiTabVM) -> Unit) =
        runBlocking {
            store.saveCredentials(TOKEN, ACCOUNT_ID)
            val client = client()
            val access =
                AiAccountAccess(
                    prefs = store,
                    seed = { ByteArray(SEED_BYTES) { it.toByte() } },
                    candidates = { emptyList() },
                    ownerTag = { "a".repeat(SEED_BYTES) },
                    scopeCounter = { 7L },
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
                withTimeoutOrNull(WAIT_MILLIS) { tab.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
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

    private companion object {
        const val CHALLENGE = "/api/v1/ai/auth/key/challenge"
        const val KEY_STATE = "/api/v1/ai/auth/key/state"
        const val BALANCE_PATH = "/api/v1/ai/balance"
        const val MODELS_PATH = "/api/v1/ai/models"
        const val CHAT = "/api/v1/ai/chat"

        const val TOKEN = "synthetic-token-zc1409"
        const val ACCOUNT_ID = "acct_SYNTHETIC-zc1409"
        const val PROMPT = "synthetic-prompt-zc1409"
        const val THROTTLE_BYTES = 64L
        const val THROTTLE_MILLIS = 100L
        const val PARTIAL_1 = "synthetic-partial-head"
        const val TAIL_CHUNKS = 40
        const val BALANCE = 5.0

        const val SEED_BYTES = 64
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val SETTLE_MILLIS = 800L

        const val MODELS =
            """{"data":[""" +
                """{"id":"synthetic-text-1","zchat_model_type":"text",""" +
                """"zchat_pricing":{"inputPer1mUsd":1.0,"outputPer1mUsd":2.0}},""" +
                """{"id":"synthetic-image-1","zchat_model_type":"image","zchat_pricing":{"imagePerCallUsd":0.01}}]}"""

        val OWN_KEY_ID: String by lazy { AiSignInApi.keyId(AiSignInApi.derive(ByteArray(SEED_BYTES) { it.toByte() }).publicKey) }
    }
}
