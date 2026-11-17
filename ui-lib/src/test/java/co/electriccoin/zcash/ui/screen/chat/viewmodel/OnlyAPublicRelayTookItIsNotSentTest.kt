package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.Collections

/**
 * ZC-393 (fix-all 2.13.0, train T5b; t5b-add SPECS §2.3 items 4, 7, 8, §2.5, §2.6): a message or payment request that only
 * a public relay took is not SENT, and its Retry re-sends the kept wrap instead of making a new one.
 *
 * At HEAD the chat reads `PublishResult.acks > 0` as delivered, and acks counts any relay that took the wrap. After the fix
 * acks counts only the relays every ZChat version reads (ZChat's relay, nos.lol) and `readableAcks` any relay. Wanted, in
 * an Open chat: (acks 0, readableAcks 1) → the row `nmsg-<rumorId>` is FAILED with FAILED_ONLY_PUBLIC_RELAY ("Not
 * confirmed — only a public relay took it …"); its Retry asks the kept-resender (NostrChatBridge.registerKeptResender)
 * with (rumorId, the contact's relay key) and does NOT publish again; a resender answer (1, id, 1) makes the row SENT
 * under the same id; a null answer (nothing kept) publishes exactly one new wrap. A payment request the same: FAILED under
 * `nmsg-<rumorId>` with its amount, reason and the new text; its Retry goes to the resender; never on-chain. Controls:
 * (0, id, 0) keeps today's FAILED_NO_RELAY / PAYMENT_REQUEST_NOT_CONNECTED and ids; (1, id, 1) is SENT.
 *
 * PublishResult's 3-argument constructor, the resender hook and the new constant are reached by name (they do not exist
 * at HEAD). Fake publisher and resender; every key and address synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlyAPublicRelayTookItIsNotSentTest {
    private val sendScopes = SendHarnessScopes()
    private val published: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val resendCalls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Volatile private var publishAnswer: NostrChatBridge.PublishResult? = null

    @Volatile private var resendAnswer: NostrChatBridge.PublishResult? = null

    private companion object {
        const val PEER = "usyntheticonlypublicrelay001"
        const val PEER_NOSTR = "5d2e8a1f4c7b0e3d6a9f2c5b8e1d4a7f0c3b6e9d2a5f8c1b4e7d0a3f6c9b2e5d"
        const val AMOUNT = 25_000_000L
        const val REASON = "synthetic rent"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NostrChatBridge.unregisterPublisher()
        unregisterResender()
        NostrChatBridge.registerPublisher { plaintext, _ ->
            published += plaintext
            publishAnswer ?: NostrChatBridge.PublishResult(0, null)
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        unregisterResender()
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a message only a public relay took is FAILED with the new reason, and its Retry re-sends the kept wrap`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.OPEN, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            publishAnswer = result(acks = 0, id = "R1", readable = 1)
            registerResender()
            val vm = paymentViewModel(prefs, seam)

            vm.sendMessage(PEER, "synthetic only primal took it")
            awaitIdle(vm)

            val row = prefs.pending["nmsg-R1"]
            assertNotNull("the row is not under its shared id (rows: ${prefs.pending.keys})", row)
            assertEquals("a message only a public relay took was shown as SENT", MessageStatus.FAILED.name, row!!.status)
            assertEquals(onlyPublicRelayText(), row.failureReason)
            assertEquals(1, published.size)

            // Retry while ZChat's relay and nos.lol are still unreachable: the kept wrap, not a new one; still FAILED.
            resendAnswer = result(acks = 0, id = "R1", readable = 1)
            vm.retryMessage(PEER, "nmsg-R1")
            awaitIdle(vm)
            assertEquals("the Retry did not ask for the kept wrap", listOf("R1" to PEER_NOSTR), resendCalls.toList())
            assertEquals("the Retry made a new wrap while the first is kept", 1, published.size)
            assertEquals(MessageStatus.FAILED.name, prefs.pending["nmsg-R1"]?.status)
            assertEquals(onlyPublicRelayText(), prefs.pending["nmsg-R1"]?.failureReason)

            // Retry once ZChat's relay holds it: SENT under the same id, still no new wrap.
            resendAnswer = result(acks = 1, id = "R1", readable = 1)
            vm.retryMessage(PEER, "nmsg-R1")
            awaitIdle(vm)
            assertEquals(2, resendCalls.size)
            assertEquals(1, published.size)
            assertEquals(MessageStatus.SENT.name, prefs.pending["nmsg-R1"]?.status)
            assertEquals(setOf("nmsg-R1"), prefs.pending.keys)
            assertEquals(0, seam.proposalUris.size)
        }

    @Test
    fun `a Retry with nothing kept publishes exactly one new wrap`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.OPEN, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            publishAnswer = result(acks = 0, id = "R1", readable = 1)
            registerResender()
            val vm = paymentViewModel(prefs, seam)
            vm.sendMessage(PEER, "synthetic restart in between")
            awaitIdle(vm)

            resendAnswer = null // the outbox has nothing: a restart, or it was dropped
            publishAnswer = result(acks = 1, id = "R2", readable = 2)
            vm.retryMessage(PEER, "nmsg-R1")
            awaitIdle(vm)

            assertEquals(listOf("R1" to PEER_NOSTR), resendCalls.toList())
            assertEquals("nothing kept: exactly one new wrap", 2, published.size)
            assertEquals(MessageStatus.SENT.name, prefs.pending["nmsg-R2"]?.status)
            assertEquals(0, seam.proposalUris.size)
        }

    @Test
    fun `a payment request only a public relay took is FAILED under its shared id, and its Retry re-sends the kept wrap`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.OPEN, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            publishAnswer = result(acks = 0, id = "Q1", readable = 1)
            registerResender()
            val vm = paymentViewModel(prefs, seam)

            vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)

            val row = prefs.pending["nmsg-Q1"]
            assertNotNull("the request is not kept under its shared id (rows: ${prefs.pending.keys})", row)
            assertEquals(MessageStatus.FAILED.name, row!!.status)
            assertEquals(AMOUNT, row.paymentRequestAmountZatoshi)
            assertEquals(REASON, row.paymentRequestReason)
            assertEquals(onlyPublicRelayText(), row.failureReason)
            assertEquals(setOf("nmsg-Q1"), prefs.pending.keys)

            resendAnswer = result(acks = 1, id = "Q1", readable = 2)
            vm.retryMessage(PEER, "nmsg-Q1")
            awaitIdle(vm)

            assertEquals(listOf("Q1" to PEER_NOSTR), resendCalls.toList())
            assertEquals("the Retry published a second request", 1, published.size)
            assertEquals(MessageStatus.SENT.name, prefs.pending["nmsg-Q1"]?.status)
            assertEquals(AMOUNT, prefs.pending["nmsg-Q1"]?.paymentRequestAmountZatoshi)
            assertEquals("a request in a free chat must never go on-chain", 0, seam.proposalUris.size)
        }

    /** Control (passes before and after): no relay took it at all keeps today's reasons and ids. */
    @Test
    fun `control - no readable relay keeps the no-relay reason and today's ids`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.OPEN, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            publishAnswer = NostrChatBridge.PublishResult(0, "N1") // readableAcks = acks: no relay took it
            val vm = paymentViewModel(prefs, seam)

            vm.sendMessage(PEER, "synthetic no relay")
            awaitIdle(vm)
            assertEquals(ChatSendCopy.FAILED_NO_RELAY, prefs.pending["nmsg-N1"]?.failureReason)

            vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)
            val request = prefs.pending.values.single { it.paymentRequestAmountZatoshi == AMOUNT }
            assertEquals(ChatSendCopy.PAYMENT_REQUEST_NOT_CONNECTED, request.failureReason)
            assertEquals("a request no relay took keeps its pending id", true, request.id.startsWith("pending_"))
        }

    /** Control (passes before and after): a relay every version reads took it: SENT. */
    @Test
    fun `control - a shared relay took it, SENT`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.OPEN, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            publishAnswer = NostrChatBridge.PublishResult(1, "S1") // readableAcks = acks
            val vm = paymentViewModel(prefs, seam)

            vm.sendMessage(PEER, "synthetic shared relay")
            awaitIdle(vm)

            assertEquals(MessageStatus.SENT.name, prefs.pending["nmsg-S1"]?.status)
        }

    // --------------------------------------------------------------- harness

    /** PublishResult(acks, messageId, readableAcks), reached by name. */
    private fun result(
        acks: Int,
        id: String?,
        readable: Int,
    ): NostrChatBridge.PublishResult {
        val constructor =
            NostrChatBridge.PublishResult::class.java.constructors.firstOrNull { it.parameterCount == 3 }
                ?: throw AssertionError("PublishResult has no readableAcks (ZC-393): (acks, messageId) only")
        return constructor.newInstance(acks, id, readable) as NostrChatBridge.PublishResult
    }

    private fun onlyPublicRelayText(): String =
        runCatching { ChatSendCopy::class.java.getField("FAILED_ONLY_PUBLIC_RELAY").get(null) as String }
            .getOrElse { throw AssertionError("ChatSendCopy.FAILED_ONLY_PUBLIC_RELAY does not exist (ZC-393)") }

    private fun registerResender() {
        val hook: suspend (String, String) -> NostrChatBridge.PublishResult? = { rumorId, peerKey ->
            resendCalls += rumorId to peerKey
            resendAnswer
        }
        val method =
            NostrChatBridge::class.java.methods.firstOrNull { it.name == "registerKeptResender" && it.parameterCount == 1 }
                ?: throw AssertionError("NostrChatBridge.registerKeptResender does not exist (ZC-393)")
        try {
            method.invoke(NostrChatBridge, hook)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun unregisterResender() {
        NostrChatBridge::class.java.methods
            .firstOrNull { it.name == "unregisterKeptResender" && it.parameterCount == 0 }
            ?.invoke(NostrChatBridge)
    }
}
