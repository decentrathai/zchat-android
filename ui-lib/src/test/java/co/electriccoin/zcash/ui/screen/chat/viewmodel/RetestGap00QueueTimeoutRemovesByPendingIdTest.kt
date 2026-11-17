package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * RED regression pin for the queue-retry TIMEOUT cleanup in ChatViewModel.doSendMessage
 * (ChatViewModel.kt ~line 7217):
 *
 *     synchronized(messageQueue) { messageQueue.removeAll { it.pendingId == pendingId } }
 *
 * When a queued send's per-waiter `withTimeout(QUEUE_RETRY_TIMEOUT_MS)` fires, the catch branch must
 * fail THIS waiter's OWN message keyed by its `pendingId` — NOT `removeAt(0)`. With BUG-8a queuing
 * fresh sends, several pending-blocked sends have concurrent timeout waiters; a blind `removeAt(0)`
 * evicts whatever happens to sit at the head of the queue (a different, possibly about-to-succeed
 * message) while the timed-out waiter's own entry survives — exactly inverted.
 *
 * `doSendMessage`, `messageQueue` (a `private data class QueuedMessage`) and the timeout handler are
 * all private, and the load-bearing removal is inline inside a `viewModelScope.launch { withTimeout
 * { … } }` lambda — not an extractable pure function. So this test drives the REAL production path:
 *
 *   - the ViewModel is allocated WITHOUT its constructor via sun.misc.Unsafe.allocateInstance
 *     (reached reflectively — no sun.* compile-time reference; same idiom as RetestGap04), so its
 *     heavyweight init (inbound/exchange-rate collectors) never runs;
 *   - only the handful of fields `doSendMessage`'s timeout path touches are seeded by reflection;
 *   - `viewModelScope` on an Unsafe-allocated ViewModel (impl == null) returns a fresh scope on
 *     Dispatchers.Main.immediate (verified against lifecycle-viewmodel 2.10.0 bytecode:
 *     getCloseable is null-safe → createViewModelScope()), so a StandardTestDispatcher installed as
 *     Main gives full virtual-time control of the 5-minute withTimeout;
 *   - `getOrCreateConversationId` is faked to throw an "Insufficient balance" error, which routes the
 *     send into the re-queue + timeout-waiter branch without needing the crypto / proposal path.
 *
 * MUTATION that reddens this test: revert line 7217 to `messageQueue.removeAt(0)`. With `pending_B`'s
 * waiter timing out while its own entry sits at index 1 (a concurrent `pending_A` at the head),
 * removeAt(0) evicts `pending_A` and leaves `pending_B` — the surviving-pendingId assertion flips
 * from ["pending_A"] to ["pending_B"] and goes RED. (The pendingMessages FAILED flip is keyed by
 * pendingId in BOTH versions and is asserted only as a secondary invariant — it does not redden.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetestGap00QueueTimeoutRemovesByPendingIdTest {

    private companion object {
        const val PEER = "usynthetic-peer"
        val QUEUED_MESSAGE_CLASS: Class<*> =
            Class.forName("co.electriccoin.zcash.ui.screen.chat.viewmodel.ChatViewModel\$QueuedMessage")
    }

    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ fakes / reflection helpers

    /** Answers exactly the reads doSendMessage's timeout path performs; anything else throws loudly. */
    private fun fakePrefs(): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                // Non-null → skip the first-contact KEX send so the path reaches getOrCreateConversationId.
                "getE2EOurPublicKey" -> "our_pub_stub"
                // Fail the send with an insufficient-balance signal → re-queue + launch the timeout waiter.
                "getOrCreateConversationId" ->
                    throw RuntimeException("Insufficient balance: change from a previous message not yet confirmed")
                // R2-6 (c): the send path now asks the per-message call; it fails the same way.
                "getOrCreateConversationIdForMessage" ->
                    throw RuntimeException("Insufficient balance: change from a previous message not yet confirmed")
                "removePendingMessages" -> null // Unit
                // ZC-739: the send path reads the key-change flag first; no new key waits here.
                "isE2EKeyChanged" -> false
                // ZC-1352: no first message was deleted here.
                "isAutomaticSetupWithdrawn" -> false
                // ZC-1843 (QA2 k1b2): the Accept-first re-check now runs for every queued message; no contact key is stored
                // here, so there is no unpaid answer to it.
                "getE2EPeerPublicKey" -> null
                "toString" -> "FakeZchatPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
            }
        } as ZchatPreferences

    private fun allocateBareViewModel(): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, ChatViewModel::class.java)!!
    }

    private fun setField(vm: Any, name: String, value: Any?) =
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)

    private fun getField(vm: Any, name: String): Any? =
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(vm)

    /** Reflectively build a private QueuedMessage(peerAddress, message, amountZatoshi, pendingId, retryCount). */
    private fun queuedMessage(pendingId: String): Any {
        val constructor =
            QUEUED_MESSAGE_CLASS.getDeclaredConstructor(
                String::class.java,
                String::class.java,
                java.lang.Long.TYPE,
                String::class.java,
                Integer.TYPE,
            )
        constructor.isAccessible = true
        return constructor.newInstance(PEER, "body", 1000L, pendingId, 0)
    }

    private fun pendingIdOf(entry: Any): String =
        entry.javaClass
            .getDeclaredMethod("getPendingId")
            .apply { isAccessible = true }
            .invoke(entry) as String

    private fun bubble(id: String) =
        ChatMessage(
            id = id,
            txId = null,
            text = id,
            timestamp = Instant.now(),
            isOutgoing = true,
            peerAddress = PEER,
            isPending = true,
            status = MessageStatus.SENDING,
        )

    // ----------------------------------------------------------------------------------------- test

    @Test
    fun `queue retry timeout fails only the waiters own message keyed by pendingId not by queue head`() {
        val vm = allocateBareViewModel()

        // Seed only the fields the timeout path reads. init() never ran, so nothing else is populated.
        setField(vm, "_sendMessageState", MutableStateFlow<SendMessageState>(SendMessageState.Idle))
        setField(vm, "_currentUserAddress", MutableStateFlow<String?>("usynthetic-me"))
        setField(vm, "_blockHeight", MutableStateFlow<Long?>(null)) // never advances → withTimeout fires
        setField(vm, "convIdMutex", Mutex())
        setField(vm, "zchatPreferences", fakePrefs())
        setField(vm, "messageQueue", mutableListOf<Any?>()) // re-queue will append the waiter's own entry
        setField(
            vm,
            "pendingMessages",
            MutableStateFlow(listOf(bubble("pending_A"), bubble("pending_B"))),
        )

        // Drive the REAL send for the already-queued waiter B (existingPendingId = "pending_B").
        val sendMethod =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "doSendMessage" && it.parameterCount in 5..6 }
                .apply { isAccessible = true }
        val sendArgs = mutableListOf<Any?>(PEER, "hello", 1000L, "pending_B", 0)
        // The original queued command has no retry owner; the new signature makes that explicit.
        if (sendMethod.parameterCount == 6) sendArgs.add(null)
        sendMethod.invoke(vm, *sendArgs.toTypedArray())

        // Run the outer send + the timeout-waiter launch up to their suspension points WITHOUT
        // advancing virtual time (so the 5-minute withTimeout has NOT fired yet).
        scheduler.runCurrent()

        // Sanity: we actually reached the re-queue branch — B's own entry was re-added to the queue.
        @Suppress("UNCHECKED_CAST")
        val afterRequeue = getField(vm, "messageQueue") as List<Any?>
        assertEquals("send must have re-queued the waiter (retry branch reached)", 1, afterRequeue.size)
        val bEntry = afterRequeue[0]!!
        assertEquals("pending_B", pendingIdOf(bEntry))

        // Model the concurrent scenario the fix targets: by the time B's timeout fires, a DIFFERENT
        // message (pending_A) sits at the head of the queue and B's own entry is at index 1.
        setField(vm, "messageQueue", mutableListOf(queuedMessage("pending_A"), bEntry))

        // Advance virtual time past QUEUE_RETRY_TIMEOUT_MS → the withTimeout throws and the cleanup runs.
        scheduler.advanceUntilIdle()

        // ---- RED-ON-REVERT ANCHOR ----
        // FIX  (removeAll { it.pendingId == pendingId }): B removed by its own id, A survives → ["pending_A"].
        // BUG  (removeAt(0))                            : head A wrongly removed, B survives   → ["pending_B"].
        @Suppress("UNCHECKED_CAST")
        val remaining = getField(vm, "messageQueue") as List<Any?>
        val remainingIds = remaining.map { pendingIdOf(it!!) }
        assertEquals(
            "timeout must remove the waiter's OWN message by pendingId, not removeAt(0)",
            listOf("pending_A"),
            remainingIds,
        )

        // ---- secondary invariant (keyed by pendingId in both versions; does NOT redden on revert) ----
        @Suppress("UNCHECKED_CAST")
        val bubbles = (getField(vm, "pendingMessages") as MutableStateFlow<List<ChatMessage>>).value
        val bBubble = bubbles.first { it.id == "pending_B" }
        val aBubble = bubbles.first { it.id == "pending_A" }
        assertEquals("timed-out waiter's bubble must be marked FAILED", MessageStatus.FAILED, bBubble.status)
        assertFalse("timed-out waiter's bubble must clear isPending", bBubble.isPending)
        assertEquals("the other waiter's bubble must be untouched", MessageStatus.SENDING, aBubble.status)
        assertTrue("the other waiter's bubble must stay pending", aBubble.isPending)
    }
}
