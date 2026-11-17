package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.kexFrom
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.relayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v3Boot
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
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

/**
 * ZC-634 (review of part B, finding 1, P1; design 5.5 item 2 b, replay (c) A5): this phone started a Tunnel chat with X
 * (our key pair made, or E2E on with the key pair not made yet; no relay key bound); a stranger's key exchange claims X.
 * The W-62 hold stops the paid answer, but at d1c127cb2 no claim mark is written beside it, so after the user's Accept
 * tap the stranger's ZBOOT, read again, binds his relay key and our queued Tunnel message goes to him.
 *
 * Wanted: the mark is written under case (b) whether or not the W-62 hold is set; after Accept it still stands, the
 * ZBOOT binds nothing, is held, and the queued message waits (X's own answer under our id, leg 1, releases it: RED ah).
 * Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimMarkOutlivesTheAcceptTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
        ClaimMarkChat.clearTunnelQueue()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        ClaimMarkChat.clearTunnelQueue()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `(m) past Accept, our key pair made - the claimed key keeps its mark and its ZBOOT binds nothing`() =
        afterAccept(seed = 63480, ourKeyPair = true)

    @Test
    fun `(m) past Accept, E2E on before our key pair - the claimed key keeps its mark and its ZBOOT binds nothing`() =
        afterAccept(seed = 63481, ourKeyPair = false)

    private fun afterAccept(
        seed: Int,
        ourKeyPair: Boolean,
    ) = runBlocking {
        val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = seed), firstContact = true)
        chat.prefs.e2eEnabled += chat.peer
        if (ourKeyPair) chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
        chat.maps.modes[chat.peer] = ConversationMode.TUNNEL
        chat.queueTunnelMessage("synthetic first tunnel message")
        val vm = chat.viewModel()
        val stranger = E2EEncryption.generateKeyPair()

        handleKexMemo(vm, kexFrom(chat.peer, stranger, "ZC634MA$seed".take(8)), "synthetic-zc634-ma-$seed")
        awaitIdle(vm)
        assertEquals("fixture: the W-62 hold", stranger.publicKey, chat.prefs.awaitingAccept[chat.peer])
        assertEquals("no claim mark beside the W-62 hold (5.5 item 2 b)", stranger.publicKey, chat.maps.claims[chat.peer])

        vm.acceptInboundKexRequest(chat.peer)
        awaitIdle(vm)
        val raw = v3Boot(stranger, relayKey(82))
        chat.route(vm, raw)
        awaitIdle(vm)

        assertTrue("after Accept the claimed key's ZBOOT bound a relay key: ${chat.maps.binds.size}", chat.maps.binds.isEmpty())
        assertTrue("after Accept the queued Tunnel message left the queue", chat.tunnelMessageWaits())
        assertEquals("after Accept the ZBOOT is not held", raw, chat.maps.held[chat.peer])
    }
}
