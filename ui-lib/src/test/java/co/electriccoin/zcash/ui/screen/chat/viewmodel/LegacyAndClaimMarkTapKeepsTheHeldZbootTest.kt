package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.relayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v3Boot
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * W-72, independent review of 3426ade7d finding 6 (P3): a key can carry the pre-update mark and a ZC-634 claim mark at
 * once. "Mark as verified" removes both; the ZBOOT the pre-update mark held back must be handed on (with the relays-only
 * reply) when the claim held none, as the comment in markE2EVerified says. Today it is dropped. Every address, key and id is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyAndClaimMarkTapKeepsTheHeldZbootTest {
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
    fun `Mark as verified with both marks hands on the ZBOOT the pre-update mark held`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 72601), firstContact = true)
            val contact = E2EEncryption.generateKeyPair()
            chat.prefs.e2eEnabled += chat.peer
            chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
            chat.prefs.e2ePeerKey[chat.peer] = contact.publicKey
            chat.maps.legacy[chat.peer] = contact.publicKey
            chat.maps.legacyHeld[chat.peer] = v3Boot(contact, relayKey(97))
            chat.maps.claims[chat.peer] = contact.publicKey
            chat.maps.modes[chat.peer] = ConversationMode.TUNNEL
            val vm = chat.viewModel()

            vm.markE2EVerified(chat.peer)
            awaitIdle(vm)

            assertNull("the tap left the pre-update mark", chat.maps.legacy[chat.peer])
            assertNull("the tap left the claim mark", chat.maps.claims[chat.peer])
            assertEquals("the ZBOOT the pre-update mark held was dropped", relayKey(97), chat.maps.nostr[chat.peer])
            assertEquals("the tap paid", 0, chat.proposals())
        }
}
