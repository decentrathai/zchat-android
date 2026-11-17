package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
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
import java.util.concurrent.ConcurrentHashMap

/**
 * T3b-1 (fix-all 2.13.0, train T3b; train T3 phone finding "money surprise", phones/logs/249-MONEY-SURPRISE.txt).
 *
 * At 2d739b5df the mode picker's Apply (AndroidChat onPick) calls changeConversationMode and then, for Tunnel or Open,
 * ensureNostrBootstrapSent at once. The bootstrap is not behind the price question: in a Shielded chat whose contact
 * has no relay key it paid the Tunnel key exchange (KEX, 17,000 zat, tx 9c73f993069d on the HONOR) under the standing
 * automatic-setup consent before "Pay for this?" was on screen. That question then quoted "1 on-chain transaction" for
 * the mode change alone (a control that cannot even go on-chain before the key exchange is finished), and its Cancel
 * could not undo the KEX. A contact who holds our key but not our relay identity gets our ZBOOT paid the same way, and
 * an Open chat switched to Tunnel pays its KEX with no question at all.
 *
 * Must hold, for every switch the picker makes: nothing is paid before the question; the question counts every
 * on-chain transaction the switch makes (the mode change when it goes on-chain, our KEX, our ZBOOT, including the one
 * sent after the contact answers) with the total; Cancel pays nothing and leaves the chat as it was; Pay and continue
 * pays no more than it quoted, and a switch that finds more to pay than it quoted asks again and pays nothing; a switch
 * with nothing on-chain changes the mode at once and asks nothing.
 *
 * The picker's call is reached by name (ChatViewModel.switchConversationMode). Where it does not exist yet the harness
 * replays what the picker calls at 2d739b5df (changeConversationMode, then ensureNostrBootstrapSent for a relay mode),
 * so a run before the fix shows the payment itself, not a missing method. ModePickerPaysOnlyThroughTheSwitchTest pins
 * that the picker calls the switch and nothing else. Drives the real ChatViewModel through the real use case and
 * repository (HandshakeHarness, AutoSpendPrefs). Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModeSwitchAsksBeforeEveryPaymentTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usynthetict3b1modeswitchpeer01"
        const val PEER_RELAY_KEY = "7b3c0f5a9e21d4c86b0a3f5e7d9c1b2a4e6f8091a3b5c7d9e1f2a4b6c8d0e2f4"
        const val ONE_TRANSACTION_ZAT = 17_000L
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

    /** The phone case: Shielded, no relay key, the key exchange not done, the standing consent given. */
    @Test
    fun `a Tunnel switch in a Shielded chat whose contact has no relay key pays nothing before its question`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)

            assertEquals("the switch paid before its question (the T3 money surprise)", 0, chat.seam.proposalRequests.get())
            // Our KEX now and our ZBOOT once the contact answers; the mode change itself cannot go on-chain before the
            // key exchange is finished, so it is not counted.
            assertAsked(vm, transactions = 2, setup = true)
            assertEquals("nothing changes before the user confirms", ConversationMode.VAULT, chat.mode)
            assertFalse("the KEX marker went down before the question", chat.prefs.ownBootSent[PEER] == true)
        }

    @Test
    fun `Cancel pays nothing and leaves the chat as it was`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false)
            val vm = chat.viewModel()
            val ourKeyBefore = chat.prefs.ourKeys[PEER]

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            vm.dismissPaidAction()
            awaitIdle(vm)

            assertEquals("Cancel still paid", 0, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.VAULT, chat.mode)
            assertFalse("Cancel left the KEX armed", chat.prefs.ownBootSent[PEER] == true)
            assertTrue("Cancel left a mode change to deliver", chat.pendingModeSyncs.isEmpty())
            assertFalse("Cancel left a ZBOOT allowance", PEER in chat.zbootQuoted)
            assertEquals("Cancel changed our key", ourKeyBefore, chat.prefs.ourKeys[PEER])
            assertNoQuestion(vm)
        }

    /**
     * Pay and continue is the consent for what it quoted: the KEX is paid now with no second (automatic-setup)
     * question, and the ZBOOT after the contact's answer against the allowance it left, once. Never more than quoted.
     */
    @Test
    fun `Pay and continue pays the key exchange now and the relay identity once the contact answers, never more`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false, consent = false)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            assertEquals(0, chat.seam.proposalRequests.get())
            assertAsked(vm, transactions = 2, setup = true)
            confirm(vm)
            awaitIdle(vm)

            assertEquals("Pay and continue pays our KEX now", 1, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.TUNNEL, chat.mode)
            assertNoQuestion(vm)

            handleKexMemo(vm, kexAckMemo(chat.prefs.convIdFor(PEER), E2EEncryption.generateKeyPair()), "synthetic-t3b1-01")
            awaitIdle(vm)

            assertEquals("the contact's answer pays our quoted ZBOOT", 2, chat.seam.proposalRequests.get())
            assertFalse("the ZBOOT allowance must be used up", PEER in chat.zbootQuoted)
            assertNoQuestion(vm)
        }

    @Test
    fun `a switch whose contact holds our key but not our relay identity counts the mode change and the ZBOOT`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = true)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)

            assertEquals("the switch paid our ZBOOT before its question", 0, chat.seam.proposalRequests.get())
            assertAsked(vm, transactions = 2, setup = true)
            confirm(vm)
            awaitIdle(vm)
            assertEquals("the mode change and our ZBOOT, as quoted", 2, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.TUNNEL, chat.mode)
        }

    @Test
    fun `an Open chat switched to Tunnel asks before its key exchange`() =
        runBlocking {
            val chat = Chat(ConversationMode.OPEN, keysBothWays = false, relayKey = true)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)

            assertEquals("an Open chat's Tunnel switch paid a KEX with no question", 0, chat.seam.proposalRequests.get())
            // Our KEX, and our ZBOOT on-chain: the relays are not reachable from this phone right now.
            assertAsked(vm, transactions = 2, setup = true)
            assertEquals(ConversationMode.OPEN, chat.mode)
        }

    @Test
    fun `Cancel on an Open chat's Tunnel question leaves it Open and pays nothing`() =
        runBlocking {
            val chat = Chat(ConversationMode.OPEN, keysBothWays = false, relayKey = true)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            vm.dismissPaidAction()
            awaitIdle(vm)

            assertEquals(0, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.OPEN, chat.mode)
            assertFalse(chat.prefs.ownBootSent[PEER] == true)
        }

    /** The earlier KEX failed and was re-armed while the question was open: there is now more to pay than was quoted. */
    @Test
    fun `a switch that finds more to pay at Pay and continue than it quoted asks again and pays nothing`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false)
            chat.prefs.ownBootSent[PEER] = true // our KEX went out earlier; the contact has not answered
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            assertAsked(vm, transactions = 1, setup = null) // only our ZBOOT, after the answer

            chat.prefs.ownBootSent[PEER] = false // that KEX provably sent nothing and was re-armed
            confirm(vm)
            awaitIdle(vm)

            assertEquals("a switch paid more than it quoted", 0, chat.seam.proposalRequests.get())
            assertAsked(vm, transactions = 2, setup = true)
            assertEquals(ConversationMode.VAULT, chat.mode)
        }

    /** The mode change of a Shielded chat whose key exchange is not finished can only be kept on this phone. */
    @Test
    fun `the mode change of a Shielded chat with no key exchange asks nothing, since it cannot go on-chain`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false)
            val vm = chat.viewModel()

            vm.changeConversationMode(PEER, ConversationMode.TUNNEL)
            awaitIdle(vm)

            assertNoQuestion(vm)
            assertEquals(0, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.TUNNEL, chat.mode)
            assertEquals(ChatSendCopy.MODE_NEEDS_KEY_EXCHANGE, (HandshakeHarness.sendMessageState(vm) as? SendMessageState.Error)?.message)
        }

    /** Control, green before and after: leaving Tunnel for Shielded pays nothing, so nothing is asked. */
    @Test
    fun `control - a switch with nothing to pay changes the mode at once and asks nothing`() =
        runBlocking {
            val chat = Chat(ConversationMode.TUNNEL, keysBothWays = true)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.VAULT)
            awaitIdle(vm)

            assertNoQuestion(vm)
            assertEquals(0, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.VAULT, chat.mode)
        }

    /**
     * T3b fixup (cross-family review G-T3b1-1): the switch sends our KEX, so our ZBOOT can only go after the contact's
     * answer. The relays are up when the question is asked and down when the answer arrives. The question must have
     * counted that ZBOOT, and the answer pays it against the question's allowance: never more than was quoted.
     */
    @Test
    fun `a switch sending our key exchange counts our relay identity with the relays up, and pays no more with them down`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false, relayKey = true)
            relays(acks = 1)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            assertEquals("the switch paid before its question", 0, chat.seam.proposalRequests.get())
            val quoted = answerTheQuestion(vm)
            assertEquals("Pay and continue pays our KEX now", 1, chat.seam.proposalRequests.get())

            relays(acks = null) // the relays are down when the contact's KEXACK arrives
            handleKexMemo(vm, kexAckMemo(chat.prefs.convIdFor(PEER), E2EEncryption.generateKeyPair()), "synthetic-t3b1-fx-01")
            awaitIdle(vm)

            val paid = chat.seam.proposalRequests.get()
            assertTrue("the switch paid $paid on-chain transactions against a quote of $quoted", paid <= quoted)
            assertEquals("our KEX and our ZBOOT, both counted", 2, quoted)
            assertEquals("the answer pays our quoted ZBOOT", 2, paid)
            assertFalse("the ZBOOT allowance must be used up", PEER in chat.zbootQuoted)
            assertNoQuestion(vm)
        }

    /** The same, with the relays reachable at the answer but acknowledging nothing (the ZBOOT falls back on-chain). */
    @Test
    fun `a switch that sends our key exchange pays no more than quoted when the relays acknowledge nothing at the answer`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false, relayKey = true)
            relays(acks = 1)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            val quoted = answerTheQuestion(vm)

            relays(acks = 0)
            handleKexMemo(vm, kexAckMemo(chat.prefs.convIdFor(PEER), E2EEncryption.generateKeyPair()), "synthetic-t3b1-fx-02")
            awaitIdle(vm)

            val paid = chat.seam.proposalRequests.get()
            assertTrue("the switch paid $paid on-chain transactions against a quote of $quoted", paid <= quoted)
            assertEquals("our KEX and our ZBOOT, both counted", 2, quoted)
            assertFalse("the ZBOOT allowance must be used up", PEER in chat.zbootQuoted)
            assertNoQuestion(vm)
        }

    /**
     * Our KEX went out earlier and the contact has not answered; the relays are up at the switch. The switch sends
     * nothing now, but it turns the ZBOOT that follows the answer into one a Tunnel chat pays: the question counts it,
     * and when the relays are down at the answer no more is paid than was quoted.
     */
    @Test
    fun `a switch whose key exchange is already out counts the relay identity sent after the answer, whatever the relays are now`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false, relayKey = true)
            chat.prefs.ownBootSent[PEER] = true
            relays(acks = 1)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            val quoted = answerTheQuestion(vm)
            assertEquals(0, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.TUNNEL, chat.mode)

            relays(acks = null)
            handleKexMemo(vm, kexAckMemo(chat.prefs.convIdFor(PEER), E2EEncryption.generateKeyPair()), "synthetic-t3b1-fx-03")
            awaitIdle(vm)

            val paid = chat.seam.proposalRequests.get()
            assertTrue("the switch paid $paid on-chain transactions against a quote of $quoted", paid <= quoted)
            assertEquals("our ZBOOT after the answer, counted", 1, quoted)
            assertFalse("the ZBOOT allowance must be used up", PEER in chat.zbootQuoted)
        }

    /**
     * The quoted ZBOOT goes free over the relays at the answer: nothing more is paid, and the allowance it no longer
     * needs is withdrawn, so it cannot pay a later ZBOOT that no question counted.
     */
    @Test
    fun `a quoted relay identity that goes free over the relays pays nothing and leaves no allowance`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false, relayKey = true)
            relays(acks = 1)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            answerTheQuestion(vm)
            handleKexMemo(vm, kexAckMemo(chat.prefs.convIdFor(PEER), E2EEncryption.generateKeyPair()), "synthetic-t3b1-fx-04")
            awaitIdle(vm)

            assertEquals("only our KEX is paid; the ZBOOT went over the relays", 1, chat.seam.proposalRequests.get())
            assertTrue("our relay identity was not delivered", chat.prefs.sentNostrBoot[PEER] != null)
            assertFalse("an allowance outlived the ZBOOT it counted", PEER in chat.zbootQuoted)
        }

    /** A quoted ZBOOT paid on another trigger (chat open, standing consent) uses up the allowance too. */
    @Test
    fun `a quoted relay identity paid on a chat open uses up its allowance`() =
        runBlocking {
            val chat = Chat(ConversationMode.TUNNEL, keysBothWays = true)
            chat.zbootQuoted += PEER
            val vm = chat.viewModel()

            vm.ensureNostrBootstrapSent(PEER)
            awaitIdle(vm)

            assertEquals("the chat open pays our ZBOOT on-chain (no relay key)", 1, chat.seam.proposalRequests.get())
            assertFalse("an allowance outlived the ZBOOT it counted", PEER in chat.zbootQuoted)
        }

    // --------------------------------------------------------------- harness

    /** What the mode picker's Apply calls: the switch, or, before it exists, the picker's calls at 2d739b5df. */
    private fun pick(
        vm: ChatViewModel,
        mode: ConversationMode,
    ) {
        val switch = ChatViewModel::class.java.methods.firstOrNull { it.name == "switchConversationMode" && it.parameterCount == 2 }
        if (switch != null) {
            try {
                switch.invoke(vm, PEER, mode)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } else {
            vm.changeConversationMode(PEER, mode)
            if (mode != ConversationMode.VAULT) vm.ensureNostrBootstrapSent(PEER)
        }
    }

    private fun confirm(vm: ChatViewModel) = vm.confirmPaidAction()

    /** The on-chain count of the question on screen, answered with Pay and continue; 0 when nothing was asked. */
    private suspend fun answerTheQuestion(vm: ChatViewModel): Int {
        val state = HandshakeHarness.sendMessageState(vm)
        if (state.javaClass.simpleName != "NeedsPaidActionConfirmation") return 0
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        val count = confirmation.javaClass.getMethod("getOnChainMessages").invoke(confirmation) as Int
        confirm(vm)
        awaitIdle(vm)
        return count
    }

    /** The relays as this phone sees them: reachable, each publish acknowledged by [acks] relays; null: not reachable. */
    private fun relays(acks: Int?) {
        NostrChatBridge.unregisterPublisher()
        if (acks != null) NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks, "synthetic-relay-event") }
    }

    private fun assertAsked(
        vm: ChatViewModel,
        transactions: Int,
        setup: Boolean?,
    ) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertEquals("the user must be asked first, was $state", "NeedsPaidActionConfirmation", state.javaClass.simpleName)
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        val count = confirmation.javaClass.getMethod("getOnChainMessages").invoke(confirmation) as Int
        val total = confirmation.javaClass.getMethod("getTotalCost").invoke(confirmation)
        val totalZat = total.javaClass.getMethod("getValue").invoke(total) as Long
        assertEquals("on-chain transactions quoted", transactions, count)
        assertTrue("total quoted ($totalZat) below $transactions x 17,000 zatoshi", totalZat >= transactions * ONE_TRANSACTION_ZAT)
        if (setup == null) return
        val setupFlag =
            runCatching { confirmation.javaClass.getMethod("getIncludesChatSetup").invoke(confirmation) as Boolean }
                .getOrElse { throw AssertionError("the question cannot say that it pays for the chat setup (includesChatSetup)") }
        assertEquals("the question must say it also pays for the chat setup", setup, setupFlag)
    }

    private fun assertNoQuestion(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertFalse("a question is on screen: $state", state.javaClass.simpleName.startsWith("Needs"))
    }

    /** A signed first-contact KEXACK from [PEER] (its address fills the memo; no relay fields) under [convId]. */
    private fun kexAckMemo(
        convId: String,
        keys: E2EKeyPair,
    ): String =
        ZMSGProtocol.createV4KEXAckMessage(
            convId,
            PEER,
            E2EEncryption.createKEXAckPayload(PEER, keys.publicKey, keys.privateKey),
        )

    /**
     * A chat with [PEER] in [initialMode]: our E2E key stored, and theirs too when [keysBothWays]; their relay key known
     * when [relayKey] (the relays are not reachable unless a case sets them with [relays]); the standing automatic-setup
     * consent is [consent]; the message-cost notice was accepted. The mode, the pending mode deliveries and the ZBOOT allowances are kept.
     */
    private class Chat(
        initialMode: ConversationMode,
        keysBothWays: Boolean,
        relayKey: Boolean = false,
        consent: Boolean = true,
    ) {
        val prefs = AutoSpendPrefs(consent = consent).apply { session(PEER, complete = keysBothWays, mode = initialMode) }
        val seam = HandshakeSeam(PEER)
        val zbootQuoted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val pendingModeSyncs = ConcurrentHashMap<String, String>()
        private val relayKeys = ConcurrentHashMap<String, String>().apply { if (relayKey) put(PEER, PEER_RELAY_KEY) }
        private val withKeys = keysBothWays

        val mode: ConversationMode get() = prefs.modes[PEER] ?: ConversationMode.VAULT

        private val store: ZchatPreferences =
            intercept(
                prefs.proxy,
                mapOf(
                    "hasAcknowledgedMessageCost" to { _ -> true },
                    "setConversationMode" to { a ->
                        (a!![1] as ConversationMode?)?.let { prefs.modes[a[0] as String] = it } ?: prefs.modes.remove(a[0] as String)
                        null
                    },
                    "getConversationModeOrNull" to { a -> prefs.modes[a!![0] as String] },
                    "getPeerNostrPubkey" to { a -> relayKeys[a!![0] as String] },
                    "isZbootQuoted" to { a -> a!![0] in zbootQuoted },
                    "setZbootQuotedAccount" to { _ -> true },
                    "setZbootQuoted" to { a ->
                        if (a!![1] as Boolean) zbootQuoted += a[0] as String else zbootQuoted -= a[0] as String
                        true
                    },
                    "setPendingModeSync" to { a ->
                        pendingModeSyncs[a!![0] as String] = a[1] as String
                        null
                    },
                    "clearPendingModeSync" to { a ->
                        pendingModeSyncs.remove(a!![0] as String)
                        null
                    },
                ),
            )

        suspend fun viewModel(): ChatViewModel {
            val vm = chatViewModelWith(store, seam).also(AutoSpendVm::wireBootstrapFields)
            val processors = ConcurrentHashMap<String, E2EMessageProcessor>()
            if (withKeys) {
                val convId = prefs.convIdFor(PEER)
                processors["$PEER:$convId"] = E2EMessageProcessor(ByteArray(32) { 7 }, convId, true, InMemoryRatchetStateStore())
            }
            AutoSpendVm.setField(vm, "messageProcessors", processors)
            AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
            AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
            AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
            AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
            AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
            return vm
        }
    }
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
