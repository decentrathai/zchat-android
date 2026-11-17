package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import kotlin.coroutines.CoroutineContext

/**
 * ZC-946 part 1 (PERF-SPEC revision 2 section 3): the sent bubble waits for a whole history pass.
 *
 * HONOR, perf-20261014: a send's pending row reaches `pendingMessages` within 20 ms of the tap, but the bubble is drawn
 * only from the output of the next finished pass (`_chatListState.value = state`), 2.8 to 4.4 s later. Wanted: the
 * screens draw a DISPLAY flow, `ChatViewModel.displayChatList()`, that shows a send's own pending row at once, truthfully
 * "sending" (status SENDING, pending, marked optimistic so the bubble offers no action), until a finished pass has it;
 * `_chatListState` keeps meaning "the last finished pass" and stays the only thing money, key and trust code reads.
 *
 * The rows go through the view model's REAL render flow (`conversationRenderFlow`, the pass loadConversations shows)
 * into `_chatListState` as loadConversations writes it; the pass runs on `renderDispatcher`, here a gate the test holds
 * shut, so "while the pass runs" is exact. Only outgoing rows with a send id (`pending_`, `nostr-out-`, `nmsg-`, an
 * attachment upload) in a chat that already exists are overlaid; nothing is written and nothing is paid. The display
 * flow and the optimistic mark are reached by name, so this file compiles before they exist. Every address and text is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SentBubbleBeforeTheRebuildTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val T0 = 1_900_000_000_000L
        val CONTACT: String = SyntheticUnifiedAddress.mainnet(9461)
        val STRANGER: String = SyntheticUnifiedAddress.mainnet(9462)
        val ME: String = SyntheticUnifiedAddress.mainnet(9463)
        const val HELLO = "synthetic hello from the contact"
        const val TEXT = "synthetic text sent at the tap"
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
    fun `an Open send shows its bubble as sending while the pass still runs`() =
        runTest {
            val chat = Chat(this)
            chat.start()
            chat.gate.close()

            chat.send(out("nostr-out-1"))

            val bubbles = chat.bubbles(chat.display.last())
            assertEquals("the sent text is not on screen while the pass runs", 1, bubbles.size)
            assertEquals(MessageStatus.SENDING, bubbles.single().status)
            assertTrue("the early bubble is not marked pending", bubbles.single().isPending)
            assertTrue("the early bubble is not marked optimistic (it would offer Retry, reply, delete)", optimistic(bubbles.single()))
            assertTrue("fixture: the pass was still held", chat.gate.holding())
            chat.stop()
        }

    @Test
    fun `the re-key while the pass runs keeps one bubble that shows the row's own state, never more than it is`() =
        runTest {
            val chat = Chat(this)
            chat.start()
            chat.gate.close()
            val mark = chat.display.size

            chat.send(out("nostr-out-2"))
            chat.rekey("nostr-out-2", "nmsg-rumor-2", MessageStatus.SENT)

            val seen = chat.display.drop(mark)
            assertTrue("no emission while the pass ran", seen.isNotEmpty())
            seen.forEachIndexed { i, state ->
                val bubbles = chat.bubbles(state)
                assertTrue("emission $i holds ${bubbles.size} bubbles for one text", bubbles.size <= 1)
            }
            val last = chat.bubbles(seen.last()).single()
            // Review 19 Oct, finding 1: a row the relays took is drawn as sent, not as still sending.
            assertEquals("a sent row was drawn as more or less than sent", MessageStatus.SENT, last.status)
            assertFalse("a sent row was drawn as pending", last.isPending)
            assertTrue("the early row offers actions before the pass read it", optimistic(last))
            chat.stop()
        }

    @Test
    fun `a send that failed before the pass read it is never drawn early, so it never says sending`() =
        runTest {
            val chat = Chat(this)
            chat.start()
            chat.gate.close()
            val before = chat.live.value

            chat.send(out("nostr-out-2f"))
            chat.rekey("nostr-out-2f", "nmsg-rumor-2f", MessageStatus.FAILED)

            // Review 19 Oct, finding 1: its "not sent" and Retry come from the pass (F4), never an early "sending".
            assertSame("a failed send is still drawn early", before, chat.display.last())
            chat.gate.open()
            runCurrent()
            assertEquals("the pass's own status", MessageStatus.FAILED, chat.bubbles(chat.live.value).single().status)
            chat.stop()
        }

    @Test
    fun `the draft the user just sent is not shown on the chat's row next to the early row`() =
        runTest {
            val chat = Chat(this)
            chat.start()
            chat.gate.close()
            // The last finished pass still holds the typed text as the chat's draft (HONOR 20 Oct: "Draft: <text>" for
            // about 150 ms on the chat list before the sent text).
            val live = chat.live.value as ChatListState.Success
            chat.live.value =
                live.copy(conversations = live.conversations.map { if (it.peerAddress == CONTACT) it.copy(draft = TEXT) else it })

            chat.send(out("nostr-out-d"))

            val shown = (chat.display.last() as ChatListState.Success).conversations.single { it.peerAddress == CONTACT }
            assertEquals("fixture: the early row is the chat's last message", TEXT, shown.lastMessage?.text)
            assertEquals("the sent text still shows as the chat's draft", null, shown.draft)
            chat.stop()
        }

    @Test
    fun `source - the bubble menu never opens for an early row, also not when the pass's row takes over`() {
        val source =
            listOf(
                File("src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"),
                File("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"),
            ).first { it.exists() }.readText()
        val bubble = source.substringAfter("private fun MessageBubble(").substringBefore("\n@Composable")
        // Review 19 Oct, finding 2: a long-press on the early bubble must not leave the menu armed for the pass's row.
        assertFalse(
            "a long-press on an early bubble still arms the menu",
            Regex("""onLongClick = \{ showMenu = true \}""").containsMatchIn(bubble),
        )
        assertTrue(
            "the menu state is not keyed on the early mark, so it survives the take-over",
            bubble.contains("remember(message.optimistic)"),
        )
    }

    @Test
    fun `once the pass has the row the display is the pass and the pass decides the status`() =
        runTest {
            val chat = Chat(this)
            chat.start()
            chat.gate.close()
            chat.send(out("nostr-out-3"))
            chat.rekey("nostr-out-3", "nmsg-rumor-3", MessageStatus.FAILED)
            val mark = chat.display.size

            chat.gate.open()
            runCurrent()

            chat.display.drop(mark).forEach { assertTrue("two bubbles for one text", chat.bubbles(it).size <= 1) }
            val live = chat.live.value
            assertSame("with nothing left to show early, the display is not the last finished pass", live, chat.display.last())
            val row = chat.bubbles(live).single()
            assertEquals("the pass's own status", MessageStatus.FAILED, row.status)
            assertFalse(optimistic(row))
            chat.stop()
        }

    @Test
    fun `a re-key that lands after the pass shows one bubble in every emission`() =
        runTest {
            val chat = Chat(this)
            chat.start()
            chat.send(out("nostr-out-4"))
            runCurrent()
            assertEquals("fixture: the pass has the row", 1, chat.bubbles(chat.live.value).size)
            chat.gate.close()
            val mark = chat.display.size

            chat.rekey("nostr-out-4", "nmsg-rumor-4", MessageStatus.SENT)

            val seen = chat.display.drop(mark)
            assertTrue("fixture: the re-key emitted", seen.isNotEmpty())
            seen.forEach { assertEquals("the re-keyed row was shown twice", 1, chat.bubbles(it).size) }
            chat.stop()
        }

    @Test
    fun `rows that are not a send of the user and a first message are never shown early`() =
        runTest {
            val chat = Chat(this)
            chat.start()
            chat.gate.close()
            val before = chat.live.value

            chat.send(out("tunnel-wait-5"))
            chat.send(out("calllog-5"))
            chat.send(out("ai-user-5"))
            chat.send(out("nostr-out-5", peer = STRANGER))
            chat.send(out("nmsg-in-5", outgoing = false))

            assertSame("a row that is no send of the user changed the display", before, chat.display.last())
            chat.stop()
        }

    @Test
    fun `a row the finished pass received but did not show is not shown again`() =
        runTest {
            val chat = Chat(this)
            chat.start()
            chat.gate.close()
            val live = chat.live.value as ChatListState.Success
            // The pass that produced `live` took this row and dropped it (deduped against a mined row, hidden).
            chat.live.value = withRenderedPendingIds(live, setOf("pending_6"))

            chat.send(out("pending_6"))

            assertTrue("a row the pass consumed came back early", chat.bubbles(chat.display.last()).isEmpty())
            chat.stop()
        }

    @Test
    fun `source - only loadConversations writes the last finished pass`() {
        val source =
            listOf(
                File("src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"),
                File("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"),
            ).first { it.exists() }.readText()
        assertEquals("_chatListState gained a writer", 2, Regex("""_chatListState\.value\s*=""").findAll(source).count())
    }

    // ---------------------------------------------------------------- fixture

    private fun out(
        id: String,
        peer: String = CONTACT,
        outgoing: Boolean = true,
    ) = ChatMessage(
        id = id,
        txId = null,
        text = TEXT,
        timestamp = Instant.ofEpochMilli(T0 + 60_000),
        isOutgoing = outgoing,
        peerAddress = peer,
        isPending = outgoing,
        status = if (outgoing) MessageStatus.SENDING else MessageStatus.SENT,
    )

    private fun incoming() =
        ChatMessage(
            id = "nmsg-hello",
            txId = null,
            text = HELLO,
            timestamp = Instant.ofEpochMilli(T0),
            isOutgoing = false,
            peerAddress = CONTACT,
            status = MessageStatus.SENT,
        )

    /** A dispatcher that holds every task while shut: the pass that runs on it does not end until [open]. */
    private class Gate(
        private val inner: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        @Volatile private var shut = false
        private val held = mutableListOf<Pair<CoroutineContext, Runnable>>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            if (shut) synchronized(held) { held += context to block } else inner.dispatch(context, block)
        }

        fun close() {
            shut = true
        }

        fun open() {
            shut = false
            val tasks = synchronized(held) { held.toList().also { held.clear() } }
            tasks.forEach { (c, b) -> inner.dispatch(c, b) }
        }

        fun holding(): Boolean = synchronized(held) { held.isNotEmpty() }
    }

    private inner class Chat(
        private val test: TestScope,
    ) {
        val gate = Gate(test.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher)
        val display: MutableList<ChatListState> = Collections.synchronizedList(mutableListOf())
        val pending = MutableStateFlow(listOf(incoming()))
        val live = MutableStateFlow<ChatListState>(ChatListState.Loading)
        private val jobs = mutableListOf<Job>()

        private val prefs =
            ScanPrefs(
                mapOf(
                    "getDisappearingTtls" to { _ -> MutableStateFlow(emptyMap<String, Any>()) },
                    "getDisplayName" to { _ -> "Synthetic Contact" },
                    "getExpiredMessageIds" to { _ -> emptySet<String>() },
                    "getOrPutMessageExpiryAnchorMillis" to { a -> a[1] },
                    "getOrPutPendingTxFirstSeenMillis" to { a -> a[1] },
                    "getConversationMode" to { _ -> co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN },
                ),
            )

        suspend fun start() {
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
            PaymentHarness.setField(vm, "restoreRecheck", MutableStateFlow(0))
            PaymentHarness.setField(vm, "peerStatuses", MutableStateFlow<Map<String, Any>>(emptyMap()))
            PaymentHarness.setField(vm, "pendingMessages", pending)
            PaymentHarness.setField(vm, "_chatListState", live)
            PaymentHarness.setField(vm, "ttlClock", { T0 + 120_000 })
            PaymentHarness.setField(vm, "renderDispatcher", gate)

            val render =
                ChatViewModel::class.java.declaredMethods
                    .first { it.name == "conversationRenderFlow" && it.parameterCount == 2 }
                    .apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val pass = invoke { render.invoke(vm, flowOf(emptyList<Transaction>() to true), ME) } as Flow<List<Conversation>>
            // What loadConversations' last step writes: the conversations of the finished pass.
            jobs +=
                test.backgroundScope.launch {
                    pass.collect { conversations ->
                        live.value = ChatListState.Success(conversations = conversations, currentUserAddress = ME)
                    }
                }
            test.runCurrent()
            assertTrue("fixture: the first pass finished", live.value is ChatListState.Success)

            val show =
                ChatViewModel::class.java.declaredMethods
                    .firstOrNull { it.name == "displayChatList" && it.parameterCount == 0 }
                    ?: throw AssertionError(
                        "ChatViewModel has no display flow (displayChatList): the sent bubble waits for the whole pass",
                    )

            @Suppress("UNCHECKED_CAST")
            val shown = invoke { show.invoke(vm) } as Flow<ChatListState>
            jobs += test.backgroundScope.launch { shown.collect { display += it } }
            test.runCurrent()
        }

        fun send(row: ChatMessage) {
            pending.update { it + row }
            test.runCurrent()
        }

        fun rekey(
            from: String,
            to: String,
            status: MessageStatus,
        ) {
            pending.update { list -> list.map { if (it.id == from) it.copy(id = to, isPending = false, status = status) else it } }
            test.runCurrent()
        }

        fun bubbles(state: ChatListState): List<ChatMessage> =
            (state as? ChatListState.Success)
                ?.conversations
                ?.filter { it.peerAddress == CONTACT }
                ?.flatMap { it.messages }
                ?.filter { it.text == TEXT }
                .orEmpty()

        fun stop() {
            gate.open()
            jobs.forEach { it.cancel() }
        }
    }

    private fun optimistic(m: ChatMessage): Boolean {
        val field =
            runCatching { ChatMessage::class.java.getDeclaredField("optimistic") }.getOrNull()
                ?: throw AssertionError("ChatMessage has no optimistic mark: an early bubble cannot refuse its actions")
        field.isAccessible = true
        return field.getBoolean(m)
    }

    private fun withRenderedPendingIds(
        s: ChatListState.Success,
        ids: Set<String>,
    ): ChatListState.Success {
        val field =
            runCatching { ChatListState.Success::class.java.getDeclaredField("renderedPendingIds") }.getOrNull()
                ?: throw AssertionError("ChatListState.Success has no renderedPendingIds: the display cannot tell a consumed row")
        field.isAccessible = true
        val copy = s.copy()
        field.set(copy, ids)
        return copy
    }

    private fun invoke(call: () -> Any?): Any? =
        try {
            call()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun repository(): TransactionRepository =
        Proxy.newProxyInstance(TransactionRepository::class.java.classLoader, arrayOf(TransactionRepository::class.java)) {
            p,
            method,
            args,
            ->
            when (method.name) {
                "getMemos" -> emptyList<String>()
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "OverlayRepository"
                else -> error("unexpected TransactionRepository.${method.name}")
            }
        } as TransactionRepository

    private fun synchronizer(): Synchronizer =
        Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) {
            p,
            method,
            args,
            ->
            when (method.name) {
                "getFullyScannedHeight", "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(ScanPassHarness.SCANNED))
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "OverlaySynchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer
}
