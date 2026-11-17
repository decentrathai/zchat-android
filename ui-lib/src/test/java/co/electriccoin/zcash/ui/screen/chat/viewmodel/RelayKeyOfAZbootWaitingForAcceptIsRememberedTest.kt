package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ConversationIdStoreFixture
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.relayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v3Boot
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * ZC-1346: routeIncomingBoot holds a v3 ZBOOT while its key waits for Accept, but fills a slot only where a claim mark stands.
 * With no claim nothing names the relay key, so findPeerByHeldBootNostrPubkey answers null, ZC-1337's keep does not apply and
 * a relay message from that key is dropped as "not an INIT": the sender reads "Sent to the network" and it never arrives.
 *
 * Agreed behaviour, the simplest true one: such a message is kept for redelivery with ZC-1349's bound, as under a claim. The
 * seam is one store slot beside the Accept hold, reached by name so this compiles before it exists:
 * `ZchatPreferences.holdAcceptWaitingBoot(peer, raw): Boolean`, read by findPeerByHeldBootNostrPubkey while the hold stands.
 * Only a ZBOOT that verified fills it. Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayKeyOfAZbootWaitingForAcceptIsRememberedTest {
    private val sendScopes = SendHarnessScopes()
    private val slotWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())

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

    /** A contact key waiting for Accept with no claim mark, over a store that sees the slot's write. */
    private suspend fun waitingForAccept(): Pair<ClaimMarkChat, ChatViewModel> {
        val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 13460), firstContact = true)
        val contact = E2EEncryption.generateKeyPair()
        chat.prefs.e2eEnabled += chat.peer
        chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
        chat.prefs.e2ePeerKey[chat.peer] = contact.publicKey
        chat.prefs.askedKey[chat.peer] = contact.publicKey
        chat.prefs.awaitingAccept[chat.peer] = contact.publicKey
        chat.maps.modes[chat.peer] = ConversationMode.TUNNEL
        val vm = chat.viewModel()
        val seen =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
                if (method.name == SLOT) {
                    slotWrites += args!![1] as String
                    true
                } else {
                    try {
                        method.invoke(chat.store, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            } as ZchatPreferences
        PaymentHarness.setField(vm, "zchatPreferences", seen)
        return chat to vm
    }

    @Test
    fun `a verified ZBOOT held for Accept with no claim fills the slot and binds nothing`() =
        runBlocking {
            val (chat, vm) = waitingForAccept()
            val raw = v3Boot(keysOf(chat), relayKey(46))

            assertFalse("fixture: the ZBOOT was not held back", chat.route(vm, raw))

            assertEquals("the held ZBOOT filled no slot: the relay messages of its key vanish", 1, slotWrites.size)
            assertTrue("the slot holds another ZBOOT", slotWrites.single() == raw)
            assertNull("the key waiting for Accept bound a relay key", chat.maps.nostr[chat.peer])
        }

    @Test
    fun `control - a ZBOOT that does not verify fills no slot`() =
        runBlocking {
            val (chat, vm) = waitingForAccept()
            val forged = v3Boot(E2EEncryption.generateKeyPair(), relayKey(47))

            assertFalse(chat.route(vm, forged))

            assertTrue("a forged ZBOOT named a relay key to keep", slotWrites.isEmpty())
        }

    private lateinit var contactKeys: co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair

    private fun keysOf(chat: ClaimMarkChat): co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair {
        contactKeys = E2EEncryption.generateKeyPair()
        chat.prefs.e2ePeerKey[chat.peer] = contactKeys.publicKey
        chat.prefs.askedKey[chat.peer] = contactKeys.publicKey
        chat.prefs.awaitingAccept[chat.peer] = contactKeys.publicKey
        return contactKeys
    }

    @Test
    fun `the store names the contact while the hold stands, and not after it`() {
        val modes = ConversationIdStoreFixture.RecordingPrefs()
        val keys = ConversationIdStoreFixture.RecordingPrefs()
        val store = realStore(keys, modes)
        val relay = relayKey(48)
        val raw = v3Boot(E2EEncryption.generateKeyPair(), relay)
        store.setInboundKexAwaitingAccept(PEER, HOLD_KEY)

        val method =
            ZchatPreferencesImpl::class.java.methods.firstOrNull { it.name == SLOT && it.parameterCount == 2 }
                ?: throw AssertionError("the store has no slot for a ZBOOT held for Accept with no claim ($SLOT)")
        assertEquals("the slot was not saved", true, method.invoke(store, PEER, raw))

        assertEquals("the slot does not name the contact for its relay key", PEER, store.findPeerByHeldBootNostrPubkey(relay))
        store.setInboundKexAwaitingAccept(PEER, null)
        assertNull("the slot outlived the Accept hold", store.findPeerByHeldBootNostrPubkey(relay))
    }

    private fun realStore(
        keys: ConversationIdStoreFixture.RecordingPrefs,
        modes: ConversationIdStoreFixture.RecordingPrefs,
    ): ZchatPreferencesImpl {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe =
            unsafeClass
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
        val impl =
            unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl

        fun set(
            name: String,
            value: Any,
        ) = ZchatPreferencesImpl::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(impl, value)
        set("e2ePrefs", keys)
        set("convMappingPrefs", ConversationIdStoreFixture.RecordingPrefs())
        set("modePrefs", modes)
        set("_e2eHandshakeTicks", MutableStateFlow(0L))
        return impl
    }

    private companion object {
        const val SLOT = "holdAcceptWaitingBoot"
        const val PEER = "usyntheticzc1346waitingpeer"
        const val HOLD_KEY = "c3ludGhldGljLWhvbGQta2V5"
    }
}
