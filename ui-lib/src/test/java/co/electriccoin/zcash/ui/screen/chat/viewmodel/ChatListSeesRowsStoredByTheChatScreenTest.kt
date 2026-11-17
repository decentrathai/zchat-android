package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences.PendingMessageData
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-946 part 4 (PERF-SPEC revision 2 section 4; perf-20261014 "Preview after a send": the Chats row still showed the
 * older message 100 s after a send in an Open chat, until a Wallet -> Chats round trip rebuilt the list).
 *
 * The chat screen and the chat list are two ChatViewModels. A send in the chat writes its row to the store
 * (`addPendingMessage`), but the list loaded the store's rows once, at its start, so its pass never has the new row.
 * Wanted: every view model follows the store's pending rows (`ZchatPreferences.pendingRowsVersion`, raised by every
 * pending-row write of the store) and takes the user's SETTLED relay sends another screen stored (outgoing `nmsg-`
 * rows no longer in flight, SENT or FAILED, as `NostrChatBridge.emitLocalMessage` hands a tunnel flush to every view
 * model today) into its `pendingMessages`, the pass's existing input; part 1's display shows such a row at once as
 * "sending" until the list's own pass has it.
 *
 * Never taken: a row still in flight (`nostr-out-`, `pending_`, any pending row), whose sender is the other view model
 * (taking it, the loader's mapping would mark it "ZChat was closed" with a Retry while it is still being sent), a
 * payment request, an incoming row. A row this view model holds itself is never replaced or removed; a row it took is
 * replaced by the store's later version and removed when the store drops it (a delete in the chat).
 *
 * The list's view model with its REAL render flow and the store as a map behind the preferences proxy; the follower and
 * the version are reached by name, so this file compiles before they exist. Every address and text is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListSeesRowsStoredByTheChatScreenTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val T0 = 1_900_000_000_000L
        val CONTACT: String = SyntheticUnifiedAddress.mainnet(9471)
        val ME: String = SyntheticUnifiedAddress.mainnet(9473)
        const val HELLO = "synthetic hello from the contact"
        const val TEXT = "synthetic text the chat screen sent"
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
    fun `an Open send the chat screen stored is the chat's newest row on the list at once`() =
        runTest {
            val list = ListVm(this)
            list.start()
            list.gate.close()

            list.store(stored("nmsg-rumor-1", status = "SENT"))

            val chat = list.chat(list.display.last())
            assertEquals("the list does not show the text the chat screen sent", TEXT, chat.lastMessage?.text)
            // Review 19 Oct, finding 1: a row the relays took is drawn early as sent, never as still sending.
            assertEquals("the early row is not drawn as what it is (sent)", MessageStatus.SENT, chat.lastMessage?.status)

            list.gate.open()
            runCurrent()
            val live = list.live.value
            val row = list.chat(live).messages.single { it.text == TEXT }
            assertEquals("the list's own pass does not have the row", MessageStatus.SENT, row.status)
            assertSame("with the pass holding the row the display is the pass", live, list.display.last())
            list.stop()
        }

    @Test
    fun `a row still in flight, a payment request and an incoming row are never taken`() =
        runTest {
            val list = ListVm(this)
            list.start()
            val before = list.pendingIds()

            list.store(stored("nostr-out-2", status = "SENDING", pending = true))
            list.store(stored("pending_2", status = null, pending = true))
            list.store(stored("nmsg-rumor-2s", status = "SENDING", pending = true))
            list.store(stored("nmsg-rumor-2r", status = "SENT", requestZat = 5_000L))
            list.store(stored("nmsg-rumor-2i", status = "SENT", outgoing = false))

            assertEquals("the list took a row it does not own", before, list.pendingIds())
            list.stop()
        }

    @Test
    fun `a later store version replaces the taken row and a delete in the chat removes it`() =
        runTest {
            val list = ListVm(this)
            list.start()
            list.store(stored("nmsg-rumor-3", status = "FAILED"))
            assertEquals("fixture: the row was taken", MessageStatus.FAILED, list.held("nmsg-rumor-3")?.status)

            list.store(stored("nmsg-rumor-3", status = "SENT"))
            assertEquals("the store's later version did not reach the list", MessageStatus.SENT, list.held("nmsg-rumor-3")?.status)

            list.drop("nmsg-rumor-3")
            assertEquals("a row deleted in the chat stayed on the list", null, list.held("nmsg-rumor-3"))
            list.stop()
        }

    @Test
    fun `rows the list holds itself survive every store change`() =
        runTest {
            val list = ListVm(this)
            val own = out("nmsg-own-4", MessageStatus.FAILED)
            val callLog = out("calllog-4", MessageStatus.SENT)
            list.start(own, callLog)

            list.store(stored("nmsg-own-4", status = "SENT"))
            list.store(stored("nmsg-rumor-4", status = "SENT"))
            list.drop("nmsg-rumor-4")
            list.drop("nmsg-own-4")

            assertEquals("the list's own row was replaced from the store", own, list.held("nmsg-own-4"))
            assertEquals("the list's in-memory row was removed", callLog, list.held("calllog-4"))
            list.stop()
        }

    @Test
    fun `a message deleted in the chat is never taken, and a taken one leaves the list when it is deleted`() =
        runTest {
            val list = ListVm(this)
            list.start()
            list.hide("nmsg-rumor-5h")
            list.store(stored("nmsg-rumor-5h", status = "SENT"))
            assertEquals("a message the user deleted was taken", null, list.held("nmsg-rumor-5h"))

            list.store(stored("nmsg-rumor-5k", status = "SENT"))
            assertTrue("fixture: the row was taken", list.held("nmsg-rumor-5k") != null)
            list.hide("nmsg-rumor-5k") // hideMessage / hideChat in the chat: the store keeps the row
            assertEquals("a message the user deleted stayed on the list", null, list.held("nmsg-rumor-5k"))
            list.stop()
        }

    /** ZC-1353: the list loaded the waiting row itself; Delete in the chat left its chat on the list, opening "Conversation not found". */
    @Test
    fun `a chat whose only row was a waiting first message leaves the list when the chat deletes that row`() =
        runTest {
            val id = "pending_1353"
            val list = ListVm(this)
            list.start(out(id, MessageStatus.SENDING).copy(isPending = true, waitsForFunds = true), withIncoming = false)
            list.store(stored(id, status = "SENDING", pending = true))
            assertEquals("fixture: the list does not show the waiting row", id, list.chat(list.live.value).lastMessage?.id)

            list.drop(id)
            list.hide(id)
            runCurrent()

            val left = (list.live.value as ChatListState.Success).conversations.count { it.peerAddress == CONTACT }
            assertEquals("the deleted row's chat stays on the list as a dead row", 0, left)
            list.stop()
        }

    @Test
    fun `the taken rows are read and built on the store dispatcher, never on the main thread`() =
        runTest {
            // A main dispatcher of its own (not unconfined), so work resumed on main is not mistaken for work done on
            // the store dispatcher that resumed it.
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val list = ListVm(this)
            list.start()
            list.store(stored("nmsg-rumor-6a", status = "SENT"))
            list.store(stored("nmsg-rumor-6b", status = "FAILED"))

            assertTrue("fixture: the rows were taken", list.held("nmsg-rumor-6a") != null && list.held("nmsg-rumor-6b") != null)
            assertTrue("fixture: no row was built", list.rowReads.isNotEmpty())
            assertTrue("a taken row was built outside the store dispatcher (${list.rowReads})", list.rowReads.all { it })
            list.stop()
        }

    @Test
    fun `source - every pending-row write of the store raises the version`() {
        val source = sourceOf("ZchatPreferences.kt")
        val impl = source.substringAfter("class ZchatPreferencesImpl(")
        // Review 19 Oct, finding 3: hiding a message or a chat changes what the list may show too.
        val writers =
            listOf(
                "addPendingMessage",
                "commitPendingMessage",
                "removePendingMessage",
                "removePendingMessages",
                "clearPendingMessages",
                "hideMessage",
                "hideMessages",
                "unhideMessage",
            )
        for (name in writers) {
            val start = impl.indexOf("override fun $name(")
            if (start < 0) fail("ZchatPreferencesImpl has no $name")
            val rest = impl.substring(start)
            val body = rest.substring(0, rest.indexOf("override fun ", 1).takeIf { it > 0 } ?: rest.length)
            assertTrue(
                "ZchatPreferencesImpl.$name does not raise pendingRowsVersion, so the chat list never hears of the row",
                body.contains("markPendingRowsChanged()"),
            )
        }
    }

    // ---------------------------------------------------------------- fixture

    private fun stored(
        id: String,
        status: String?,
        pending: Boolean = false,
        outgoing: Boolean = true,
        requestZat: Long? = null,
    ) = PendingMessageData(
        id = id,
        text = TEXT,
        timestampMillis = T0 + 60_000,
        peerAddress = CONTACT,
        isOutgoing = outgoing,
        isPending = pending,
        status = status,
        submitTracked = true,
        paymentRequestAmountZatoshi = requestZat,
    )

    private fun out(
        id: String,
        status: MessageStatus,
    ) = ChatMessage(
        id = id,
        txId = null,
        text = "synthetic row the list holds $id",
        timestamp = Instant.ofEpochMilli(T0 + 30_000),
        isOutgoing = true,
        peerAddress = CONTACT,
        status = status,
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
        private val held = mutableListOf<Pair<kotlin.coroutines.CoroutineContext, Runnable>>()

        override fun dispatch(
            context: kotlin.coroutines.CoroutineContext,
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
    }

    /** Runs every task on [inner] with [flag] set, so a read can tell it ran on the store dispatcher. */
    private class Marked(
        private val inner: CoroutineDispatcher,
        private val flag: ThreadLocal<Boolean>,
    ) : CoroutineDispatcher() {
        override fun dispatch(
            context: kotlin.coroutines.CoroutineContext,
            block: Runnable,
        ) {
            inner.dispatch(context) {
                flag.set(true)
                try {
                    block.run()
                } finally {
                    flag.set(false)
                }
            }
        }
    }

    /** The chat list's view model; [rows] is the store both screens share. */
    private inner class ListVm(
        private val test: TestScope,
    ) {
        private val testDispatcher = test.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher
        val gate = Gate(testDispatcher)
        val display: MutableList<ChatListState> = Collections.synchronizedList(mutableListOf())
        val live = MutableStateFlow<ChatListState>(ChatListState.Loading)
        private val pending = MutableStateFlow(listOf<ChatMessage>())
        private val rows = ConcurrentHashMap<String, PendingMessageData>()
        private val jobs = mutableListOf<Job>()
        private val hidden: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        private val onStoreDispatcher = ThreadLocal<Boolean>()

        /** One entry per stored row built into a ChatMessage: true when it was built on the store dispatcher. */
        val rowReads: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

        private val prefs =
            ScanPrefs(
                mapOf(
                    "getDisappearingTtls" to { _ -> MutableStateFlow(emptyMap<String, Any>()) },
                    "getDisplayName" to { _ -> "Synthetic Contact" },
                    "getExpiredMessageIds" to { _ -> emptySet<String>() },
                    "getOrPutMessageExpiryAnchorMillis" to { a -> a[1] },
                    "getOrPutPendingTxFirstSeenMillis" to { a -> a[1] },
                    "getConversationMode" to { _ -> co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN },
                    "getPendingMessages" to { _ -> rows.values.sortedBy { it.timestampMillis } },
                    "getNostrReactions" to { _ ->
                        rowReads += onStoreDispatcher.get() == true
                        emptyList<Any>()
                    },
                    "getHiddenMessageIds" to { _ -> HashSet(hidden) },
                ),
            )

        suspend fun start(
            vararg own: ChatMessage,
            withIncoming: Boolean = true,
        ) {
            pending.value = (if (withIncoming) listOf(incoming()) else emptyList()) + own
            val vm = PaymentHarness.paymentViewModel(PaymentPrefs(CONTACT), PaymentSeam(CONTACT))
            PaymentHarness.setField(vm, "zchatPreferences", prefs.proxy)
            PaymentHarness.setField(vm, "contactBook", ScanPassHarness.noContacts())
            PaymentHarness.setField(vm, "transactionRepository", repository())
            PaymentHarness.setField(vm, "synchronizerProvider", ScanPassHarness.synchronizerProvider(synchronizer()))
            PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(ME))
            PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            PaymentHarness.setField(vm, "decryptFailedPeers", ConcurrentHashMap.newKeySet<String>())
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
            jobs +=
                test.backgroundScope.launch {
                    pass.collect { conversations ->
                        live.value = ChatListState.Success(conversations = conversations, currentUserAddress = ME)
                    }
                }
            test.runCurrent()
            assertTrue("fixture: the first pass finished", live.value is ChatListState.Success)
            jobs += test.backgroundScope.launch { vm.displayChatList().collect { display += it } }

            val storeSeam = runCatching { ChatViewModel::class.java.getDeclaredField("storeReadDispatcher") }.getOrNull()
            storeSeam?.apply { isAccessible = true }?.set(vm, Marked(testDispatcher, onStoreDispatcher))
            val follow =
                ChatViewModel::class.java.declaredMethods
                    .firstOrNull { it.name == "followStoredSends" && it.parameterCount == 0 }
                    ?.apply { isAccessible = true }
                    ?: throw AssertionError(
                        "ChatViewModel does not follow the rows another screen stores (followStoredSends): the list " +
                            "never sees a send made in the chat",
                    )
            (invoke { follow.invoke(vm) } as? Job)?.let { jobs += it }
            test.runCurrent()
        }

        fun store(row: PendingMessageData) {
            rows[row.id] = row
            markChanged()
        }

        /** What hideMessage / hideChat in the chat do: the store keeps the row, the hidden set gains its id. */
        fun hide(id: String) {
            hidden += id
            markChanged()
        }

        fun drop(id: String) {
            rows.remove(id)
            markChanged()
        }

        /** What the store's own writers do after a write. */
        private fun markChanged() {
            runCatching { ZchatPreferences.Companion::class.java.getMethod("markPendingRowsChanged") }
                .getOrNull()
                ?.invoke(ZchatPreferences.Companion)
            test.runCurrent()
        }

        fun pendingIds(): List<String> = pending.value.map { it.id }

        fun held(id: String): ChatMessage? = pending.value.firstOrNull { it.id == id }

        fun chat(state: ChatListState): Conversation =
            (state as ChatListState.Success).conversations.single { it.peerAddress == CONTACT }

        fun stop() {
            gate.open()
            jobs.forEach { it.cancel() }
        }
    }

    private fun sourceOf(name: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val file =
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/$name") }
                .firstOrNull { it.isFile }
                ?: throw AssertionError("could not find $name from ${start.path}")
        return file.readText()
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
                "toString" -> "StoredRowsRepository"
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
                "toString" -> "StoredRowsSynchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer
}
