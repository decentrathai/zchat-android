package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections

/**
 * Round 9 R9-6 (REVIEW_R8 F14 = device D-F11) — a disappearing message stays in an open chat past its time.
 *
 * Round 8 devices: with a 1-minute timer, a message stayed 2+ minutes in the open chat, while the timer dialog (D10)
 * promises it goes after a minute. At 8ecb482aa the expiry filter (B17) runs only inside convertToConversations, and
 * that runs only when the wallet's transactions, the relay rows, the hidden set, the paid set or the timers emit:
 * nothing re-runs it when a message's time is up, so it leaves on the next block or relay message instead.
 *
 * Wanted: the conversation render (ChatViewModel.conversationRenderFlow, the flow loadConversations shows) arms a
 * ticker to the nearest expiry each time it renders and re-renders when it fires, so an expired row leaves the chat
 * with no other emission. The expiry reads the ViewModel's clock (ttlClock; the wall clock when unset), and the
 * render runs on renderDispatcher (Dispatchers.Default when unset), so this test drives both on virtual time. Both
 * are reached by name, so this file compiles before they exist. All addresses and texts are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpiredMessageLeavesOpenChatTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val T0 = 1_900_000_000_000L // synthetic wall clock at the start of each test
        val CONTACT: String = SyntheticUnifiedAddress.mainnet(9601)
        val ME: String = SyntheticUnifiedAddress.mainnet(9602)
        const val TEXT = "synthetic disappearing relay text"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a one-minute message leaves the open chat after 61 seconds with nothing else emitting`() =
        runTest {
            val chat = OpenChat(this, ttlSeconds = 60L)
            chat.start(relayRow("nmsg-r96-a", sentAt = T0))

            assertEquals("fixture: the row is shown when sent", listOf(TEXT), chat.texts())

            advanceTimeBy(59_000)
            runCurrent()
            assertEquals("the row left before its minute was up", listOf(TEXT), chat.texts())

            advanceTimeBy(2_000) // t0 + 61 s, and no wallet, relay, hidden, paid or timer emission
            runCurrent()
            assertEquals("the expired row is still in the open chat at t0 + 61 s", emptyList<String>(), chat.texts())
            chat.stop()
        }

    @Test
    fun `each row leaves at its own time`() =
        runTest {
            val chat = OpenChat(this, ttlSeconds = 60L)
            chat.start(relayRow("nmsg-r96-b1", sentAt = T0, text = "first"), relayRow("nmsg-r96-b2", sentAt = T0 + 30_000, text = "second"))

            advanceTimeBy(61_000)
            runCurrent()
            assertEquals(listOf("second"), chat.texts())

            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(emptyList<String>(), chat.texts())
            chat.stop()
        }

    @Test
    fun `control - with no timer the row stays and the render does not tick`() =
        runTest {
            val chat = OpenChat(this, ttlSeconds = null)
            chat.start(relayRow("nmsg-r96-c", sentAt = T0))
            val rendersAfterStart = chat.renders()

            advanceTimeBy(10 * 60_000L)
            runCurrent()

            assertEquals(listOf(TEXT), chat.texts())
            assertEquals("the render re-ran with no timer and no emission", rendersAfterStart, chat.renders())
        }

    @Test
    fun `control - a one-hour message is still there after 61 seconds`() =
        runTest {
            val chat = OpenChat(this, ttlSeconds = 3_600L)
            chat.start(relayRow("nmsg-r96-d", sentAt = T0))

            advanceTimeBy(61_000)
            runCurrent()

            assertEquals(listOf(TEXT), chat.texts())
            chat.stop()
        }

    // ---------------------------------------------------------------- fixture

    private fun relayRow(
        id: String,
        sentAt: Long,
        text: String = TEXT,
    ) = ChatMessage(
        id = id,
        txId = null,
        text = text,
        timestamp = Instant.ofEpochMilli(sentAt),
        isOutgoing = false,
        peerAddress = CONTACT,
        isPending = false,
        status = MessageStatus.SENT,
        authenticated = true,
    )

    /** The chat with [CONTACT], its conversation list rendered by the ViewModel's real render flow. */
    private inner class OpenChat(
        private val test: TestScope,
        ttlSeconds: Long?,
    ) {
        private val ttl = ttlSeconds?.let { ZchatPreferences.DisappearingTtl(it, T0 - 3_600_000L) }
        private val emissions = Collections.synchronizedList(mutableListOf<List<Conversation>>())
        private val hidden: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        private var job: kotlinx.coroutines.Job? = null

        private val prefs =
            ScanPrefs(
                mapOf(
                    "getDisappearingTtl" to { a -> if (a[0] == CONTACT) ttl else null },
                    "getDisappearingTtls" to { _ -> MutableStateFlow(if (ttl != null) mapOf(CONTACT to ttl) else emptyMap()) },
                    "getDisplayName" to { _ -> "Synthetic Contact" },
                    "hideMessages" to { a ->
                        @Suppress("UNCHECKED_CAST")
                        hidden += a[0] as Set<String>
                    },
                    "getExpiredMessageIds" to { _ -> emptySet<String>() },
                    "getOrPutMessageExpiryAnchorMillis" to { a -> a[1] },
                    "getOrPutPendingTxFirstSeenMillis" to { a -> a[1] },
                    "getConversationMode" to { _ -> co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN },
                ),
            )

        suspend fun start(vararg rows: ChatMessage) {
            val vm = PaymentHarness.paymentViewModel(PaymentPrefs(CONTACT), PaymentSeam(CONTACT))
            PaymentHarness.setField(vm, "zchatPreferences", prefs.proxy)
            PaymentHarness.setField(vm, "contactBook", ScanPassHarness.noContacts())
            PaymentHarness.setField(vm, "transactionRepository", repository())
            PaymentHarness.setField(vm, "synchronizerProvider", ScanPassHarness.synchronizerProvider(synchronizer()))
            PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(ME))
            PaymentHarness.setField(vm, "decryptedTextCache", java.util.concurrent.ConcurrentHashMap<String, String>())
            PaymentHarness.setField(
                vm,
                "decryptFailedPeers",
                java.util.concurrent.ConcurrentHashMap
                    .newKeySet<String>()
            )
            PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(emptySet()))
            // Field initialiser the allocation skipped: the restore re-check a render may schedule bumps it.
            PaymentHarness.setField(vm, "restoreRecheck", MutableStateFlow(0))
            PaymentHarness.setField(vm, "peerStatuses", MutableStateFlow<Map<String, Any>>(emptyMap()))
            PaymentHarness.setField(vm, "pendingMessages", MutableStateFlow(rows.toList()))
            // The expiry follows virtual time: the ViewModel's clock and the render dispatcher.
            val clock: () -> Long = { T0 + test.testScheduler.currentTime }
            setNamed(vm, "ttlClock", clock, "ChatViewModel has no clock the expiry render reads (ttlClock)")
            setNamed(
                vm,
                "renderDispatcher",
                test.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher,
                "ChatViewModel has no render dispatcher (renderDispatcher)",
            )
            val method =
                ChatViewModel::class.java.declaredMethods
                    .firstOrNull { it.name == "conversationRenderFlow" && it.parameterCount == 2 }
                    ?.apply { isAccessible = true }
                    ?: throw AssertionError(
                        "ChatViewModel has no conversationRenderFlow: nothing re-renders the chat when a message expires"
                    )

            @Suppress("UNCHECKED_CAST")
            val flow =
                try {
                    method.invoke(vm, flowOf(emptyList<Transaction>() to true), ME) as Flow<List<Conversation>>
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            job = test.backgroundScope.launch { flow.collect { emissions += it } }
            test.runCurrent()
            assertTrue("fixture: the chat rendered", emissions.isNotEmpty())
        }

        fun texts(): List<String> =
            emissions
                .last()
                .filter { it.peerAddress == CONTACT }
                .flatMap { it.messages }
                .filterNot { it.isSystemNote }
                .map { it.text }

        fun renders(): Int = emissions.size

        fun stop() {
            job?.cancel()
        }
    }

    private fun setNamed(
        vm: ChatViewModel,
        name: String,
        value: Any,
        missing: String,
    ) {
        val field =
            runCatching { ChatViewModel::class.java.getDeclaredField(name) }.getOrNull()
                ?: throw AssertionError(missing)
        field.isAccessible = true
        field.set(vm, value)
    }

    private fun repository(): TransactionRepository =
        Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getMemos" -> emptyList<String>()
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "ExpiryRepository"
                else -> error("unexpected TransactionRepository.${method.name}")
            }
        } as TransactionRepository

    private fun synchronizer(): Synchronizer =
        Proxy.newProxyInstance(
            Synchronizer::class.java.classLoader,
            arrayOf(Synchronizer::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getFullyScannedHeight", "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(ScanPassHarness.SCANNED))
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "ExpirySynchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer
}
