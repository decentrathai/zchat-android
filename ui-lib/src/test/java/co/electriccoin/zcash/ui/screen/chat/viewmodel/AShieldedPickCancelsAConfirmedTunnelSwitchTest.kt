package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
 * ZC-777 (review of 12 Oct, finding 2) — a Shielded pick made while a confirmed switch to Tunnel is still planning
 * cancels that switch.
 *
 * Pay on the mode question starts the switch again; it reads its plan, and the plan waits for the wallet (our relay
 * identity). At 26b976bef the pick is checked only before that wait. A Shielded pick made meanwhile changes nothing
 * visible (the chat is still Shielded), and the earlier run then sets and pins Tunnel, pays the on-chain mode change
 * and writes and pays a ZBOOT allowance: up to 34,000 zat, inside the price the user accepted, but for a choice they
 * had already replaced. Wanted: the run that was replaced stops after its plan: no mode change, no submit, no allowance.
 *
 * The plan is parked with the seam of TimerOrModeChangeIsRefusedWhileANewKeyWaitsTest (a wallet provider that waits).
 * Money: proposals are counted against submits on the real use case and repository; the control pays exactly the two
 * transactions the question quoted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AShieldedPickCancelsAConfirmedTunnelSwitchTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ZbootSetup.resetProcess()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        ZbootSetup.resetProcess()
    }

    @Test
    fun `a Shielded pick while the confirmed Tunnel switch is planning leaves the chat Shielded and pays nothing`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()
            val plan = ParkedPlan(vm)
            try {
                vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
                awaitIdle(vm)
                assertAsked(vm, chat)
                plan.park()
                vm.confirmPaidAction()
                plan.awaitParked()

                // The user picks Shielded again; the chat is still Shielded, so this pick itself sends and pays nothing.
                vm.switchConversationMode(PEER, ConversationMode.VAULT)
                assertEquals("fixture sanity: the Shielded pick pays nothing", 0, chat.seam.proposalRequests.get())
            } finally {
                plan.release()
            }
            awaitIdle(vm)

            assertEquals("the switch the user replaced still paid on-chain", 0, chat.seam.proposalRequests.get())
            assertEquals("the switch the user replaced still paid on-chain", 0, chat.seam.submits.get())
            assertEquals("the switch the user replaced still changed the mode", ConversationMode.VAULT, chat.mode)
            assertEquals("the switch the user replaced wrote a ZBOOT allowance", 0, chat.allowanceWrites.get())
            assertFalse("a ZBOOT allowance was left behind", PEER in chat.zbootQuoted)
            assertTrue("a mode change was left to deliver", chat.pendingModeSyncs.isEmpty())
            assertEquals("the chat must be pinned to the user's last pick", listOf(ConversationMode.VAULT), chat.pins.distinct())
            assertTrue("our relay identity must not be marked as sent", chat.prefs.sentNostrBoot[PEER] == null)
            assertFalse(
                "no question may be left on screen",
                HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation,
            )
        }

    @Test
    fun `control - with no later pick the parked confirmed switch changes the mode and pays exactly what it quoted`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()
            val plan = ParkedPlan(vm)
            try {
                vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
                awaitIdle(vm)
                assertAsked(vm, chat)
                plan.park()
                vm.confirmPaidAction()
                plan.awaitParked()
                assertEquals("nothing is paid while the plan waits", 0, chat.seam.proposalRequests.get())
            } finally {
                plan.release()
            }
            awaitIdle(vm)

            assertEquals(ConversationMode.TUNNEL, chat.mode)
            assertEquals("the mode change and our ZBOOT, as quoted", 2, chat.seam.proposalRequests.get())
            assertEquals(2, chat.seam.submits.get())
            assertEquals("the quoted ZBOOT's allowance is written once", 1, chat.allowanceWrites.get())
            assertFalse("the paid ZBOOT must use up its allowance", PEER in chat.zbootQuoted)
        }

    @Test
    fun `control - a Shielded pick after the switch has finished is a new mode change, not a cancel`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
            awaitIdle(vm)
            assertAsked(vm, chat)
            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals("fixture sanity: the confirmed switch paid its two transactions", 2, chat.seam.submits.get())

            vm.switchConversationMode(PEER, ConversationMode.VAULT)
            awaitIdle(vm)
            assertEquals("the later Shielded pick paid on-chain", 2, chat.seam.proposalRequests.get())
            assertEquals(ConversationMode.VAULT, chat.mode)
        }

    /** The mode question is on screen: the on-chain mode change and our ZBOOT, nothing built or changed yet. */
    private fun assertAsked(
        vm: ChatViewModel,
        chat: Chat,
    ) {
        val state =
            HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsPaidActionConfirmation
                ?: throw AssertionError("the switch must ask its price first, was ${HandshakeHarness.sendMessageState(vm)}")
        assertEquals("MODE_CHANGE", state.confirmation.action.name)
        assertEquals("the mode change and our ZBOOT", 2, state.confirmation.onChainMessages)
        assertEquals("nothing is paid before the question", 0, chat.seam.proposalRequests.get())
        assertEquals("nothing changes before the user confirms", ConversationMode.VAULT, chat.mode)
    }

    /** The wallet read inside the mode-switch plan, held open from [park] until [release]. */
    private class ParkedPlan(
        private val vm: ChatViewModel,
    ) {
        private val entered = CompletableDeferred<Unit>()
        private val release = CompletableDeferred<Unit>()

        fun park() {
            AutoSpendVm.setField(
                vm,
                "persistableWalletProvider",
                object : PersistableWalletProvider by FixtureSeedWalletProvider {
                    override suspend fun requirePersistableWallet(): PersistableWallet {
                        entered.complete(Unit)
                        release.await()
                        return FixtureSeedWalletProvider.requirePersistableWallet()
                    }
                },
            )
        }

        suspend fun awaitParked() = withTimeout(HandshakeHarness.TIMEOUT_MS) { entered.await() }

        fun release() {
            release.complete(Unit)
        }
    }

    /**
     * A Shielded chat with [PEER] (the chat of ModeSwitchAsksBeforeEveryPaymentTest): keys both ways and a live ratchet,
     * so the mode change can go on-chain; the contact's relay key unknown and our relay identity never delivered, so
     * the switch also counts our ZBOOT. The mode, its pins, the pending mode deliveries and the ZBOOT allowances are kept.
     */
    private class Chat {
        val prefs = AutoSpendPrefs(consent = false).apply { session(PEER, complete = true, mode = ConversationMode.VAULT) }
        val seam = HandshakeSeam(PEER)
        val zbootQuoted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val allowanceWrites = AtomicInteger(0)
        val pendingModeSyncs = ConcurrentHashMap<String, String>()
        val pins: MutableList<ConversationMode> = Collections.synchronizedList(mutableListOf())

        val mode: ConversationMode get() = prefs.modes[PEER] ?: ConversationMode.VAULT

        private val store: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "hasAcknowledgedMessageCost" -> true
                    "setConversationMode" -> {
                        val newMode = args!![1] as ConversationMode?
                        if (newMode == null) prefs.modes.remove(args[0] as String) else prefs.modes[args[0] as String] = newMode
                        null
                    }
                    "getConversationModeOrNull" -> prefs.modes[args!![0] as String]
                    // The pin is written right after the mode: it records which mode the chat was pinned to.
                    "setConversationModeExplicit" -> {
                        pins += mode
                        null
                    }
                    "isZbootQuoted" -> args!![0] in zbootQuoted
                    "setZbootQuotedAccount" -> true
                    "setZbootQuoted" -> {
                        if (args!![1] as Boolean) {
                            allowanceWrites.incrementAndGet()
                            zbootQuoted += args[0] as String
                        } else {
                            zbootQuoted -= args[0] as String
                        }
                        true
                    }
                    "setPendingModeSync" -> {
                        pendingModeSyncs[args!![0] as String] = args[1] as String
                        null
                    }
                    "clearPendingModeSync" -> {
                        pendingModeSyncs.remove(args!![0] as String)
                        null
                    }
                    else ->
                        try {
                            method.invoke(prefs.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences

        suspend fun viewModel(): ChatViewModel {
            val vm = chatViewModelWith(store, seam).also(AutoSpendVm::wireBootstrapFields)
            val convId = prefs.convIdFor(PEER)
            val processors = ConcurrentHashMap<String, E2EMessageProcessor>()
            processors["$PEER:$convId"] = E2EMessageProcessor(ByteArray(32) { 7 }, convId, true, InMemoryRatchetStateStore())
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

    private companion object {
        const val PEER = "usyntheticzc777shieldedpick01"
    }
}
