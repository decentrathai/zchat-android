package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.RatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 8 D5 (DECISIONS_NIGHT §2 / M-NOS-21) — "Reset encryption" pays without asking.
 *
 * ChatViewModel.resetSecureSession wipes the contact's ratchet, our E2E keys, the Quantum Shield PSK and the
 * handshake markers, then forces a paid on-chain KEX (ensureNostrBootstrapSent(force = true)), and our ZBOOT follows
 * once the contact's new key lands: up to 2 transactions, at least 2 x 17,000 zatoshi, with no price on screen. The
 * confirm dialog before it says nothing about cost.
 *
 * Wanted:
 *  - the whole reset, key wipe included, waits behind the paid-action question (2 on-chain transactions, at least
 *    34,000 zatoshi), also when the automatic-handshake consent was given;
 *  - Cancel wipes nothing and pays nothing;
 *  - Confirm wipes and pays exactly the KEX (our ZBOOT waits for the contact's new key), once;
 *  - the same in an Open chat whose contact has no relay key (its KEX goes on-chain);
 *  - an Open chat whose contact's relay key is known pays nothing for a reset (no on-chain KEX in Open, #224): it
 *    is not asked, and the reset runs as before (control);
 *  - with a Keystone account selected the KEX can never be paid (ZC-102): nothing is wiped, nothing is sent, and
 *    the Keystone explanation is shown.
 *
 * The new API (PaidAction.RESET_ENCRYPTION) is read by name, so this file compiles before it exists. Every address
 * and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResetEncryptionAsksFirstTest {
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
    fun `with handshake consent given Reset asks first, quoting 2 transactions, and sends or wipes nothing`() =
        runBlocking {
            val f = ResetFixture("usyntheticd5resetvault0001", ConversationMode.VAULT)
            val vm = f.viewModel()

            vm.resetSecureSession(f.peer)
            awaitIdle(vm)

            f.assertAskedFirst(vm)
            f.assertNothingWiped("before the user answers")
        }

    @Test
    fun `Cancel leaves the keys, the markers, the Quantum Shield key and the ratchet untouched`() =
        runBlocking {
            val f = ResetFixture("usyntheticd5resetcancel01", ConversationMode.VAULT)
            val vm = f.viewModel()

            vm.resetSecureSession(f.peer)
            awaitIdle(vm)
            vm.dismissPaidAction()
            awaitIdle(vm)

            f.assertNothingWiped("after Cancel")
            assertEquals("Cancel must pay nothing", 0, f.seam.proposalRequests.get())
            assertEquals("Cancel must pay nothing", 0, f.seam.submits.get())
            assertEquals(SendMessageState.Idle, HandshakeHarness.sendMessageState(vm))
        }

    @Test
    fun `Confirm wipes the session and sends at most one KEX and one ZBOOT`() =
        runBlocking {
            val f = ResetFixture("usyntheticd5resetconfirm1", ConversationMode.VAULT)
            val vm = f.viewModel()

            vm.resetSecureSession(f.peer)
            awaitIdle(vm)
            f.assertAskedFirst(vm)
            confirm(vm)
            awaitIdle(vm)
            confirm(vm) // a second tap on the same question pays nothing more
            awaitIdle(vm)

            f.assertWiped()
            assertEquals("Confirm pays the KEX now; our ZBOOT waits for the contact's new key", 1, f.seam.proposalRequests.get())
            assertTrue("at most one KEX and one ZBOOT", f.seam.submits.get() in 1..2)
        }

    // T7BH-N2 (W-57): an Open chat's Reset pays nothing (AnOpenChatNeverSpendsZecTest); a Tunnel chat's asks first.
    @Test
    fun `in a Tunnel chat whose contact has no relay key Reset asks first as well`() =
        runBlocking {
            val f = ResetFixture("usyntheticd5resetopen0001", ConversationMode.TUNNEL)
            val vm = f.viewModel()

            vm.resetSecureSession(f.peer)
            awaitIdle(vm)

            f.assertAskedFirst(vm)
            f.assertNothingWiped("before the user answers")
            confirm(vm)
            awaitIdle(vm)
            f.assertWiped()
            assertEquals("the Tunnel chat's KEX goes on-chain (no relay key)", 1, f.seam.proposalRequests.get())
        }

    /** Control (passes before and after): nothing paid follows, so nothing is asked and the reset runs as before. */
    @Test
    fun `control - in an Open chat whose contact's relay key is known Reset pays nothing and is not asked`() =
        runBlocking {
            val f = ResetFixture("usyntheticd5resetopen0002", ConversationMode.OPEN).apply { peerRelayKey = RELAY_KEY }
            val vm = f.viewModel()

            vm.resetSecureSession(f.peer)
            awaitIdle(vm)

            val state = HandshakeHarness.sendMessageState(vm)
            assertFalse("a reset that pays nothing must not ask: $state", state.javaClass.simpleName == "NeedsPaidActionConfirmation")
            f.assertWiped()
            assertEquals("an Open chat never pays a KEX (#224)", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `with a Keystone account selected nothing is wiped or sent and the Keystone text is shown`() =
        runBlocking {
            val accounts = SelectedAccountFeed.create().apply { keystoneSelected() }
            val f = ResetFixture("usyntheticd5resetkeyst001", ConversationMode.VAULT)
            val vm = f.viewModel(accounts)

            vm.resetSecureSession(f.peer)
            awaitIdle(vm)
            // Whatever is on screen, a confirm must not start the reset either.
            confirm(vm)
            awaitIdle(vm)

            f.assertNothingWiped("with Keystone selected")
            assertEquals("nothing may be submitted with Keystone selected", 0, f.seam.submits.get())
            assertEquals("nothing may be built with Keystone selected", 0, f.seam.proposalRequests.get())
            assertEquals(SendMessageState.Error(ChatSendCopy.KEYSTONE_AUTOMATIC_SETUP), HandshakeHarness.sendMessageState(vm))
        }

    private fun confirm(vm: ChatViewModel) {
        ChatViewModel::class.java.getMethod("confirmPaidAction").invoke(vm)
    }

    private companion object {
        const val RELAY_KEY = "7e5f0c4d2a1b9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f"
    }
}

/**
 * One contact [peer] in [mode] with a finished key exchange, a Quantum Shield key, handshake markers, a live ratchet
 * processor and a stored ratchet state; the message-cost notice and the automatic-handshake consent were given.
 * Every call that a reset wipes with is recorded in [wipes]. Lenient: any other call gets the type's empty value
 * (a Boolean write reports success).
 */
internal class ResetFixture(
    val peer: String,
    @Volatile var mode: ConversationMode,
) {
    val seam = HandshakeSeam(peer)

    @Volatile var peerRelayKey: String? = null

    private val ourKeys = ConcurrentHashMap<String, E2EKeyPair>()
    private val peerKeys = ConcurrentHashMap<String, String>()
    private val psk = ConcurrentHashMap<String, String>()
    private val ownBootSent = ConcurrentHashMap<String, Boolean>()
    private val sentNostrBoot = ConcurrentHashMap<String, String>()
    private val sentKexAck = ConcurrentHashMap<String, String>()
    private val originalOurKey: E2EKeyPair = E2EEncryption.generateKeyPair()
    private val originalPeerKey: String = E2EEncryption.generateKeyPair().publicKey
    val wipes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val ratchetDeletes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val processors = ConcurrentHashMap<String, E2EMessageProcessor>()

    init {
        ourKeys[peer] = originalOurKey
        peerKeys[peer] = originalPeerKey
        psk[peer] = "synthetic-quantum-shield-psk"
        ownBootSent[peer] = true
        sentNostrBoot[peer] = "synthetic-sent-nostr-boot-pubkey"
        sentKexAck[peer] = "synthetic-sent-kexack-pubkey"
    }

    fun assertAskedFirst(vm: ChatViewModel) {
        assertEquals("Reset paid on-chain with no question on screen", 0, seam.proposalRequests.get())
        assertEquals("Reset paid on-chain with no question on screen", 0, seam.submits.get())
        val state = HandshakeHarness.sendMessageState(vm)
        assertEquals(
            "Reset must ask with the on-chain count and the total first, was $state",
            "NeedsPaidActionConfirmation",
            state.javaClass.simpleName,
        )
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        val action = confirmation.javaClass.getMethod("getAction").invoke(confirmation)
        val count = confirmation.javaClass.getMethod("getOnChainMessages").invoke(confirmation) as Int
        val total = confirmation.javaClass.getMethod("getTotalCost").invoke(confirmation)
        val totalZat = total.javaClass.getMethod("getValue").invoke(total) as Long
        assertEquals("the question names the reset", "RESET_ENCRYPTION", (action as Enum<*>).name)
        assertEquals("the KEX and then our ZBOOT", 2, count)
        assertEquals("2 x 17,000 zatoshi", 34_000L, totalZat)
    }

    fun assertNothingWiped(whenText: String) {
        assertEquals("nothing may be wiped $whenText", emptyList<String>(), wipes.toList())
        assertEquals("our keys must stay $whenText", originalOurKey, ourKeys[peer])
        assertEquals("the contact's key must stay $whenText", originalPeerKey, peerKeys[peer])
        assertNotNull("the Quantum Shield key must stay $whenText", psk[peer])
        assertEquals("the bootstrap marker must stay $whenText", true, ownBootSent[peer])
        assertNotNull("the ZBOOT marker must stay $whenText", sentNostrBoot[peer])
        assertNotNull("the KEXACK marker must stay $whenText", sentKexAck[peer])
        assertEquals("no ratchet state may be deleted $whenText", emptyList<String>(), ratchetDeletes.toList())
        assertTrue("the live ratchet must stay $whenText", processors.containsKey("$peer:$CONV_ID"))
    }

    fun assertWiped() {
        assertTrue("the reset must wipe the E2E keys: $wipes", "clearE2EKeys" in wipes)
        assertTrue("the reset must wipe the Quantum Shield key: $wipes", "clearQuantumShieldPSK" in wipes)
        assertTrue("the reset must delete the ratchet state", CONV_ID in ratchetDeletes)
        assertFalse("the reset must drop the live ratchet", processors.containsKey("$peer:$CONV_ID"))
    }

    suspend fun viewModel(accounts: SelectedAccountFeed? = null): ChatViewModel {
        val selected = accounts ?: SelectedAccountFeed.create()
        val vm = chatViewModelWith(proxy, seam, accountSource = selected).also(AutoSpendVm::wireBootstrapFields)
        processors["$peer:$CONV_ID"] = E2EMessageProcessor(ByteArray(32) { 5 }, CONV_ID, true, InMemoryRatchetStateStore())
        AutoSpendVm.setField(vm, "messageProcessors", processors)
        AutoSpendVm.setField(vm, "ratchetStateStore", recordingRatchetStore())
        AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        return vm
    }

    private fun recordingRatchetStore(): RatchetStateStore {
        val inner = InMemoryRatchetStateStore()
        return Proxy.newProxyInstance(
            RatchetStateStore::class.java.classLoader,
            arrayOf(RatchetStateStore::class.java),
        ) { p, method, args ->
            when (method.name) {
                "delete" -> {
                    ratchetDeletes += args!![0] as String
                    Unit
                }
                "toString" -> "RecordingRatchetStore"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> if (args == null) method.invoke(inner) else method.invoke(inner, *args)
            }
        } as RatchetStateStore
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]

            fun forPeer() = args?.getOrNull(0) == peer
            when (method.name) {
                "hasAcknowledgedMessageCost" -> true
                "hasConsentedToHandshakeSpend" -> true
                "getConversationMode", "getConversationModeOrNull" -> mode
                "getPeerNostrPubkey" -> if (forPeer()) peerRelayKey else null
                "getE2EOurPublicKey" -> ourKeys[arg(0) as String]?.publicKey
                "getE2EPrivateKey" -> ourKeys[arg(0) as String]?.privateKey
                "setE2EOurKeys" -> {
                    ourKeys[arg(0) as String] = E2EKeyPair(arg(1) as String, arg(2) as String)
                    null
                }
                "getE2EPeerPublicKey" -> peerKeys[arg(0) as String]
                "isE2EKeyExchangeComplete" -> ourKeys.containsKey(arg(0) as String) && peerKeys.containsKey(arg(0) as String)
                "isE2EEnabled" -> true
                "clearE2EKeys" -> {
                    if (forPeer()) wipes += method.name
                    ourKeys.remove(arg(0) as String)
                    peerKeys.remove(arg(0) as String)
                    null
                }
                "clearQuantumShieldPSK" -> {
                    if (forPeer()) wipes += method.name
                    psk.remove(arg(0) as String)
                    null
                }
                "setPeerBootEpoch" -> {
                    if (forPeer()) wipes += method.name
                    null
                }
                "isOwnBootSent" -> ownBootSent[arg(0) as String] ?: false
                "setOwnBootSent" -> {
                    if (forPeer() && arg(1) == false) wipes += "${method.name}(false)"
                    ownBootSent[arg(0) as String] = arg(1) as Boolean
                    true
                }
                "getSentNostrBootPubkey" -> sentNostrBoot[arg(0) as String]
                "setSentNostrBootPubkey" -> {
                    val value = arg(1) as String?
                    if (forPeer() && value == null) wipes += "${method.name}(null)"
                    if (value == null) sentNostrBoot.remove(arg(0) as String) else sentNostrBoot[arg(0) as String] = value
                    true
                }
                "getSentKexAckPubkey" -> sentKexAck[arg(0) as String]
                "setSentKexAckPubkey" -> {
                    val value = arg(1) as String?
                    if (forPeer() && value == null) wipes += "${method.name}(null)"
                    if (value == null) sentKexAck.remove(arg(0) as String) else sentKexAck[arg(0) as String] = value
                    true
                }
                "getOrCreateConversationId", "getOrCreateConversationIdForMessage" -> CONV_ID to false
                // FC-ID (ZC-892): our KEX is built only under an id this phone made for the address; this fixture's id is that id.
                "ownConversationId", "ownConversationIdForStart" -> CONV_ID
                "getConversationId" -> if (forPeer()) CONV_ID else null
                "getAllConversationMappings" -> mapOf(CONV_ID to peer)
                "getPeerByConversationId" -> if (arg(0) == CONV_ID) peer else null
                "getNostrRotationIndex" -> 0
                "resolvePeerAddress" -> arg(0)
                "toString" -> "ResetFixture"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> {
                    val isWrite =
                        method.name.startsWith("set") || method.name.startsWith("put") || method.name.startsWith("mark")
                    // A working store: a Boolean write reports that it reached the disk.
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
        const val CONV_ID = "RSET2345"
    }
}
