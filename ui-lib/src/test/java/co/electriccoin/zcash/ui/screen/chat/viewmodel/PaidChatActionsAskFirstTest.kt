package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * R2-2 (b), plan step p2-2546b8c8a490 — chat actions that pay on-chain without saying so.
 *
 * The one-time "messages cost ZEC" acknowledgement is given (and, for the handshake, the one-time handshake
 * consent). At b1f45ab32 each of these taps then pays at once, with nothing on screen that says a
 * transaction is paid or what it costs:
 *
 * - setting the disappearing-messages timer in a Shielded chat (one on-chain control message);
 * - switching a Shielded chat to Tunnel while the contact is not reachable over Nostr (one message);
 * - reacting to a message in a Shielded chat (one message);
 * - dismissing "security key changed" in a Tunnel chat (a paid KEX, then our paid ZBOOT);
 * - the first message to a new contact in a Shielded chat (a paid KEX before the message itself);
 * - a status broadcast (one message per Shielded contact).
 *
 * Wanted: each such action shows a confirmation carrying the number of on-chain messages and the total
 * (at least 17,000 zatoshi per message: carrier + platform fee + the network fee of a one-input send) and
 * pays nothing until the user confirms; confirming pays what was quoted. The confirmation API is reached by
 * name so this file compiles before it exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaidChatActionsAskFirstTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `setting the disappearing timer in a Shielded chat asks before paying`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticpaidactiontimer01", ConversationMode.VAULT)
            val vm = f.viewModel()

            vm.setDisappearingTtl(f.peer, 3_600L)
            awaitIdle(vm)

            f.assertAskedFirst(vm, messages = 1)
            confirm(vm)
            awaitIdle(vm)
            assertEquals("confirming pays the one control message", 1, f.seam.proposalRequests.get())
        }

    @Test
    fun `switching a Shielded chat with no relay channel asks before paying`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticpaidactionmode001", ConversationMode.VAULT)
            val vm = f.viewModel()

            vm.changeConversationMode(f.peer, ConversationMode.TUNNEL)
            awaitIdle(vm)

            f.assertAskedFirst(vm, messages = 1)
            assertEquals("nothing changes before the user confirms", ConversationMode.VAULT, f.mode)
            confirm(vm)
            awaitIdle(vm)
            assertEquals(1, f.seam.proposalRequests.get())
            assertEquals(ConversationMode.TUNNEL, f.mode)
        }

    @Test
    fun `a reaction in a Shielded chat asks before paying`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticpaidactionreact01", ConversationMode.VAULT)
            val vm = f.viewModel()

            vm.sendReaction(f.peer, "synthetic-target-message", "👍")
            awaitIdle(vm)

            f.assertAskedFirst(vm, messages = 1)
            confirm(vm)
            awaitIdle(vm)
            assertEquals(1, f.seam.proposalRequests.get())
        }

    @Test
    fun `dismissing a key change in a Tunnel chat asks before the paid re-KEX`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticpaidactionkeychg1", ConversationMode.TUNNEL).apply { keyChanged = true }
            val vm = f.viewModel()

            vm.dismissE2EKeyChanged(f.peer)
            awaitIdle(vm)

            // A KEX now and our ZBOOT once the reply lands.
            f.assertAskedFirst(vm, messages = 2)
            assertTrue("the banner stays until the user decides", f.keyChanged)
            confirm(vm)
            awaitIdle(vm)
            assertEquals("confirming pays the KEX", 1, f.seam.proposalRequests.get())
            assertEquals(false, f.keyChanged)
        }

    @Test
    fun `the first message to a new contact in a Shielded chat asks before paying the KEX and the message`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticpaidactionfirst01", ConversationMode.VAULT, haveOurKey = false)
            val vm = f.viewModel()

            vm.sendMessage(f.peer, "hello")
            awaitIdle(vm)

            // Our KEX, the one-memo message and (Round 10b R10B-1) our ZBOOT after the contact's KEXACK: the contact has no
            // relay key, so it goes on-chain.
            f.assertAskedFirst(vm, messages = 3)
            confirm(vm)
            awaitIdle(vm)
            assertEquals("confirming pays the KEX and the message", 2, f.seam.proposalRequests.get())
        }

    /** Control (passes before and after): a message to an existing contact is not asked again. */
    @Test
    fun `control - a message to a contact we already have keys for is sent at once`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticpaidactionctrl001", ConversationMode.VAULT)
            val vm = f.viewModel()

            vm.sendMessage(f.peer, "hello")
            awaitIdle(vm)

            assertEquals(1, f.seam.proposalRequests.get())
        }

    private fun confirm(vm: ChatViewModel) {
        ChatViewModel::class.java.getMethod("confirmPaidAction").invoke(vm)
    }
}

/**
 * One contact [peer] in [initialMode]: we hold keys with them (unless [haveOurKey] is false), they have no
 * Nostr key, the message-cost notice and the handshake consent were accepted. A Shielded chat has its
 * ratchet processor ready. Lenient: any other call gets the type's empty value and is recorded.
 */
