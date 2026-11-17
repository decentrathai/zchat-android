package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.kexMemoFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1792 F1, ZC-1632, ZC-1793 (QA2 lane k1; Fable root cause ZC1792_ROOT_CAUSE.md, 12 Nov; owner W-137: no crypto,
 * protocol, key-derivation or wire change) — a responder whose own answer (KEXACK) to the stored contact key is not yet
 * paid must not seal a Shielded message or attachment line.
 *
 * What happened on 9 Nov: the Seeker stored the HONOR's key (its Accept was waiting), made its own key pair and turned E2E
 * on, so the send path built a ratchet from the HONOR's key exchange alone and sealed a paid message. When the Seeker's
 * answer was mined the root moved (the KEXACK txid is part of it), nothing tries the earlier root again, and the HONOR could
 * never open the message. The sender paid 17,000 zatoshi for a message nobody can read.
 *
 * Wanted (the REAL key-exchange handler builds the state, as on the Seeker):
 * 1. Held for Accept (getInboundKexAwaitingAccept non-null): a Shielded sendMessage is refused BEFORE any question or
 *    payment, with [ChatSendCopy.ACCEPT_FIRST_NOT_SENT]: nothing priced, built, broadcast or stored, no "Pay for this?".
 * 2. The same where the hold is gone but the answer is not paid (a responder: getReceivedKexPubkey is the stored key,
 *    getSentKexAckPubkey is not).
 * 3. An attachment line (a ZFILE link) is refused the same way, and attachmentBlockReason answers PEER_MUST_ACCEPT although
 *    the shared key exists (ZC-1793: that branch was unreachable because the key exists under the hold).
 * 4. Fail-closed backstop: getOrCreateMessageProcessorUngated(forSend = true) returns null (a cached processor included),
 *    forSend = false (decrypt) is exempt.
 * 5. Never blocked: Accept itself pays the one KEXACK; a ZBOOT line; a chat whose answer is paid; an Open chat.
 * 6. QA2 k1b (ZC-1815, [test-change-ok: ZC-1815]; decision: either side's Accept moves the root): the
 *    both-sides-sent-a-KEX state as the live handler builds it (we began the chat and our KEX is still owed; the contact's
 *    key exchange arrived: received-KEX mark and Accept hold set) STAYS gated, and a queued or funds-waiting first message
 *    never pays its owed KEX only to be refused at the seal.
 * 7. QA2 k1b: ZC-1812 (sendReply has the same Accept-first gate, before any "Pay for this?"), ZC-1816 (the stuck state with
 *    no hold puts the request back on hold, so Accept is on screen; nothing paid), ZC-1818 (the backstop also refuses where
 *    the E2E flag is off, before the Zcash-only branch, in doSendMessage and in sendReply).
 *
 * 8. QA2 k1b2 ([test-change-ok: ZC-1842], Fable review of k1b): the fake store answers isE2EKeyExchangeComplete and the
 *    owed-KEX reads from its maps, so 6b runs in the state a phone is in (both keys held, the owed mark on disk) and pins
 *    what the ZC-1815 re-check changes: nothing paid or broadcast, no row stored, the Accept-first refusal, the owed KEX
 *    kept. ZC-1842: the reconnect-owed case (the user accepted the contact's new key, our reconnect KEX is owed) pays
 *    nothing. ZC-1843: a queued message that owes no KEX is refused before its row is written. ZC-1844: in that
 *    reconnect state the request is not put back on hold (Accept would answer ANSWERED_BY_OUR_KEX and do nothing); the
 *    refusal says our key exchange is finishing ([ChatSendCopy.KEY_EXCHANGE_FINISHING_NOT_SENT]).
 * 9. QA2 k1b2 (ZC-1845): the ZC-1816 skip branches (an answer in flight, an Accept waiting for funds for that key) put
 *    nothing on hold; after the re-hold one Accept pays exactly one answer and a second Accept pays none.
 *
 * The new name is [ChatSendCopy.ACCEPT_FIRST_NOT_SENT] (EN/ES text in Qa2K1CopyTest). Every address and key is synthetic;
 * no money.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcceptFirstBeforeSealingTest {
    private val sendScopes = SendHarnessScopes()
    private var savedOwnHistoryRead = false

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        savedOwnHistoryRead = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
    }

    @After
    fun tearDown() {
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(savedOwnHistoryRead)
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
    }

    // ---- the phone ---------------------------------------------------------------------------------------------------

    /** The Seeker on 9 Nov: the contact's key exchange arrived, its answer waits behind Accept; the key is stored. */
    private class Seeker(
        val peer: String,
        val contactKeys: E2EKeyPair,
        val prefs: HandshakePrefs,
        val seam: HandshakeSeam,
        val vm: ChatViewModel,
    )

    private suspend fun seekerHoldingTheAnswer(
        seed: Int,
        consent: Boolean = true,
    ): Seeker {
        val peer = SyntheticUnifiedAddress.mainnet(seed = seed)
        val keys = E2EEncryption.generateKeyPair()
        val prefs = HandshakePrefs(peer, consent = consent, firstContact = true)
        val seam = HandshakeSeam(peer)
        val vm = handshakeViewModel(prefs, seam)
        PaymentHarness.setField(vm, "zchatPreferences", lenient(prefs.proxy))
        handleKexMemo(vm, kexMemoFrom(peer, keys), txId = "synthetic-zc1792-kex-$seed")
        awaitIdle(vm)
        wire(vm)
        // Fixture sanity: this is the state the Seeker was in.
        assertTrue("fixture: the contact's request waits for Accept", vm.isInboundKexAwaitingAccept(peer))
        assertEquals("fixture: the contact's key is stored", keys.publicKey, prefs.e2ePeerKey[peer])
        assertNotNull("fixture: our key pair for the contact exists", prefs.ourKeyStore[peer])
        assertTrue("fixture: E2E is on for the contact", peer in prefs.e2eEnabled)
        assertEquals("fixture: the contact's key exchange is the received one", keys.publicKey, prefs.receivedKex[peer])
        assertNull("fixture: our answer is not paid", prefs.sentKexAck[peer])
        assertEquals("fixture: nothing was paid by the handler", 0, seam.proposalRequests.get())
        return Seeker(peer, keys, prefs, seam, vm)
    }

    private fun wire(vm: ChatViewModel) {
        PaymentHarness.setField(vm, "messageProcessors", ConcurrentHashMap<String, Any>())
        PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        PaymentHarness.setField(vm, "ratchetStateStore", InMemoryRatchetStateStore())
        // The send gate reads the key store through the seed provider; give it the lenient store too.
        PaymentHarness.setField(
            vm,
            "seedKeyProvider",
            SeedDerivedE2EKeyProvider(currentPrefs(vm)) { null },
        )
        PaymentHarness.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
        PaymentHarness.setField(vm, "messageQueue", mutableListOf<Any?>())
        PaymentHarness.setField(
            vm,
            "_chatListState",
            MutableStateFlow<co.electriccoin.zcash.ui.screen.chat.model.ChatListState>(
                co.electriccoin.zcash.ui.screen.chat.model.ChatListState.Loading,
            ),
        )
        PaymentHarness.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
        PaymentHarness.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
        AutoSpendVm.wireBootstrapFields(vm)
        // QA2 k1b2 (ZC-1842): the input a refused send hands its text back to (the constructor sets it on a phone). Without
        // it the refusal's restoreTypedText threw and the send ended as "Failed to send message" for another reason.
        ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "_restoredTypedText" }?.let {
            it.isAccessible = true
            it.set(vm, MutableStateFlow<Any?>(null))
        }
    }

    /** [base] with the calls it does not know answered by the type's empty value (the send path reads many stores). */
    private fun lenient(base: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
            val a = args ?: emptyArray()
            when (method.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a.getOrNull(0)
                "toString" -> "LenientPrefs"
                "hasAcknowledgedMessageCost" -> true
                "getE2EKeyVersion" -> 2
                else -> {
                    // QA2 k1b (ZC-1818): what happens while the send takes its conversation id (inside the send's run).
                    if (method.name == "getOrCreateConversationIdForMessage") onConversationIdForMessage?.invoke()
                    val value =
                        try {
                            method.invoke(base, *a)
                        } catch (e: InvocationTargetException) {
                            if (e.targetException is NullPointerException && method.returnType.isPrimitive) {
                                null
                            } else {
                                throw e.targetException
                            }
                        }
                    value ?: emptyValue(method.returnType)
                }
            }
        } as ZchatPreferences

    /** QA2 k1b (ZC-1818): runs once on the first getOrCreateConversationIdForMessage of a send (then cleared). */
    @Volatile private var onConversationIdForMessage: (() -> Unit)? = null

    private fun emptyValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            List::class.java -> emptyList<Any>()
            Set::class.java -> emptySet<Any>()
            Map::class.java -> emptyMap<Any, Any>()
            else -> null
        }

    private fun currentPrefs(vm: ChatViewModel): ZchatPreferences {
        val field = ChatViewModel::class.java.getDeclaredField("zchatPreferences")
        field.isAccessible = true
        return field.get(vm) as ZchatPreferences
    }

    private fun copyConstant(name: String): String {
        val field = ChatSendCopy::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(null) as String
    }

    private fun acceptFirst(): String =
        try {
            copyConstant("ACCEPT_FIRST_NOT_SENT")
        } catch (e: NoSuchFieldException) {
            throw AssertionError("ChatSendCopy has no ACCEPT_FIRST_NOT_SENT: a responder can still seal before its answer is paid", e)
        }

    /** The control tests compare against this: before the fix the constant does not exist, so nothing equals it. */
    private fun acceptFirstOrNull(): String? =
        runCatching { copyConstant("ACCEPT_FIRST_NOT_SENT") }.getOrNull()

    private fun refusedAsAcceptFirst(vm: ChatViewModel): Boolean {
        val text = acceptFirstOrNull() ?: return false
        return HandshakeHarness.sendMessageState(vm) == SendMessageState.Error(text)
    }

    private fun assertRefusedBeforeAnyPayment(
        s: Seeker,
        sent: Boolean,
        what: String,
    ) {
        assertFalse("$what was accepted although the answer is not paid", sent)
        assertEquals(
            "$what: the refusal is not the Accept-first text",
            SendMessageState.Error(acceptFirst()),
            HandshakeHarness.sendMessageState(s.vm),
        )
        assertEquals("$what: nothing may be built", 0, s.seam.proposalRequests.get())
        assertEquals("$what: nothing may be priced", 0, s.seam.probes.get())
        assertEquals("$what: nothing may be broadcast", 0, s.seam.submits.get())
        assertTrue("$what: no row may be stored", PaymentHarness.pendingMessages(s.vm).isEmpty())
        assertEquals("$what: no ratchet was built", 0, processors(s.vm).size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun processors(vm: ChatViewModel): Map<String, Any> =
        ChatViewModel::class.java
            .getDeclaredField("messageProcessors")
            .apply { isAccessible = true }
            .get(vm) as Map<String, Any>

    // ---- 1, 2: the pre-send gate -------------------------------------------------------------------------------------

    @Test
    fun `1 - held for Accept - a Shielded message is refused before any question or payment`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 17921)

            val sent = s.vm.sendMessage(s.peer, "synthetic zc1792 hello")
            awaitIdle(s.vm)

            assertRefusedBeforeAnyPayment(s, sent, "a message while the Accept is open")
            assertTrue("the hold is untouched", s.vm.isInboundKexAwaitingAccept(s.peer))
        }

    @Test
    fun `2 - the hold is gone but the answer is not paid - the message is still refused`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 17922)
            // A responder whose Accept went through the tap but whose answer was not paid (funds confirming, a declined
            // question put back): the stored key is the received one and the answer marker is not that key.
            s.prefs.awaitingAccept.remove(s.peer)
            assertNull(s.prefs.sentKexAck[s.peer])

            val sent = s.vm.sendMessage(s.peer, "synthetic zc1792 hello again")
            awaitIdle(s.vm)

            assertRefusedBeforeAnyPayment(s, sent, "a message of a responder with an unpaid answer")
        }

    @Test
    fun `3 - an attachment line is refused the same way and the attachment gate names the Accept`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 17923)

            // ZC-1793: the shared key exists under the hold, so the gate used to answer "no block" before it reached its
            // Accept branch.
            assertEquals(
                "attachmentBlockReason ignores the Accept hold because the shared key exists",
                "PEER_MUST_ACCEPT",
                s.vm.attachmentBlockReason(s.peer)?.name,
            )
            assertTrue("the picker/camera/recorder gate lets an attachment through", s.vm.refuseAttachmentIfBlocked(s.peer))
            val shown = HandshakeHarness.sendMessageState(s.vm)
            assertEquals(
                "the attachment notice is the Accept-first dialog",
                "PEER_MUST_ACCEPT",
                (shown as? SendMessageState.AttachmentNeedsKeyExchange)?.reason?.name,
            )
            assertEquals("the gate paid or priced something", 0, s.seam.proposalRequests.get() + s.seam.probes.get())

            // F1 covers the link line itself (a retried or queued line reaches sendMessage without that gate).
            val line =
                ZFILEMessage(
                    hash = "a".repeat(64),
                    type = ZFILEType.JPEG,
                    size = 1_000L,
                    url = "https://example.invalid/zc1792",
                    wrappedKey = "AAAA",
                    blurhash = "LEHV6nWB2yk8",
                ).serialize()
            PaymentHarness.setSendMessageState(s.vm, SendMessageState.Idle)
            val sent = s.vm.sendMessage(s.peer, line)
            awaitIdle(s.vm)

            assertRefusedBeforeAnyPayment(s, sent, "an attachment line")
        }

    // ---- 4: the backstop -----------------------------------------------------------------------------------------------

    @Test
    fun `4 - the backstop - no send processor while the answer is unpaid, a decrypt processor is still built`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 17924)
            val convId = HandshakeHarness.CONV_ID

            val forSend = KeyAcceptApi.callSuspend(s.vm, "getOrCreateMessageProcessorUngated", s.peer, convId, true)
            assertNull(
                "getOrCreateMessageProcessorUngated(forSend = true) built a processor that seals before the answer is paid",
                forSend,
            )
            assertEquals("a refused send must not leave a processor behind", 0, processors(s.vm).size)

            // The decrypt path is exempt: it is what opens a contact's earlier messages.
            val forDecrypt = KeyAcceptApi.callSuspend(s.vm, "getOrCreateMessageProcessorUngated", s.peer, convId, false)
            assertNotNull("the decrypt path was blocked too", forDecrypt)

            // A processor a decrypt cached must not smuggle a send past the gate.
            val again = KeyAcceptApi.callSuspend(s.vm, "getOrCreateMessageProcessorUngated", s.peer, convId, true)
            assertNull("a cached processor let a send through", again)
        }

    @Test
    fun `4b - once the answer is paid the same chat gets its send processor`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 17925)
            s.prefs.awaitingAccept.remove(s.peer)
            s.prefs.sentKexAck[s.peer] = s.contactKeys.publicKey

            val forSend = KeyAcceptApi.callSuspend(s.vm, "getOrCreateMessageProcessorUngated", s.peer, HandshakeHarness.CONV_ID, true)

            assertNotNull("control: a chat whose answer is paid must be able to seal", forSend)
        }

    // ---- 5: never blocked ----------------------------------------------------------------------------------------------

    @Test
    fun `5a - control - Accept itself still pays the one answer under the hold`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 17926, consent = true)

            s.vm.acceptInboundKexRequest(s.peer)
            awaitIdle(s.vm)

            assertEquals("Accept must still pay exactly one KEXACK", 1, s.seam.proposalRequests.get())
            assertFalse("the hold goes once the answer is sent", s.vm.isInboundKexAwaitingAccept(s.peer))
        }

    @Test
    fun `5b - control - a ZBOOT line is not refused by the Accept-first gate`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 17927)

            s.vm.sendMessage(s.peer, co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage.PREFIX_V4 + "synthetic-zc1792")
            awaitIdle(s.vm)

            assertFalse("the gate refused a ZBOOT, the memo that re-establishes trust", refusedAsAcceptFirst(s.vm))
        }

    @Test
    fun `5c - control - an answered chat sends`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 17928)
            s.prefs.awaitingAccept.remove(s.peer)
            s.prefs.sentKexAck[s.peer] = s.contactKeys.publicKey

            s.vm.sendMessage(s.peer, "synthetic zc1792 after the answer")
            awaitIdle(s.vm)

            assertFalse("the gate refused a chat whose answer is paid", refusedAsAcceptFirst(s.vm))
        }

    @Test
    fun `5d - control - an Open chat is not gated, its messages never seal a Shielded memo`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 17929)
            s.prefs.mode = ConversationMode.OPEN

            // Only the gate's answer matters here; the Open route has its own fixture needs.
            runCatching { s.vm.sendMessage(s.peer, "synthetic zc1792 open") }
            awaitIdle(s.vm)

            assertFalse("the Shielded gate refused an Open chat's message", refusedAsAcceptFirst(s.vm))
        }

    // ---- 6: the both-sides state stays gated (QA2 k1b, ZC-1815) ---------------------------------------------------------

    /**
     * The both-sides-sent-a-KEX state as the live handler builds it: we began the chat (our key pair, our first-message
     * KEX still owed: its funds were confirming), then the contact's key exchange arrived. The REAL handler stores the key,
     * sets the received-KEX mark and puts the request on hold (W-62: an awaited key exchange waits for Accept).
     */
    private suspend fun bothSidesSentAKex(seed: Int): Seeker {
        val peer = SyntheticUnifiedAddress.mainnet(seed = seed)
        val keys = E2EEncryption.generateKeyPair()
        val prefs = HandshakePrefs(peer, consent = true, firstContact = true)
        val ourPair = E2EEncryption.generateKeyPair()
        prefs.ourKeyStore[peer] = ourPair.publicKey to ourPair.privateKey
        prefs.e2eEnabled += peer
        val seam = HandshakeSeam(peer)
        val vm = handshakeViewModel(prefs, seam)
        PaymentHarness.setField(vm, "zchatPreferences", lenient(prefs.proxy))
        // Our first message's KEX is owed (the process-wide mark a funds-waiting first message leaves, and its durable mark).
        kexOwedPeers().add(peer)
        prefs.kexOwed += peer
        handleKexMemo(vm, kexMemoFrom(peer, keys), txId = "synthetic-zc1815-kex-$seed")
        awaitIdle(vm)
        wire(vm)
        assertEquals("fixture: the contact's key is stored", keys.publicKey, prefs.e2ePeerKey[peer])
        assertEquals("fixture: the live handler set the received-KEX mark", keys.publicKey, prefs.receivedKex[peer])
        assertTrue("fixture: the live handler put the request on hold", vm.isInboundKexAwaitingAccept(peer))
        assertNull("fixture: our answer is not paid", prefs.sentKexAck[peer])
        assertEquals("fixture: nothing was paid by the handler", 0, seam.proposalRequests.get())
        assertTrue("fixture: our KEX is still owed", peer in kexOwedPeers())
        return Seeker(peer, keys, prefs, seam, vm)
    }

    @Suppress("UNCHECKED_CAST")
    private fun kexOwedPeers(): MutableSet<String> =
        ChatViewModel::class.java
            .getDeclaredField("kexOwedPeers")
            .apply { isAccessible = true }
            .get(null) as MutableSet<String>

    /** doSendMessage as the queue and the funds wait call it (no tap, so no sendMessage gate before it). */
    private fun doSendMessage(
        vm: ChatViewModel,
        peer: String,
        text: String,
    ) {
        try {
            ChatViewModel::class.java
                .getDeclaredMethod(
                    "doSendMessage",
                    String::class.java,
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Long::class.javaObjectType,
                ).apply { isAccessible = true }
                .invoke(vm, peer, text, 1_000L, null, 0, null)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `6 - the both-sides-sent-a-KEX state stays gated - a Shielded message is refused before any payment`() =
        runBlocking {
            val s = bothSidesSentAKex(seed = 17930)

            val sent = s.vm.sendMessage(s.peer, "synthetic zc1815 both sides")
            awaitIdle(s.vm)

            assertRefusedBeforeAnyPayment(s, sent, "a message in the both-sides state")
        }

    @Test
    fun `6b - ZC-1815 - a queued first message in the both-sides state never pays its owed KEX`() =
        runBlocking {
            val s = bothSidesSentAKex(seed = 17931)

            doSendMessage(s.vm, s.peer, "synthetic zc1815 queued first message")
            awaitIdle(s.vm)

            assertEquals(
                "the owed KEX was paid although the seal is refused (the contact's KEX arrived meanwhile): money spent for nothing",
                0,
                s.seam.proposalRequests.get(),
            )
            assertEquals("a transaction was broadcast", 0, s.seam.submits.get())
            assertTrue("the owed KEX was dropped instead of kept for after the Accept", s.peer in kexOwedPeers())
            // ZC-1842: what the re-check changes, in the state a phone is in (both keys held, the owed mark on disk).
            assertTrue("fixture: the store answers the exchange complete (both keys held)", s.prefs.proxy.isE2EKeyExchangeComplete(s.peer))
            assertTrue("the durable owed mark was cleared", s.peer in s.prefs.kexOwed)
            assertEquals(
                "the queued first message was not refused with the Accept-first text",
                SendMessageState.Error(acceptFirst()),
                HandshakeHarness.sendMessageState(s.vm),
            )
            assertTrue("a row was written for a message the seal refuses", PaymentHarness.pendingMessages(s.vm).isEmpty())
            assertEquals("a ratchet was built for a refused send", 0, processors(s.vm).size)
            assertEquals(
                "the typed text was not handed back",
                s.peer to "synthetic zc1815 queued first message",
                FirstMessagePlanChat.restored(s.vm),
            )
        }

    // ---- 8: QA2 k1b2 (ZC-1842, ZC-1843, ZC-1844) -------------------------------------------------------------------------

    /**
     * ZC-1842, ZC-1844: the user accepted the contact's new key ("Accept the new key", getAcceptedKey), so our reconnect
     * KEX to it is owed (reason reconnect, process-wide and on disk); the contact's key exchange with that key is the
     * received one and our answer marker is not that key. payKexAckOnce answers ANSWERED_BY_OUR_KEX here: no Accept pays.
     */
    private suspend fun reconnectOwed(seed: Int): Seeker {
        val s = seekerHoldingTheAnswer(seed = seed)
        s.prefs.awaitingAccept.remove(s.peer)
        s.prefs.acceptedKeys[s.peer] = s.contactKeys.publicKey
        s.prefs.kexOwed += s.peer
        s.prefs.kexOwedReasons[s.peer] = ZchatPreferences.KEX_OWED_REASON_RECONNECT
        kexOwedPeers().add(s.peer)
        return s
    }

    private fun keyExchangeFinishing(): String =
        try {
            copyConstant("KEY_EXCHANGE_FINISHING_NOT_SENT")
        } catch (e: NoSuchFieldException) {
            throw AssertionError(
                "ChatSendCopy has no KEY_EXCHANGE_FINISHING_NOT_SENT: the refusal points to an Accept that does nothing",
                e,
            )
        }

    @Test
    fun `8a - ZC-1842 - a queued message in the reconnect-owed state pays nothing`() =
        runBlocking {
            val s = reconnectOwed(seed = 18421)

            doSendMessage(s.vm, s.peer, "synthetic zc1842 queued after the reconnect")
            awaitIdle(s.vm)

            assertEquals("the owed reconnect KEX was paid for a message the seal refuses", 0, s.seam.proposalRequests.get())
            assertEquals("a transaction was broadcast", 0, s.seam.submits.get())
            assertTrue("the owed reconnect KEX was dropped", s.peer in kexOwedPeers() && s.peer in s.prefs.kexOwed)
            assertTrue("a row was written for a message the seal refuses", PaymentHarness.pendingMessages(s.vm).isEmpty())
        }

    @Test
    fun `8b - ZC-1844 - in the reconnect-owed state a refused send puts no request on hold and says what is waiting`() =
        runBlocking {
            val s = reconnectOwed(seed = 18441)

            val sent = s.vm.sendMessage(s.peer, "synthetic zc1844 hello")
            awaitIdle(s.vm)

            assertFalse("the message was accepted although the session is not complete", sent)
            assertNull(
                "the request was put back on hold although Accept answers ANSWERED_BY_OUR_KEX and pays nothing: Accept does nothing",
                s.prefs.awaitingAccept[s.peer],
            )
            assertEquals(
                "the refusal says 'Tap Accept first' while no Accept can help: our own key exchange is what is waiting",
                SendMessageState.Error(keyExchangeFinishing()),
                HandshakeHarness.sendMessageState(s.vm),
            )
            assertEquals("nothing may be built", 0, s.seam.proposalRequests.get())
            assertEquals("nothing may be broadcast", 0, s.seam.submits.get())
        }

    @Test
    fun `8c - ZC-1843 - a queued message that owes no KEX is refused before its row is written`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 18431)
            assertFalse("fixture: this message owes no KEX of ours", s.peer in kexOwedPeers())

            doSendMessage(s.vm, s.peer, "synthetic zc1843 queued message")
            awaitIdle(s.vm)

            assertEquals(
                "the queued message was not refused with the Accept-first text",
                SendMessageState.Error(acceptFirst()),
                HandshakeHarness.sendMessageState(s.vm),
            )
            assertTrue("a row was written for a message the seal refuses", PaymentHarness.pendingMessages(s.vm).isEmpty())
            assertEquals("nothing may be built", 0, s.seam.proposalRequests.get())
            assertEquals("nothing may be broadcast", 0, s.seam.submits.get())
            assertEquals(
                "the typed text was not handed back",
                s.peer to "synthetic zc1843 queued message",
                FirstMessagePlanChat.restored(s.vm),
            )
        }

    // ---- 9: QA2 k1b2 (ZC-1845) -----------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun kexAckInFlight(): MutableSet<String> =
        ChatViewModel::class.java
            .getDeclaredField("kexAckInFlight")
            .apply { isAccessible = true }
            .get(null) as MutableSet<String>

    @Suppress("UNCHECKED_CAST")
    private fun acceptsWaitingForFunds(): MutableMap<String, Any> =
        ChatViewModel::class.java
            .getDeclaredField("acceptsWaitingForFunds")
            .apply { isAccessible = true }
            .get(null) as MutableMap<String, Any>

    private fun acceptFundsWait(
        key: String,
        until: Long,
    ): Any {
        val type = Class.forName("co.electriccoin.zcash.ui.screen.chat.viewmodel.ChatViewModel\$Companion\$AcceptFundsWait")
        val constructor = type.declaredConstructors.single { it.parameterCount == 3 }
        constructor.isAccessible = true
        return constructor.newInstance(key, until, null)
    }

    @Test
    fun `9a - ZC-1845 - with an answer in flight the refused send puts nothing on hold`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 18451)
            s.prefs.awaitingAccept.remove(s.peer)
            kexAckInFlight().add(s.peer)
            try {
                val sent = s.vm.sendMessage(s.peer, "synthetic zc1845 in flight")
                awaitIdle(s.vm)

                assertRefusedBeforeAnyPayment(s, sent, "a message while our answer is in flight")
                assertNull("an answer in flight was offered for Accept again", s.prefs.awaitingAccept[s.peer])
            } finally {
                kexAckInFlight().remove(s.peer)
            }
        }

    @Test
    fun `9b - ZC-1845 - with an Accept waiting for funds for that key the refused send puts nothing on hold`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 18452)
            s.prefs.awaitingAccept.remove(s.peer)
            acceptsWaitingForFunds()[s.peer] = acceptFundsWait(s.contactKeys.publicKey, System.currentTimeMillis() + 600_000L)
            try {
                val sent = s.vm.sendMessage(s.peer, "synthetic zc1845 funds wait")
                awaitIdle(s.vm)

                assertRefusedBeforeAnyPayment(s, sent, "a message while the Accept waits for funds")
                assertNull("an Accept waiting for funds was offered again", s.prefs.awaitingAccept[s.peer])
            } finally {
                acceptsWaitingForFunds().remove(s.peer)
            }
        }

    @Test
    fun `9c - ZC-1845 - after the re-hold one Accept pays exactly one answer and a second pays none`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 18453, consent = true)
            s.prefs.awaitingAccept.remove(s.peer)
            s.vm.sendMessage(s.peer, "synthetic zc1845 hello")
            awaitIdle(s.vm)
            assertEquals("fixture: the request is back on hold", s.contactKeys.publicKey, s.prefs.awaitingAccept[s.peer])

            s.vm.acceptInboundKexRequest(s.peer)
            awaitIdle(s.vm)
            assertEquals("one Accept after the re-hold must pay exactly one answer", 1, s.seam.proposalRequests.get())
            assertEquals("the answer marker names the key", s.contactKeys.publicKey, s.prefs.sentKexAck[s.peer])

            s.vm.acceptInboundKexRequest(s.peer)
            awaitIdle(s.vm)
            assertEquals("a second Accept paid another answer", 1, s.seam.proposalRequests.get())
        }

    // ---- 7: QA2 k1b ------------------------------------------------------------------------------------------------------

    @Test
    fun `7a - ZC-1812 - a reply while the Accept is open is refused before any payment with the Accept-first text`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 18121)

            val sent = s.vm.sendReply(s.peer, "synthetic zc1812 reply", replyToId = "synthetic-zc1812-quoted")
            awaitIdle(s.vm)

            assertRefusedBeforeAnyPayment(s, sent, "a reply while the Accept is open")
        }

    @Test
    fun `7b - ZC-1812 - a long reply while the Accept is open asks no Pay for this`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 18122)

            val sent = s.vm.sendReply(s.peer, "synthetic zc1812 long reply ".repeat(40), replyToId = "synthetic-zc1812-quoted")
            awaitIdle(s.vm)

            assertFalse(
                "a long reply under the hold was asked 'Pay for this?' (${HandshakeHarness.sendMessageState(s.vm)})",
                HandshakeHarness.sendMessageState(s.vm).javaClass.simpleName == "NeedsPaidActionConfirmation",
            )
            assertRefusedBeforeAnyPayment(s, sent, "a long reply while the Accept is open")
        }

    @Test
    fun `7c - ZC-1816 - with no hold and the answer unpaid a refused send puts the request back on hold`() =
        runBlocking {
            val s = seekerHoldingTheAnswer(seed = 18161)
            // The stuck state: no hold, the received mark is the stored key, the answer marker is not that key.
            s.prefs.awaitingAccept.remove(s.peer)

            val sent = s.vm.sendMessage(s.peer, "synthetic zc1816 hello")
            awaitIdle(s.vm)

            assertRefusedBeforeAnyPayment(s, sent, "a message in the stuck state")
            assertEquals(
                "the send says 'Tap Accept first' but no Accept is on screen: the request was not put back on hold",
                s.contactKeys.publicKey,
                s.prefs.awaitingAccept[s.peer],
            )
            assertTrue("the Accept banner does not show", s.vm.isInboundKexAwaitingAccept(s.peer))
            assertNull("an answer was recorded without the user's Accept", s.prefs.sentKexAck[s.peer])
        }

    /**
     * ZC-1818: an answered chat whose send passed the tap's gate; while it takes its conversation id the contact's new key
     * exchange is stored (key, received-KEX mark) and E2E is not on for it yet (the window before setE2EEnabled).
     */
    private suspend fun answeredChatWhoseKeyChangesMidSend(seed: Int): Seeker {
        val s = seekerHoldingTheAnswer(seed = seed)
        s.prefs.awaitingAccept.remove(s.peer)
        s.prefs.sentKexAck[s.peer] = s.contactKeys.publicKey
        val next = E2EEncryption.generateKeyPair()
        onConversationIdForMessage = {
            onConversationIdForMessage = null
            s.prefs.e2ePeerKey[s.peer] = next.publicKey
            s.prefs.receivedKex[s.peer] = next.publicKey
            s.prefs.e2eEnabled.remove(s.peer)
        }
        return s
    }

    private fun assertNothingSealedOrPaid(
        s: Seeker,
        what: String,
    ) {
        assertNull("fixture: the window was not reached ($what)", onConversationIdForMessage)
        assertEquals(
            "$what went out with Zcash's encryption only while our answer to the stored key is unpaid",
            0,
            s.seam.proposalRequests.get(),
        )
        assertEquals("$what: a transaction was broadcast", 0, s.seam.submits.get())
    }

    @Test
    fun `7d - ZC-1818 - the backstop refuses a message in the window before the E2E flag is set`() =
        runBlocking {
            val s = answeredChatWhoseKeyChangesMidSend(seed = 18181)

            s.vm.sendMessage(s.peer, "synthetic zc1818 message")
            awaitIdle(s.vm)

            assertNothingSealedOrPaid(s, "a message")
        }

    @Test
    fun `7e - ZC-1818 - the backstop refuses a reply in the window before the E2E flag is set`() =
        runBlocking {
            val s = answeredChatWhoseKeyChangesMidSend(seed = 18182)

            s.vm.sendReply(s.peer, "synthetic zc1818 reply", replyToId = "synthetic-zc1818-quoted")
            awaitIdle(s.vm)

            assertNothingSealedOrPaid(s, "a reply")
        }
}
