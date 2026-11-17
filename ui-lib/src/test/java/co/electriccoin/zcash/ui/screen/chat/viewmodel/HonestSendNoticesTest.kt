package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.util.Log
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.SubmitAttemptProof
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.stillConfirming
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * P2h — what the chat tells the user (and its own log) about a send must match what happened.
 *
 * 1. A send held until the next block (the wallet's funds are still confirming) toasted "Message sent":
 *    ChatViewModel re-queues it and resets the state with `SendMessageState.Success()`, whose default label
 *    is "Message sent", although nothing has been sent and the send may still time out and fail.
 * 2. A free payment request no relay took is FAILED and shows Retry, but Retry did nothing:
 *    ChatMessage.recoverRawSendPayload() returns null for payment requests, so retryMessage returns false
 *    without a word and the request can never be sent again. A request whose on-chain submit started must
 *    stay blocked, as P2a requires, and Retry must say so instead of doing nothing.
 * 3. The responder's KEXACK retry logged "re-sent KEXACK" when another attempt had already paid for the
 *    key: payKexAckOnce maps the retry loop's "already delivered" to SENT.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HonestSendNoticesTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usynthetichonestnotices00001"
        const val PEER_NOSTR = "synthetic-peer-nostr-pubkey"
        const val AMOUNT = 25_000_000L
        const val REASON = "synthetic rent"
    }

    @After
    fun tearDown() {
        Log.stopCapture()
        NostrChatBridge.unregisterPublisher()
        Dispatchers.resetMain()
    }

    // ---- 1. waiting for a block ----

    @Test
    fun `a send held until the next block does not say Message sent`() {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val vm = bareViewModel()
        val state = MutableStateFlow<SendMessageState>(SendMessageState.Idle)
        setField(vm, "_sendMessageState", state)
        setField(vm, "_currentUserAddress", MutableStateFlow<String?>("usynthetic-me"))
        setField(vm, "_blockHeight", MutableStateFlow<Long?>(null)) // no new block arrives
        setField(vm, "convIdMutex", Mutex())
        setField(vm, "zchatPreferences", queuedSendPrefs())
        setField(vm, "messageQueue", mutableListOf<Any?>())
        setField(vm, "pendingMessages", MutableStateFlow(listOf(sendingBubble("pending_Q"))))

        val send =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "doSendMessage" && it.parameterCount == 6 }
                .apply { isAccessible = true }
        send.invoke(vm, PEER, "hello", 1000L, "pending_Q", 0, null)
        scheduler.runCurrent()

        @Suppress("UNCHECKED_CAST")
        val queue = getField(vm, "messageQueue") as List<Any?>
        assertEquals("fixture sanity: the send was re-queued to wait for the next block", 1, queue.size)
        val now = state.value
        assertNotEquals(
            "the toast says the message was sent while it only waits for the next block",
            SendMessageState.Success("Message sent"),
            now,
        )
        assertEquals(SendMessageState.Success(ChatSendCopy.QUEUED_WAITING_FOR_BLOCK), now)
    }

    // ---- 2. failed payment request ----

    @Test
    fun `a free payment request no relay took can be retried and is then delivered`() =
        runBlocking {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            sendScopes.open()
            try {
                val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL, peerNostrPubkey = PEER_NOSTR)
                val seam = PaymentSeam(PEER)
                val vm = paymentViewModel(prefs, seam)

                vm.sendPaymentRequest(PEER, AMOUNT, REASON) // not connected: kept as FAILED
                awaitIdle(vm)
                val failed = PaymentHarness.pendingMessages(vm).single()
                assertEquals("fixture sanity", MessageStatus.FAILED, failed.status)

                val published = mutableListOf<String>()
                NostrChatBridge.registerPublisher { memo, _ ->
                    published += memo
                    NostrChatBridge.PublishResult(acks = 1, messageId = "r2")
                }
                val accepted = vm.retryMessage(PEER, failed.id)
                awaitIdle(vm)

                assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
                assertTrue("Retry on a failed payment request did nothing", accepted)
                assertEquals("the retried request must be published once", 1, published.size)
                assertEquals(SendMessageState.Success("Payment request sent"), PaymentHarness.sendMessageState(vm))
                val row = prefs.pending["nmsg-r2"]
                assertTrue("the delivered request is stored under its shared id (rows: ${prefs.pending.keys})", row != null)
                assertEquals(AMOUNT, row!!.paymentRequestAmountZatoshi)
                assertEquals(REASON, row.paymentRequestReason)
                assertEquals(MessageStatus.SENT.name, row.status)
                assertEquals("the FAILED row must be replaced, not duplicated", setOf("nmsg-r2"), prefs.pending.keys)
                val bubbles = PaymentHarness.pendingMessages(vm)
                assertEquals(listOf("nmsg-r2"), bubbles.map { it.id })
                assertEquals(AMOUNT, bubbles.single().paymentRequest?.amountZatoshi)
                assertEquals("a free request must never go on-chain", 0, seam.proposalUris.size)
            } finally {
                sendScopes.close()
            }
        }

    /**
     * A request whose on-chain submit started stays blocked (P2a), and Retry says why: at the parent it returned
     * false without a word, because the payload check ran before the "may already have been sent" check.
     */
    @Test
    fun `a payment request that may already have gone out is never retried and Retry says why`() =
        runBlocking {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            sendScopes.open()
            try {
                val prefs = PaymentPrefs(PEER, mode = ConversationMode.VAULT)
                prefs.pending["pending_started"] =
                    ZchatPreferences.PendingMessageData(
                        id = "pending_started",
                        text = REASON,
                        timestampMillis = 1_000L,
                        peerAddress = PEER,
                        isOutgoing = true,
                        isPending = false,
                        status = MessageStatus.FAILED.name,
                        paymentRequestAmountZatoshi = AMOUNT,
                        paymentRequestReason = REASON,
                        submitTracked = true,
                        submitStartedAtMillis = 2_000L,
                    )
                val seam = PaymentSeam(PEER)
                val vm = paymentViewModel(prefs, seam)
                PaymentHarness.loadPendingMessagesFromPrefs(vm)
                val row = PaymentHarness.pendingMessages(vm).single { it.id == "pending_started" }
                assertTrue("fixture sanity: the row may have gone out", row.sendMayHaveGoneOut)

                assertFalse(vm.retryMessage(PEER, "pending_started"))
                awaitIdle(vm)

                assertEquals(SendMessageState.Error(SubmitAttemptProof.MAY_HAVE_BEEN_SENT), PaymentHarness.sendMessageState(vm))
                assertEquals(0, seam.proposalUris.size)
                assertEquals(2_000L, prefs.pending.getValue("pending_started").submitStartedAtMillis)
            } finally {
                sendScopes.close()
            }
        }

    // ---- 3. the KEXACK log line ----

    @Test
    fun `the KEXACK retry never logs re-sent when another attempt had already paid for the key`() =
        runBlocking {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            sendScopes.open()
            try {
                val peer = "usynthetichonestkexacklog001"
                val peerKeys = E2EEncryption.generateKeyPair()
                val prefs =
                    HandshakePrefs(peer).apply {
                        e2ePeerKey[peer] = peerKeys.publicKey
                        receivedKex[peer] = peerKeys.publicKey
                    }
                val seam = HandshakeSeam(peer, failFirstProposalWith = stillConfirming())
                val heights = HeightFeed()
                val vm = handshakeViewModel(prefs, seam, heights, ironwoodPending = 200_000L)
                Log.startCapture()

                vm.retryKexAckIfResponder(peer)
                awaitTrue { heights.isWaiting() }
                prefs.proxy.setSentKexAckPubkey(peer, peerKeys.publicKey) // another attempt paid for it
                heights.advance()
                awaitIdle(vm)

                assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
                assertEquals("fixture sanity: nothing was broadcast", 0, seam.broadcasts.size)
                val claims = Log.captured().filter { "re-sent KEXACK" in it }
                assertEquals("a log line says a KEXACK was re-sent although nothing was sent: $claims", emptyList<String>(), claims)
            } finally {
                sendScopes.close()
            }
        }

    /** Control (green before and after): when the retry does pay, the log says so. */
    @Test
    fun `control - a KEXACK retry that pays logs re-sent`() =
        runBlocking {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            sendScopes.open()
            try {
                val peer = "usynthetichonestkexacklog002"
                val peerKeys = E2EEncryption.generateKeyPair()
                val prefs =
                    HandshakePrefs(peer).apply {
                        e2ePeerKey[peer] = peerKeys.publicKey
                        receivedKex[peer] = peerKeys.publicKey
                    }
                val seam = HandshakeSeam(peer)
                val vm = handshakeViewModel(prefs, seam)
                Log.startCapture()

                vm.retryKexAckIfResponder(peer)
                awaitIdle(vm)

                assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
                assertEquals(1, seam.broadcasts.size)
                assertEquals(1, Log.captured().count { "re-sent KEXACK" in it })
            } finally {
                sendScopes.close()
            }
        }

    // ---- helpers ----

    /** Answers the reads the queued-send path makes; the send fails as "funds from a previous message confirming". */
    private fun queuedSendPrefs(): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getE2EOurPublicKey" -> "our_pub_stub"
                "getOrCreateConversationId" ->
                    throw RuntimeException("Insufficient balance: change from a previous message not yet confirmed")
                // R2-6 (c): the send path now asks the per-message call; it fails the same way.
                "getOrCreateConversationIdForMessage" ->
                    throw RuntimeException("Insufficient balance: change from a previous message not yet confirmed")
                "removePendingMessages" -> null
                // ZC-739: the send path reads the key-change flag first; no new key waits here.
                "isE2EKeyChanged" -> false
                // ZC-1352: no first message was deleted here.
                "isAutomaticSetupWithdrawn" -> false
                // ZC-1843 (QA2 k1b2): the Accept-first re-check now runs for every queued message; no contact key is stored
                // here, so there is no unpaid answer to it.
                "getE2EPeerPublicKey" -> null
                "toString" -> "QueuedSendPrefs"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
            }
        } as ZchatPreferences

    private fun sendingBubble(id: String) =
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

    private fun bareViewModel(): ChatViewModel {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, ChatViewModel::class.java) as ChatViewModel
    }

    private fun setField(
        vm: Any,
        name: String,
        value: Any?
    ) = ChatViewModel::class.java
        .getDeclaredField(name)
        .apply { isAccessible = true }
        .set(vm, value)

    private fun getField(
        vm: Any,
        name: String
    ): Any? =
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(vm)
}
