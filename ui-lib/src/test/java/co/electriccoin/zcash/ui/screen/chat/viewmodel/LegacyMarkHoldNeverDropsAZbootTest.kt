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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * W-72 (register ZC-847), independent review of 3426ade7d finding 2 (P2), the rule ZC-1070 (c) gave the claim mark on
 * train/T7a (22f724d35): when the pre-update mark goes between the guard's check and its hold (the user's "Mark as verified"
 * or the answer to our probe, on another thread), the hold is refused and the ZBOOT must not be lost: it is read on as an
 * unmarked contact's ZBOOT and binds by the ordinary rules; a mark that still stands keeps it held.
 *
 * [ClaimMarkMaps.legacyMarkGoesAtHold] removes the mark at the hold (as the tap would) and refuses the hold. Today the ZBOOT
 * is dropped: nothing binds and the call reports nothing established. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyMarkHoldNeverDropsAZbootTest {
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
    fun `a ZBOOT whose mark went between the check and the hold binds by the ordinary rules`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 72501), firstContact = true)
            val contact = E2EEncryption.generateKeyPair()
            chat.prefs.e2eEnabled += chat.peer
            chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
            chat.prefs.e2ePeerKey[chat.peer] = contact.publicKey
            chat.maps.legacy[chat.peer] = contact.publicKey
            chat.maps.modes[chat.peer] = ConversationMode.TUNNEL
            chat.maps.legacyMarkGoesAtHold = true
            val vm = chat.viewModel()

            val established = chat.route(vm, v3Boot(contact, relayKey(95)))
            awaitIdle(vm)

            assertEquals("the ZBOOT was dropped when its mark went before the hold", relayKey(95), chat.maps.nostr[chat.peer])
            assertTrue("the ZBOOT read on reported nothing established", established)
        }

    @Test
    fun `control - a mark that still stands keeps the ZBOOT held`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 72502), firstContact = true)
            val contact = E2EEncryption.generateKeyPair()
            chat.prefs.e2eEnabled += chat.peer
            chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
            chat.prefs.e2ePeerKey[chat.peer] = contact.publicKey
            chat.maps.legacy[chat.peer] = contact.publicKey
            chat.maps.modes[chat.peer] = ConversationMode.TUNNEL
            val vm = chat.viewModel()
            val raw = v3Boot(contact, relayKey(96))

            chat.route(vm, raw)
            awaitIdle(vm)

            assertTrue("a standing mark let the ZBOOT bind", chat.maps.binds.isEmpty())
            assertEquals(raw, chat.maps.legacyHeld[chat.peer])
        }
}
