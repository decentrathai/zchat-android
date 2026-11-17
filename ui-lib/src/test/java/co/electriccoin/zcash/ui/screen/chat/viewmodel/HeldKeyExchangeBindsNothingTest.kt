package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.kexWithRelayFields
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.relayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v3Boot
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * The sibling review's finding (T7bG on T7aF: "a key exchange held for Accept still stores its key and binds its relay
 * key"), checked on train/T7a after ZC-634 parts A and B: a stranger's first-contact key exchange, mined, carrying relay
 * fields, read by the real history pass, waits for Accept and binds nothing; a ZBOOT signed with its key binds nothing
 * either, on-chain (attributeBoot) or unsealed over the relays, and pays nothing; after Accept the same ZBOOT, read
 * again, binds. The key itself is stored while it waits (the Accept banner and its one answer need it): that is the
 * design's hold (FIRST-CONTACT-SPEC 5.5 item 1), not a binding. A guard: green at the ZC-634 part B tip. Every address,
 * key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeldKeyExchangeBindsNothingTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
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

    @Test
    fun `a held first-contact key exchange and its key's ZBOOT bind no relay key before Accept, and the ZBOOT binds after it`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 63470)
            val f = HandshakePassFixture(scheduler, peer)
            val maps = ClaimMarkMaps(f.prefs.e2ePeerKey, f.prefs.askedKey)
            f.extra = { method, args -> maps.handle(method, args) }
            f.open()
            val stranger = E2EEncryption.generateKeyPair()
            f.received("held-kex", kexWithRelayFields(peer, stranger, "ZHLD0001", relayKey(80)), minedAt = NEW + 10)
            f.pass()

            assertEquals("fixture: the key exchange waits for Accept", stranger.publicKey, f.prefs.awaitingAccept[peer])
            assertTrue("the held key exchange's relay fields were bound: ${maps.binds.size}", maps.binds.isEmpty())

            val raw = v3Boot(stranger, relayKey(81), convId = "ZHLD0001")
            boot(f, "attributeBoot", raw)
            boot(f, "routeIncomingBoot", raw, peer)
            f.idle()
            assertTrue("a ZBOOT signed with the held key bound a relay key: ${maps.binds.size}", maps.binds.isEmpty())
            assertTrue("a refused ZBOOT was recorded as processed", maps.bootSigs.isEmpty())
            assertEquals("something was paid before Accept", 0, f.seam.proposalRequests.get())

            f.vm.acceptInboundKexRequest(peer)
            f.idle()
            boot(f, "attributeBoot", raw)
            assertEquals("after Accept the same ZBOOT, read again, must bind", relayKey(81), maps.nostr[peer])
        }

    private fun boot(
        f: HandshakePassFixture,
        name: String,
        raw: String,
        peer: String? = null,
    ) {
        val method =
            if (peer == null) {
                ChatViewModel::class.java.getDeclaredMethod(name, String::class.java)
            } else {
                ChatViewModel::class.java.declaredMethods
                    .first { it.name == name && it.parameterCount == 3 && it.parameterTypes[2] == java.lang.Boolean.TYPE }
            }
        method.isAccessible = true
        try {
            if (peer == null) method.invoke(f.vm, raw) else method.invoke(f.vm, peer, raw, false)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
