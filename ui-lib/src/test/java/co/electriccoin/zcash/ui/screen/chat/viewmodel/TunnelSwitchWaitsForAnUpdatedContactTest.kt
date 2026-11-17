package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1107 (medium; phones 26 Oct, HONOR 2.13 -> Fold 2.12.4, ledger train-T7a-2026/ZC-033/LEDGER.md).
 *
 * At 04f45ec1c the mode picker's Tunnel switch (ChatViewModel.switchConversationMode -> planModeSwitch) asks "Pay and
 * continue" and pays our key exchange (17,000 zat) also when the app already knows the contact is still on ZChat 2.12.4:
 * its newest relay rumor carried no Ironwood capability tag (the SR-C1 record, ZchatPreferences.getPeerIronwoodCap,
 * capIronwood == false). That key exchange goes to the contact's Ironwood receiver, which 2.12.4 cannot see, so the
 * handshake never completes and the money buys nothing.
 *
 * Must hold:
 * - a Tunnel switch that would pay anything on-chain, with a contact whose newest relay rumor has no Ironwood tag, pays
 *   nothing (0 proposals, before or after any Pay and continue), shows no price question, leaves the chat's mode as it
 *   was, and tells the user the contact needs ZChat 2.13 or newer first (SendMessageState.Error with the new
 *   ChatSendCopy.MODE_TUNNEL_CONTACT_NEEDS_UPDATE, shown through its resource chat_send_mode_tunnel_contact_needs_update);
 * - the same when the contact's old rumor arrives while the question is open: Pay and continue re-plans and pays nothing;
 * - guards (green before and after): a contact whose newest rumor carries the tag, and a contact with no rumor on record
 *   (unknown: allowed, as today, with the T3b-1 question), are asked and pay as today; a switch away from Tunnel with an
 *   old contact pays nothing and is not refused.
 *
 * ZC-1127 (low): the check reads only the contact's last RELAY message, so a contact who updated and has since written only
 * on-chain stays refused. The notice says what the check reads and what they can do; the old text ("this contact's last
 * message came from ZChat 2.12 ... Ask them to update to ZChat 2.13 or newer") is replaced
 * [test-change-ok: ZC-1127 wording — the check reads the last relay message]: NOTICE_EN and the Spanish check below.
 *
 * ZC-1125 (medium, checked: no bug): a switch to OPEN with the same contact puts nothing on-chain at 099bb2983, so it is not
 * refused. Guards pin that: with the relays down, with or without keys, Pay and continue makes 0 proposals; with the
 * relays up the switch changes the mode and is not refused.
 *
 * The constant is reached by name, so the file compiles before it exists. Harness as in
 * ModeSwitchAsksBeforeEveryPaymentTest (real ChatViewModel, real use case and repository via HandshakeHarness and
 * AutoSpendPrefs). Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelSwitchWaitsForAnUpdatedContactTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1107tunneloldpeer01"
        const val PEER_RELAY_KEY = "4d2a9c7e1b3f5a6c8e0d2b4f6a8c0e1d3b5f7a9c1e3d5b7f9a0c2e4d6b8f0a1c"
        const val ONE_TRANSACTION_ZAT = 17_000L
        const val RUMOR_AT_SEC = 1_790_000_000L
        const val NOTICE_FIELD = "MODE_TUNNEL_CONTACT_NEEDS_UPDATE"
        const val NOTICE_KEY = "chat_send_mode_tunnel_contact_needs_update"
        const val NOTICE_EN =
            "Not switched to Tunnel — the last message this contact sent over the relay came from ZChat 2.12 or older, " +
                "which can't finish a Tunnel handshake. If they have updated, ask them to switch to Tunnel from their " +
                "side, or to send you any message over the relay first. Nothing was paid."
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

    /** The phone case: Shielded, the key exchange not done, the contact's newest relay rumor came from 2.12.4. */
    @Test
    fun `a paid Tunnel switch with a contact on 2_12 pays nothing, asks nothing and says they need 2_13`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false, ironwoodCap = false)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            vm.confirmPaidAction() // a stray tap on a question that must not be there pays nothing either
            awaitIdle(vm)

            assertEquals("ZC-1107: the Tunnel switch paid a contact still on 2.12.4", 0, chat.seam.proposalRequests.get())
            assertNoQuestionAndTheNotice(vm)
            assertEquals("the chat must stay as it was", ConversationMode.VAULT, chat.mode)
            assertFalse("the KEX marker went down for a contact on 2.12.4", chat.prefs.ownBootSent[PEER] == true)
            assertFalse("a ZBOOT allowance was left for a contact on 2.12.4", PEER in chat.zbootQuoted)
        }

    /** An Open chat with a 2.12.4 contact (its rumors carry no tag): the Tunnel switch would pay our KEX. */
    @Test
    fun `an Open chat's Tunnel switch with a contact on 2_12 pays nothing and says they need 2_13`() =
        runBlocking {
            val chat = Chat(ConversationMode.OPEN, keysBothWays = false, relayKey = true, ironwoodCap = false)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals("ZC-1107: the Tunnel switch paid a contact still on 2.12.4", 0, chat.seam.proposalRequests.get())
            assertNoQuestionAndTheNotice(vm)
            assertEquals(ConversationMode.OPEN, chat.mode)
            assertFalse(chat.prefs.ownBootSent[PEER] == true)
        }

    /** The contact's old rumor lands while the question is open: Pay and continue re-plans and pays nothing. */
    @Test
    fun `a contact found on 2_12 while the question is open makes Pay and continue pay nothing`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false, ironwoodCap = null)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            assertAsked(vm, transactions = 2)

            chat.cap = false
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals("ZC-1107: Pay and continue paid a contact found on 2.12.4", 0, chat.seam.proposalRequests.get())
            assertNoQuestionAndTheNotice(vm)
            assertEquals(ConversationMode.VAULT, chat.mode)
            assertFalse(chat.prefs.ownBootSent[PEER] == true)
        }

    /** Guard: a contact whose newest rumor carries the Ironwood tag is asked and pays as today. */
    @Test
    fun `guard - a contact on 2_13 is asked and pays the key exchange as today`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false, ironwoodCap = true)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            assertEquals(0, chat.seam.proposalRequests.get())
            assertAsked(vm, transactions = 2)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals("Pay and continue pays our KEX now", 1, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.TUNNEL, chat.mode)
        }

    /** Guard: no relay rumor on record (unknown) is not a reason to refuse: asked as today ("both need 2.13" text). */
    @Test
    fun `guard - a contact with no relay rumor on record is asked and pays as today`() =
        runBlocking {
            val chat = Chat(ConversationMode.OPEN, keysBothWays = false, relayKey = true, ironwoodCap = null)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)
            assertEquals(0, chat.seam.proposalRequests.get())
            assertAsked(vm, transactions = 2)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals("Pay and continue pays our KEX now", 1, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.TUNNEL, chat.mode)
        }

    /** Guard: leaving Tunnel for Shielded pays nothing, so an old contact is no reason to refuse it. */
    @Test
    fun `guard - a switch away from Tunnel with a contact on 2_12 changes the mode and is not refused`() =
        runBlocking {
            val chat = Chat(ConversationMode.TUNNEL, keysBothWays = true, ironwoodCap = false)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.VAULT)
            awaitIdle(vm)

            assertEquals(0, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.VAULT, chat.mode)
            val state = HandshakeHarness.sendMessageState(vm)
            assertFalse("a question is on screen: $state", state.javaClass.simpleName.startsWith("Needs"))
            // The notice may not exist yet (this guard passes before the fix): any refusal text here would be wrong too.
            val notice = runCatching { noticeText() }.getOrNull()
            if (notice != null) assertNotEquals("a free switch was refused", notice, (state as? SendMessageState.Error)?.message)
        }

    /** The notice is a ChatSendCopy constant equal to its English resource, shown through it, with a Spanish line. */
    @Test
    fun `the notice is a resource in English and Spanish and says the check reads the last relay message`() {
        val text = noticeText()
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        assertEquals("the English text of $NOTICE_KEY", NOTICE_EN, en[NOTICE_KEY])
        assertEquals("the constant differs from its English resource", en[NOTICE_KEY], text)
        assertEquals(ChatResourceText.stringId(NOTICE_KEY), ChatSendCopy.localizedResource(text))
        val spanish = es[NOTICE_KEY] ?: throw AssertionError("no Spanish string $NOTICE_KEY")
        assertNotEquals("$NOTICE_KEY is not translated", NOTICE_EN, spanish)
        assertTrue("the Spanish line does not name 2.12", spanish.contains("2.12"))
        assertTrue("ZC-1127: the Spanish line does not say the relay message was checked", spanish.contains("relay"))
        assertTrue("the Spanish line keeps the mode name Tunnel", spanish.contains("Tunnel"))
    }

    // --------------------------------------------------------------- ZC-1125: the switch to Open (guards)

    /**
     * ZC-1125 guard: a switch to Open never puts anything on-chain, so a contact on 2.12 loses nothing to it. The plan
     * may still count our ZBOOT (relays down) and ask, but Pay and continue pays nothing: the bootstrap skips the
     * on-chain KEX for Open, the ZBOOT stops at openNeverSpends, and a mode change to Open never goes on-chain.
     */
    @Test
    fun `guard ZC-1125 - an Open switch with a contact on 2_12 puts nothing on-chain with the relays down`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = true, relayKey = true, ironwoodCap = false)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.OPEN)
            awaitIdle(vm)
            vm.confirmPaidAction() // Pay and continue, if a question is on screen
            awaitIdle(vm)

            assertEquals("ZC-1125: the Open switch paid a contact still on 2.12.4", 0, chat.seam.proposalRequests.get())
            assertFalse("our KEX marker went down for a contact on 2.12.4", chat.prefs.ownBootSent[PEER] == true)
        }

    /** ZC-1125 guard: no keys and no relay key (the plan counts a KEX and a ZBOOT): an Open switch still pays nothing. */
    @Test
    fun `guard ZC-1125 - an Open switch with a contact on 2_12 and no keys puts nothing on-chain`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false, relayKey = false, ironwoodCap = false)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.OPEN)
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals("ZC-1125: the Open switch paid a contact still on 2.12.4", 0, chat.seam.proposalRequests.get())
            assertFalse("our KEX marker went down for a contact on 2.12.4", chat.prefs.ownBootSent[PEER] == true)
        }

    /** Guard: with the relays up the Open switch pays nothing on-chain, so a contact on 2.12 is no reason to refuse it. */
    @Test
    fun `guard - a free Open switch with a contact on 2_12 changes the mode and is not refused`() =
        runBlocking {
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(1, "synthetic-relay-event") }
            val chat = Chat(ConversationMode.VAULT, keysBothWays = true, relayKey = true, ironwoodCap = false)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.OPEN)
            awaitIdle(vm)

            assertEquals(0, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.OPEN, chat.mode)
            val state = HandshakeHarness.sendMessageState(vm)
            assertFalse("a question is on screen: $state", state.javaClass.simpleName.startsWith("Needs"))
            assertFalse("a free Open switch was refused: $state", state is SendMessageState.Error)
        }

    // --------------------------------------------------------------- harness

    private fun noticeText(): String =
        try {
            ChatSendCopy::class.java.getField(NOTICE_FIELD).get(null) as String
        } catch (e: NoSuchFieldException) {
            throw AssertionError("ChatSendCopy has no $NOTICE_FIELD (ZC-1107 notice)", e)
        }

    private fun pick(
        vm: ChatViewModel,
        mode: ConversationMode,
    ) = vm.switchConversationMode(PEER, mode)

    private fun assertNoQuestionAndTheNotice(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertFalse("ZC-1107: the price question was shown for a contact on 2.12.4: $state", state.javaClass.simpleName.startsWith("Needs"))
        assertEquals("ZC-1107: the user is not told the contact needs 2.13", noticeText(), (state as? SendMessageState.Error)?.message)
    }

    private fun assertAsked(
        vm: ChatViewModel,
        transactions: Int,
    ) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertTrue("the user must be asked first, was $state", state is SendMessageState.NeedsPaidActionConfirmation)
        val confirmation = (state as SendMessageState.NeedsPaidActionConfirmation).confirmation
        assertEquals("on-chain transactions quoted", transactions, confirmation.onChainMessages)
        assertTrue("total quoted below $transactions x 17,000 zatoshi", confirmation.totalCost.value >= transactions * ONE_TRANSACTION_ZAT)
        assertTrue("the question must say it also pays for the chat setup", confirmation.includesChatSetup)
    }

    /**
     * A chat with [PEER] in [initialMode] (as ModeSwitchAsksBeforeEveryPaymentTest.Chat), plus the SR-C1 record of the
     * contact's newest relay rumor: [cap] true = carried the Ironwood tag, false = did not (2.12.4), null = none recorded.
     */
    private class Chat(
        initialMode: ConversationMode,
        keysBothWays: Boolean,
        relayKey: Boolean = false,
        ironwoodCap: Boolean?,
    ) {
        val prefs = AutoSpendPrefs(consent = true).apply { session(PEER, complete = keysBothWays, mode = initialMode) }
        val seam = HandshakeSeam(PEER)
        val zbootQuoted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val pendingModeSyncs = ConcurrentHashMap<String, String>()
        private val relayKeys = ConcurrentHashMap<String, String>().apply { if (relayKey) put(PEER, PEER_RELAY_KEY) }
        private val withKeys = keysBothWays

        @Volatile var cap: Boolean? = ironwoodCap

        val mode: ConversationMode get() = prefs.modes[PEER] ?: ConversationMode.VAULT

        private val store: ZchatPreferences =
            withPrefsHandlers(
                prefs.proxy,
                mapOf(
                    "hasAcknowledgedMessageCost" to { _ -> true },
                    "setConversationMode" to { a ->
                        (a!![1] as ConversationMode?)?.let { prefs.modes[a[0] as String] = it } ?: prefs.modes.remove(a[0] as String)
                        null
                    },
                    "getConversationModeOrNull" to { a -> prefs.modes[a!![0] as String] },
                    "getPeerNostrPubkey" to { a -> relayKeys[a!![0] as String] },
                    "getPeerIronwoodCap" to { a ->
                        val known = cap
                        if (a!![0] == PEER && known != null) ZchatPreferences.PeerIronwoodCap(known, RUMOR_AT_SEC) else null
                    },
                    "isZbootQuoted" to { a -> a!![0] in zbootQuoted },
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
private fun withPrefsHandlers(
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
