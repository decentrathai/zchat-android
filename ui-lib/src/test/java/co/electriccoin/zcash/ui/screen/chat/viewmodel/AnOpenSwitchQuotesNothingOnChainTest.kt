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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1131 (medium, money path).
 *
 * At 099bb2983, for a Shielded chat with the relays down, ChatViewModel.modeSwitchPlan counts our ZBOOT (and our KEX when
 * there is no relay key) for a switch to OPEN, so the mode picker asks "Pay and continue" for 1-2 transactions that Open
 * never pays: a mode change to Open never goes on-chain (modeChangeGoesOnChain), startNostrBootstrap sets skipOnChainKex
 * for Open, and the ZBOOT sender stops at openNeverSpends. On Pay and continue, recordZbootQuote leaves a ZBOOT allowance
 * (ZchatPreferences.isZbootQuoted) that a later switch back to Shielded or Tunnel could spend under that old quote.
 *
 * Must hold:
 * - (a) Shielded, relays down, switch to Open: no price question (the plan counts 0 on-chain transactions), the mode
 *   becomes Open, 0 proposals; with keys and a relay key (the plan counted a ZBOOT) and with neither (a KEX and a ZBOOT);
 * - (b) after such a switch, Pay and continue tapped or not, no ZBOOT allowance is left; an allowance an older confirmed
 *   question left is withdrawn by the switch to Open, and a later switch to Tunnel asks again on its own quote;
 * - guard (green before and after): a Shielded chat's switch to Tunnel with the relays down asks and pays as today.
 *
 * Harness as in TunnelSwitchWaitsForAnUpdatedContactTest (real ChatViewModel, real use case and repository via
 * HandshakeHarness and AutoSpendPrefs; relays down = no publisher registered). Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnOpenSwitchQuotesNothingOnChainTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1131openswitchpeer01"
        const val PEER_RELAY_KEY = "6b1d3f5a7c9e0b2d4f6a8c0e2b4d6f8a0c2e4b6d8f0a1c3e5b7d9f1a3c5e7b9d"
        const val ONE_TRANSACTION_ZAT = 17_000L
        const val RUMOR_AT_SEC = 1_790_000_000L
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher() // the relays are down
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // --------------------------------------------------------------- (a) no question for a switch to Open

    /** Keys both ways and the contact's relay key: the plan counted our ZBOOT (1 transaction) for Open. */
    @Test
    fun `a Shielded chat's Open switch with the relays down asks nothing and becomes Open`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = true, relayKey = true)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.OPEN)
            awaitIdle(vm)

            assertNoQuestion(vm)
            assertEquals("ZC-1131: the Open switch did not change the mode", ConversationMode.OPEN, chat.mode)
            assertEquals("an Open switch made a proposal", 0, chat.seam.proposalRequests.get())
            assertFalse("a ZBOOT allowance was left by an Open switch", PEER in chat.zbootQuoted)
        }

    /** No keys and no relay key: the plan counted our KEX and our ZBOOT (2 transactions) for Open. */
    @Test
    fun `a Shielded chat's Open switch with no keys and the relays down asks nothing and becomes Open`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false, relayKey = false)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.OPEN)
            awaitIdle(vm)

            assertNoQuestion(vm)
            assertEquals("ZC-1131: the Open switch did not change the mode", ConversationMode.OPEN, chat.mode)
            assertEquals("an Open switch made a proposal", 0, chat.seam.proposalRequests.get())
            assertFalse("our KEX marker went down for an Open switch", chat.prefs.ownBootSent[PEER] == true)
        }

    // --------------------------------------------------------------- (b) no ZBOOT allowance outlives an Open switch

    /** Pay and continue on the Open switch (if a question is on screen) leaves no ZBOOT allowance behind. */
    @Test
    fun `an Open switch leaves no ZBOOT allowance even after Pay and continue`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = true, relayKey = true)
            val vm = chat.viewModel()

            pick(vm, ConversationMode.OPEN)
            awaitIdle(vm)
            vm.confirmPaidAction() // Pay and continue, if a question is on screen
            awaitIdle(vm)

            assertEquals("an Open switch made a proposal", 0, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.OPEN, chat.mode)
            assertFalse("ZC-1131: the Open switch left a ZBOOT allowance (recordZbootQuote)", PEER in chat.zbootQuoted)
        }

    /** An allowance an older confirmed question left is withdrawn by the switch to Open; Tunnel later asks again. */
    @Test
    fun `an Open switch withdraws an old ZBOOT allowance and a later Tunnel switch asks on its own quote`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false, relayKey = false)
            chat.zbootQuoted += PEER // a ZBOOT allowance an older confirmed question left
            val vm = chat.viewModel()

            pick(vm, ConversationMode.OPEN)
            awaitIdle(vm)
            vm.confirmPaidAction() // a stray tap pays nothing either
            awaitIdle(vm)

            assertEquals("an Open switch made a proposal", 0, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.OPEN, chat.mode)
            assertFalse("ZC-1131: a ZBOOT allowance outlived the switch to Open", PEER in chat.zbootQuoted)

            pick(vm, ConversationMode.TUNNEL)
            awaitIdle(vm)

            assertEquals("the Tunnel switch paid before its question", 0, chat.seam.proposalRequests.get())
            assertAsked(vm, transactions = 2)
        }

    // --------------------------------------------------------------- guard

    /** Guard: a Shielded chat's Tunnel switch with the relays down asks first and pays our KEX on confirm, as today. */
    @Test
    fun `guard - a Tunnel switch with the relays down is asked and pays the key exchange as today`() =
        runBlocking {
            val chat = Chat(ConversationMode.VAULT, keysBothWays = false, relayKey = false)
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

    // --------------------------------------------------------------- harness

    private fun pick(
        vm: ChatViewModel,
        mode: ConversationMode,
    ) = vm.switchConversationMode(PEER, mode)

    private fun assertNoQuestion(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertFalse(
            "ZC-1131: an Open switch asked Pay and continue for transactions Open never pays: $state",
            state is SendMessageState.NeedsPaidActionConfirmation,
        )
        assertFalse("an Open switch left a question on screen: $state", state.javaClass.simpleName.startsWith("Needs"))
        // The not-synced notice (relays down) is a notice after the mode was saved, not a refusal.
        assertFalse(
            "an Open switch was refused: $state",
            state is SendMessageState.Error && state != SendMessageState.Error(ChatSendCopy.MODE_RELAY_UNREACHABLE),
        )
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
     * A chat with [PEER] in [initialMode] (as TunnelSwitchWaitsForAnUpdatedContactTest.Chat); the contact's newest relay
     * rumor carried the Ironwood tag (ZChat 2.13), so ZC-1107 never refuses the Tunnel switch here.
     */
    private class Chat(
        initialMode: ConversationMode,
        keysBothWays: Boolean,
        relayKey: Boolean,
    ) {
        val prefs = AutoSpendPrefs(consent = true).apply { session(PEER, complete = keysBothWays, mode = initialMode) }
        val seam = HandshakeSeam(PEER)
        val zbootQuoted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val pendingModeSyncs = ConcurrentHashMap<String, String>()
        private val relayKeys = ConcurrentHashMap<String, String>().apply { if (relayKey) put(PEER, PEER_RELAY_KEY) }
        private val withKeys = keysBothWays

        val mode: ConversationMode get() = prefs.modes[PEER] ?: ConversationMode.VAULT

        private val store: ZchatPreferences =
            withHandlers(
                prefs.proxy,
                mapOf(
                    "hasAcknowledgedMessageCost" to { _ -> true },
                    "setConversationMode" to { a ->
                        (a!![1] as ConversationMode?)?.let { prefs.modes[a[0] as String] = it } ?: prefs.modes.remove(a[0] as String)
                        null
                    },
                    "getConversationModeOrNull" to { a -> prefs.modes[a!![0] as String] },
                    "getPeerNostrPubkey" to { a -> relayKeys[a!![0] as String] },
                    "getPeerIronwoodCap" to { a -> if (a!![0] == PEER) ZchatPreferences.PeerIronwoodCap(true, RUMOR_AT_SEC) else null },
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
private fun withHandlers(
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