internal class PaidChatFixture(
    val peer: String,
    initialMode: ConversationMode,
    haveOurKey: Boolean = true,
) {
    val seam = HandshakeSeam(peer)

    @Volatile var mode: ConversationMode = initialMode

    @Volatile var keyChanged: Boolean = false

    private val ourKeys = ConcurrentHashMap<String, E2EKeyPair>()
    private val peerKeys = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap<String, ZchatPreferences.PendingMessageData>()
    val unexpected: MutableList<String> = Collections.synchronizedList(mutableListOf())

    init {
        if (haveOurKey) {
            ourKeys[peer] = E2EEncryption.generateKeyPair()
            peerKeys[peer] = E2EEncryption.generateKeyPair().publicKey
        }
        // SR-A (fix-all 2.13.0): the exact price of a one-transaction send is its outputs plus a 15,000 network fee, and
        // the proposal the send then builds carries that same fee.
        seam.feeProbe = { FeeProbe.Built(Zatoshi(15_000L), usesOrchardInputs = false, transactionCount = 1) }
        seam.proposalFee = 15_000L
    }

    fun assertAskedFirst(
        vm: ChatViewModel,
        messages: Int
    ) {
        assertEquals(
            "the action paid on-chain with no confirmation on screen",
            0,
            seam.proposalRequests.get(),
        )
        val state = HandshakeHarness.sendMessageState(vm)
        assertEquals(
            "the user must be asked with the on-chain message count and the total, was $state",
            "NeedsPaidActionConfirmation",
            state.javaClass.simpleName,
        )
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        val count = confirmation.javaClass.getMethod("getOnChainMessages").invoke(confirmation) as Int
        val total = confirmation.javaClass.getMethod("getTotalCost").invoke(confirmation)
        val totalZat = total.javaClass.getMethod("getValue").invoke(total) as Long
        assertEquals("on-chain messages quoted", messages, count)
        assertTrue("total quoted ($totalZat) below $messages x 17,000 zatoshi", totalZat >= messages * 17_000L)
    }

    suspend fun viewModel(): ChatViewModel {
        val vm = chatViewModelWith(proxy, seam).also(AutoSpendVm::wireBootstrapFields)
        val processors = ConcurrentHashMap<String, E2EMessageProcessor>()
        processors["$peer:$CONV_ID"] = E2EMessageProcessor(ByteArray(32) { 7 }, CONV_ID, true, InMemoryRatchetStateStore())
        AutoSpendVm.setField(vm, "messageProcessors", processors)
        AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        AutoSpendVm.setField(vm, "pendingMessages", kotlinx.coroutines.flow.MutableStateFlow(emptyList<ChatMessage>()))
        AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
        AutoSpendVm.setField(vm, "_chatListState", kotlinx.coroutines.flow.MutableStateFlow<ChatListState>(ChatListState.Loading))
        AutoSpendVm.setField(vm, "_showCostDisclaimer", kotlinx.coroutines.flow.MutableStateFlow(false))
        AutoSpendVm.setField(vm, "_blockHeight", kotlinx.coroutines.flow.MutableStateFlow<Long?>(null))
        return vm
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]
            when (method.name) {
                "hasAcknowledgedMessageCost" -> true
                "hasConsentedToHandshakeSpend" -> true
                "getConversationMode" -> mode
                "getConversationModeOrNull" -> mode
                "setConversationMode" -> {
                    mode = arg(1) as ConversationMode
                    null
                }
                "setDisappearingTtl" -> true
                "isE2EKeyChanged" -> keyChanged
                "setE2EKeyChanged" -> {
                    keyChanged = arg(1) as Boolean
                    null
                }
                "getE2EOurPublicKey" -> ourKeys[arg(0) as String]?.publicKey
                "getE2EPrivateKey" -> ourKeys[arg(0) as String]?.privateKey
                "setE2EOurKeys" -> {
                    ourKeys[arg(0) as String] = E2EKeyPair(arg(1) as String, arg(2) as String)
                    null
                }
                "getE2EPeerPublicKey" -> peerKeys[arg(0) as String]
                // A first message to a new contact goes out before encryption is on (it is readable until
                // the KEXACK arrives), so E2E is on only where we already hold keys.
                "isE2EEnabled" -> ourKeys.containsKey(arg(0) as String) && peerKeys.containsKey(arg(0) as String)
                "getOrCreateConversationId" -> CONV_ID to false
                // R2-6 (c): the per-message call (an existing conversation whose INIT went out).
                "getOrCreateConversationIdForMessage" -> CONV_ID to false
                // FC-ID (ZC-892): our KEX is built only under an id this phone made for the address; this fixture's id is that id.
                "ownConversationId", "ownConversationIdForStart" -> CONV_ID
                // B2: the first-message prompt asks the store whether the INIT is still owed; a contact with no
                // keys is a new contact (the first-message tests), a keyed one has had our INIT.
                "needsConversationInit" -> !haveOurKey
                "getPeerByConversationId" -> if (arg(0) == CONV_ID) peer else null
                "getPendingMessages" -> pending.values.toList()
                "addPendingMessage", "commitPendingMessage" -> {
                    val row = arg(0) as ZchatPreferences.PendingMessageData
                    pending[row.id] = row
                    null
                }
                "removePendingMessage" -> {
                    pending.remove(arg(0) as String)
                    null
                }
                "getNostrRotationIndex" -> 0
                "resolvePeerAddress" -> arg(0)
                "toString" -> "PaidChatFixture"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> {
                    val isWrite =
                        method.name.startsWith("set") || method.name.startsWith("put") || method.name.startsWith("mark")
                    if (!isWrite) unexpected += method.name
                    // R4-1: a store write that reports a Boolean reports whether it reached the disk, and this
                    // fixture stands in for a working store, so its writes succeed. Reading the lenient `false`
                    // there would model a store that silently loses every write.
                    if (isWrite && method.returnType == java.lang.Boolean.TYPE) true else emptyValueFor(method.returnType)
                }
            }
        } as ZchatPreferences

    private fun emptyValueFor(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            List::class.java -> emptyList<Any>()
            Set::class.java -> emptySet<Any>()
            Map::class.java -> emptyMap<Any, Any>()
            else -> null
        }

    companion object {
        const val CONV_ID = "PAID2345"
    }
}
