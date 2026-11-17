package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 10b R10B-1 (F-R9C-1, P2 money; F-R9C-4; F-R9C-5) — a first message's spend exceeds its quote.
 *
 * Two-phone checks, LEDGER rows 3-5: the release's first message to a new contact in a Shielded chat asked "2 on-chain
 * transactions, at least 0.00034 ZEC" and paid 3 (51,000 zatoshi). When the contact's KEXACK arrived, the KEXACK handler
 * called sendNostrBootHandshake in every chat mode; the contact's relay key was unknown (a first-contact KEXACK carries
 * the address, never the relay fields), so the ZBOOT went on-chain under the standing "Allow handshake transactions"
 * consent. Neither the first-message question (onChainMessages = 2) nor the composer row (the message alone, 0.00017)
 * counted it. In the same step the contact's reply ZBOOT switched the release's Shielded chat to Tunnel, although the
 * release had started the handshake itself. On the other phone the Accept banner said "one handshake transaction" and
 * the Allow question "up to 2", for a pinned Shielded chat whose ZBOOT could only ever be the free reply.
 *
 * Must hold: one quote rule used by the question, the composer row and the spend. A first message whose ZBOOT must go
 * on-chain (the contact's relay key unknown) is quoted as 3 transactions and the row says the same; a Shielded chat pays
 * an on-chain ZBOOT only when a confirmed question quoted it, exactly once; a relay publish that fails never falls back to
 * an unquoted on-chain ZBOOT; the reply ZBOOT to our own does not change the chat's mode; the Allow question and the
 * Accept banner count the same transactions for a contact.
 *
 * Drives the real ChatViewModel through the real use case and repository (PaidChatFixture, AutoSpendPrefs, HandshakePrefs
 * and the HandshakeHarness seam); new calls are reached by name so this file compiles before they exist. Every address and
 * key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessageNeverExceedsQuoteTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val RELAY = "wss://relay.synthetic.example"
        val ONE_TX: Long = onChainSendMinimumCost().value
        val AMOUNT: Long = CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT.value
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ---- (a) (b) the question and the composer row ------------------------------------------------------------

    @Test
    fun `a first message to a new Shielded contact whose relay key is unknown is quoted as three transactions`() =
        runBlocking {
            val chat = QuotedChat("usyntheticr10b1quote0000001")
            val vm = chat.viewModel()

            vm.sendMessage(chat.peer, "hello")
            awaitIdle(vm)

            val (count, total) = quoted(vm)
            assertEquals("the key exchange, the message and the ZBOOT that follows the contact's KEXACK", 3, count)
            assertEquals(3 * ONE_TX, total)
            assertEquals(51_000L, total)
            assertEquals(
                "fixture sanity: nothing is paid before the answer",
                0,
                chat.fixture.seam.proposalRequests
                    .get()
            )
        }

    @Test
    fun `the composer row for a first message shows the total its question asks`() =
        runBlocking {
            val chat = QuotedChat("usyntheticr10b1quote0000002")
            val vm = chat.viewModel()

            val shown = vm.composerMessageCost(chat.peer, "hello", AMOUNT).value
            vm.sendMessage(chat.peer, "hello")
            awaitIdle(vm)

            assertEquals("the row showed $shown, the question then asked ${quoted(vm).second}", quoted(vm).second, shown)
            assertEquals(51_000L, shown)
        }

    // ---- (c) the KEXACK's ZBOOT in a Shielded chat -------------------------------------------------------------

    @Test
    fun `a Shielded chat's KEXACK pays no on-chain ZBOOT that no question quoted`() =
        runBlocking {
            val peer = "usyntheticr10b1kexack000001"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = false, mode = ConversationMode.VAULT) }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
            val peerKeys = E2EEncryption.generateKeyPair()

            handleKexMemo(vm, kexAckMemo(peer, prefs.convIdFor(peer), peerKeys), txId = "synthetic-r10b1-kexack-01")
            awaitIdle(vm)

            assertEquals("fixture sanity: the KEXACK was taken", peerKeys.publicKey, prefs.peerKeys[peer])
            assertEquals(
                "the standing consent paid a ZBOOT no question in this Shielded chat had quoted",
                0,
                seam.submits.get(),
            )
            assertEquals("the ZBOOT stays armed for a free delivery later", null, prefs.sentNostrBoot[peer])
        }

    /** Control, green before and after: in a Tunnel chat the standing consent covers the ZBOOT, as it says. */
    @Test
    fun `control - a Tunnel chat's KEXACK pays its ZBOOT under the standing consent`() =
        runBlocking {
            val peer = "usyntheticr10b1kexack000002"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = false, mode = ConversationMode.TUNNEL) }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
            val peerKeys = E2EEncryption.generateKeyPair()

            handleKexMemo(vm, kexAckMemo(peer, prefs.convIdFor(peer), peerKeys), txId = "synthetic-r10b1-kexack-02")
            awaitIdle(vm)

            assertEquals(1, seam.submits.get())
        }

    @Test
    fun `after a confirmed three-transaction first message the KEXACK pays the quoted ZBOOT exactly once`() =
        runBlocking {
            val chat = QuotedChat("usyntheticr10b1kexack000003")
            val vm = chat.viewModel()
            val peerKeys = E2EEncryption.generateKeyPair()

            vm.sendMessage(chat.peer, "hello")
            awaitIdle(vm)
            assertEquals(
                "fixture sanity: asked first",
                0,
                chat.fixture.seam.submits
                    .get()
            )
            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals(
                "fixture sanity: the KEX and the message",
                2,
                chat.fixture.seam.submits
                    .get()
            )
            assertEquals("the confirmed quote's ZBOOT is recorded before anything is paid", true, chat.zbootQuoted[chat.peer])

            handleKexMemo(vm, kexAckMemo(chat.peer, PaidChatFixture.CONV_ID, peerKeys), txId = "synthetic-r10b1-kexack-03")
            awaitIdle(vm)
            assertEquals(
                "the quoted ZBOOT is paid once the contact's KEXACK lands",
                3,
                chat.fixture.seam.submits
                    .get()
            )
            assertTrue("the allowance is used up by that ZBOOT", chat.zbootQuoted[chat.peer] != true)

            // Something re-arms the ZBOOT (a failed delivery, a new trigger): no second on-chain ZBOOT on that one quote.
            chat.sentNostrBoot.clear()
            chat.paidRecords.clear()
            handleKexMemo(vm, kexAckMemo(chat.peer, PaidChatFixture.CONV_ID, peerKeys), txId = "synthetic-r10b1-kexack-04")
            awaitIdle(vm)
            assertEquals(
                "a second ZBOOT was paid on one confirmed quote",
                3,
                chat.fixture.seam.submits
                    .get()
            )
        }

    @Test
    fun `a first message whose ZBOOT allowance could not be saved pays nothing`() =
        runBlocking {
            val chat = QuotedChat("usyntheticr10b1kexack000004", allowanceWrites = false)
            val vm = chat.viewModel()

            vm.sendMessage(chat.peer, "hello")
            awaitIdle(vm)
            assertEquals("fixture sanity: the ZBOOT is quoted", 3, quoted(vm).first)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(
                "a quote whose record was not saved was paid",
                0,
                chat.fixture.seam.proposalRequests
                    .get()
            )
            assertTrue(HandshakeHarness.sendMessageState(vm) is SendMessageState.Error)
        }

    // ---- (d) the relay key is known when the question is asked --------------------------------------------------

    @Test
    fun `with the relay key known the question counts two and a failed relay publish pays no on-chain ZBOOT`() =
        runBlocking {
            val publishes = AtomicInteger(0)
            NostrChatBridge.registerPublisher { _, _ ->
                publishes.incrementAndGet()
                NostrChatBridge.PublishResult(0, null)
            }
            val chat = QuotedChat("usyntheticr10b1relay0000001").apply { peerNostr[peer] = nostrHex(7) }
            val vm = chat.viewModel()
            val peerKeys = E2EEncryption.generateKeyPair()

            vm.sendMessage(chat.peer, "hello")
            awaitIdle(vm)
            assertEquals("the ZBOOT goes over the relays: the key exchange and the message", 2, quoted(vm).first)
            assertEquals(2 * ONE_TX, quoted(vm).second)
            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals(
                "fixture sanity: the KEX and the message",
                2,
                chat.fixture.seam.submits
                    .get()
            )

            handleKexMemo(vm, kexAckMemo(chat.peer, PaidChatFixture.CONV_ID, peerKeys), txId = "synthetic-r10b1-relay-01")
            awaitIdle(vm)

            assertEquals("fixture sanity: the ZBOOT was offered to the relays", 1, publishes.get())
            assertEquals(
                "a 0-ack relay publish fell back to an on-chain ZBOOT nobody quoted",
                2,
                chat.fixture.seam.submits
                    .get()
            )
        }

    /** Control, green before and after: an acknowledged relay publish delivers the ZBOOT free. */
    @Test
    fun `control - with the relay key known the ZBOOT goes over the relays for free`() =
        runBlocking {
            val publishes = AtomicInteger(0)
            NostrChatBridge.registerPublisher { _, _ ->
                publishes.incrementAndGet()
                NostrChatBridge.PublishResult(1, null)
            }
            val chat = QuotedChat("usyntheticr10b1relay0000002").apply { peerNostr[peer] = nostrHex(8) }
            val vm = chat.viewModel()
            val peerKeys = E2EEncryption.generateKeyPair()

            vm.sendMessage(chat.peer, "hello")
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)
            handleKexMemo(vm, kexAckMemo(chat.peer, PaidChatFixture.CONV_ID, peerKeys), txId = "synthetic-r10b1-relay-02")
            awaitIdle(vm)

            assertEquals(1, publishes.get())
            assertEquals(
                2,
                chat.fixture.seam.submits
                    .get()
            )
        }

    // ---- (e) the reply ZBOOT does not switch the chat to Tunnel -------------------------------------------------

    @Test
    fun `an initiator whose ZBOOT went out keeps its Shielded chat when the contact's reply ZBOOT arrives`() =
        runBlocking {
            val peer = "usyntheticr10b1mode00000001"
            val boot = BootChat(peer, mode = ConversationMode.VAULT, ourBootSent = true)

            assertTrue("fixture sanity: the reply ZBOOT verifies", boot.route())

            assertEquals("fixture sanity: the reply's relay key is adopted", boot.announced, boot.peerNostr[peer])
            assertEquals(
                "our own handshake's reply switched the Shielded chat to Tunnel",
                ConversationMode.VAULT,
                boot.mode,
            )
            assertFalse(
                "a 'Secure tunnel established' note was shown for a chat that did not change",
                boot.notes.contains(SysNotes.modeUpgradeNoteId(peer, 0L)),
            )
        }

    /** Control, green before and after (#233): a responder with no ZBOOT of its own follows the initiator to Tunnel. */
    @Test
    fun `control - a responder with no ZBOOT of its own adopts Tunnel on the initiator's ZBOOT`() =
        runBlocking {
            val peer = "usyntheticr10b1mode00000002"
            val boot = BootChat(peer, mode = null, ourBootSent = false)

            assertTrue(boot.route())

            assertEquals(ConversationMode.TUNNEL, boot.mode)
        }

    // ---- (f) one count for the Allow question and the Accept banner ---------------------------------------------

    @Test
    fun `the Allow question counts one transaction for a Shielded contact's KEXACK, as the Accept banner does`() =
        runBlocking {
            val peer = "usyntheticr10b1count000001"
            val prefs = responderPrefs(peer)
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(prefs.proxy, seam)

            vm.retryKexAckIfResponder(peer)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            val asked = consentAsk(vm)
            assertEquals("the KEXACK only: a Shielded chat's ZBOOT is never paid unquoted", 1, asked.pendingPeers)
            assertEquals(ONE_TX, asked.estimatedCost.value)
            assertEquals("the banner and the question disagree", asked.pendingPeers, acceptBannerCount(vm, peer))
            assertEquals("fixture sanity: nothing paid", 0, seam.proposalRequests.get())
        }

    @Test
    fun `the Allow question counts two for a Tunnel contact with no relay key, as the Accept banner does`() =
        runBlocking {
            val peer = "usyntheticr10b1count000002"
            val prefs = responderPrefs(peer)
            val store = intercept(prefs.proxy, mapOf("getConversationMode" to { _ -> ConversationMode.TUNNEL }))
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(store, seam)

            vm.retryKexAckIfResponder(peer)
            awaitIdle(vm)

            val asked = consentAsk(vm)
            assertEquals("the KEXACK and the ZBOOT that may follow it on-chain", 2, asked.pendingPeers)
            assertEquals("the banner and the question disagree", asked.pendingPeers, acceptBannerCount(vm, peer))
        }

    // --------------------------------------------------------------- harness

    /** (count, total zatoshi) of the open paid-action question. */
    private fun quoted(vm: ChatViewModel): Pair<Int, Long> {
        val state = HandshakeHarness.sendMessageState(vm)
        assertEquals(
            "the user must be asked before a first message is paid for, was $state",
            "NeedsPaidActionConfirmation",
            state.javaClass.simpleName,
        )
        val confirmation = (state as SendMessageState.NeedsPaidActionConfirmation).confirmation
        return confirmation.onChainMessages to confirmation.totalCost.value
    }

    private fun consentAsk(vm: ChatViewModel): SendMessageState.NeedsHandshakeSpendConsent =
        HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsHandshakeSpendConsent
            ?: throw AssertionError("expected the Allow question, was ${HandshakeHarness.sendMessageState(vm)}")

    /** ChatViewModel.chatRequestAcceptTransactions(peer), by name so this file compiles before it exists. */
    private fun acceptBannerCount(
        vm: ChatViewModel,
        peer: String,
    ): Int {
        val method =
            ChatViewModel::class.java.methods.firstOrNull { it.name == "chatRequestAcceptTransactions" }
                ?: throw AssertionError("the Accept banner has no count of its own (no ChatViewModel.chatRequestAcceptTransactions)")
        return try {
            method.invoke(vm, peer) as Int
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** We received [peer]'s first KEX (we are the responder); automatic chat setups are not allowed yet. */
    private fun responderPrefs(peer: String): HandshakePrefs {
        val peerKeys = E2EEncryption.generateKeyPair()
        return HandshakePrefs(peer, consent = false).apply {
            e2ePeerKey[peer] = peerKeys.publicKey
            receivedKex[peer] = peerKeys.publicKey
        }
    }

    /** A signed first-contact KEXACK from [peer] (its address fills the memo; no relay fields) under [convId]. */
    private fun kexAckMemo(
        peer: String,
        convId: String,
        keys: E2EKeyPair,
    ): String =
        ZMSGProtocol.createV4KEXAckMessage(
            convId,
            peer,
            E2EEncryption.createKEXAckPayload(peer, keys.publicKey, keys.privateKey),
        )

    private fun nostrHex(seed: Int): String =
        NOSTRIdentity
            .fromSeed(ByteArray(64) { (it + seed).toByte() })
            .publicKey
            .joinToString("") { "%02x".format(it) }

    /**
     * A new contact in a Shielded chat with no keys yet (PaidChatFixture), with the ZBOOT's stores kept for real: the
     * contact's E2E and relay keys, our delivered-identity marker, the paid-setup records and the per-contact "ZBOOT
     * quoted" allowance ([allowanceWrites] false: its write reports that it did not reach the disk).
     */
    private inner class QuotedChat(
        val peer: String,
        private val allowanceWrites: Boolean = true,
    ) {
        val fixture = PaidChatFixture(peer, ConversationMode.VAULT, haveOurKey = false)
        val peerE2E = ConcurrentHashMap<String, String>()
        val peerNostr = ConcurrentHashMap<String, String>()
        val sentNostrBoot = ConcurrentHashMap<String, String>()
        val paidRecords = ConcurrentHashMap<String, String>()
        val zbootQuoted = ConcurrentHashMap<String, Boolean>()

        private val store: ZchatPreferences =
            intercept(
                fixture.proxy,
                mapOf(
                    "getE2EPeerPublicKey" to { a -> peerE2E[a!![0] as String] },
                    "setE2EPeerPublicKey" to { a -> put(peerE2E, a!![0] as String, a[1] as String?) },
                    // ZC-634: the one write of a contact key (no claim marks are modelled here); always saved.
                    "storePeerKey" to { a -> put(peerE2E, a!![0] as String, a[1] as String?).let { true } },
                    "getPeerNostrPubkey" to { a -> peerNostr[a!![0] as String] },
                    "getSentNostrBootPubkey" to { a -> sentNostrBoot[a!![0] as String] },
                    "setSentNostrBootPubkey" to { a -> put(sentNostrBoot, a!![0] as String, a[1] as String?).let { true } },
                    "getHandshakePaidRecord" to { a -> paidRecords[a!![0] as String] },
                    "setHandshakePaidRecord" to { a -> put(paidRecords, a!![0] as String, a[1] as String?).let { true } },
                    "isZbootQuoted" to { a -> zbootQuoted[a!![0] as String] == true },
                    "setZbootQuoted" to { a ->
                        if (allowanceWrites) {
                            zbootQuoted[a!![0] as String] = a[1] as Boolean
                            true
                        } else {
                            false
                        }
                    },
                ),
            )

        suspend fun viewModel(): ChatViewModel =
            fixture.viewModel().also { AutoSpendVm.setField(it, "zchatPreferences", store) }.also {
                // A refused first message gives its typed text back (R3-2 (2)); the constructor-free harness leaves this unset.
                AutoSpendVm.setField(it, "_restoredTypedText", MutableStateFlow<Any?>(null))
            }
    }

    /**
     * The receiving side of a verified v3 reply ZBOOT from [peer]: we hold the peer's E2E key, the chat is in [mode] (not
     * picked by the user), and our own ZBOOT to them has ([ourBootSent]) or has not gone out yet. Records the mode writes
     * and the system notes.
     */
    private inner class BootChat(
        val peer: String,
        mode: ConversationMode?,
        ourBootSent: Boolean,
    ) {
        private val peerKeys = E2EEncryption.generateKeyPair()
        private val base = AutoSpendPrefs(consent = true)
        val peerNostr = ConcurrentHashMap<String, String>()
        val notes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val announced = nostrHex(21)

        @Volatile var mode: ConversationMode? = mode

        private val store: ZchatPreferences =
            intercept(
                base.proxy,
                mapOf(
                    "getConversationModeOrNull" to { _ -> this.mode },
                    "getConversationMode" to { _ -> this.mode ?: ConversationMode.VAULT },
                    "isConversationModeExplicit" to { _ -> false },
                    "setConversationMode" to { a -> null.also { this.mode = a!![1] as ConversationMode } },
                    "getPeerNostrPubkey" to { a -> peerNostr[a!![0] as String] },
                    "setPeerNostrPubkey" to { a -> null.also { put(peerNostr, a!![0] as String, a[1] as String?) } },
                    "addPendingMessage" to { a -> null.also { notes += (a!![0] as ZchatPreferences.PendingMessageData).id } },
                ),
            )

        init {
            base.peerKeys[peer] = peerKeys.publicKey
            // ZC-893 (FC-2B): a chat whose key exchange this phone took part in holds our key pair beside the contact key.
            base.ourKeys[peer] = E2EEncryption.generateKeyPair()
            if (ourBootSent) base.sentNostrBoot[peer] = nostrHex(3)
        }

        fun route(): Boolean =
            runBlocking {
                val vm = chatViewModelWith(store, HandshakeSeam(peer)).also(AutoSpendVm::wireBootstrapFields)
                AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
                val convId = "RBT10B01"
                val signature = E2EEncryption.sign(peerKeys.privateKey, ZBootMessage.signedDataFor(convId, announced, RELAY, 0L))
                val raw = ZBootMessage(convId, announced, RELAY, signature, epoch = 0L).serialize()
                val routed =
                    try {
                        ChatViewModel::class.java
                            .getDeclaredMethod("routeIncomingBoot", String::class.java, String::class.java)
                            .apply { isAccessible = true }
                            .invoke(vm, peer, raw) as Boolean
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                awaitIdle(vm)
                routed
            }
    }
}

private fun <V : Any> put(
    map: MutableMap<String, V>,
    key: String,
    value: V?,
): Any? {
    if (value == null) map.remove(key) else map[key] = value
    return null
}

/** [delegate] with the calls named in [handlers] answered by them instead. */
private fun intercept(
    delegate: ZchatPreferences,
    handlers: Map<String, (Array<Any?>?) -> Any?>,
): ZchatPreferences =
    Proxy.newProxyInstance(
        ZchatPreferences::class.java.classLoader,
        arrayOf(ZchatPreferences::class.java),
    ) { _, method, args ->
        val handler = handlers[method.name]
        if (handler != null) {
            handler(args)
        } else {
            try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    } as ZchatPreferences
