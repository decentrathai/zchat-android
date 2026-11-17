package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.relayKey
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
 * ZC-634 (review of part B, finding 2; design 5.5 item 3): while a claim mark stands, a ZBOOT signed with the claimed key
 * that announces the relay key already bound at that address passes the mark check at d1c127cb2 and is then "handled as
 * today": it writes its own relay URL (which our publishes to that contact then use), raises the stored epoch (which then
 * refuses the real contact's own later rotations as stale) and clears the key-changed flag (the one signal left when the
 * real contact's answer arrives).
 *
 * Wanted: such a ZBOOT only flushes our waiting messages to the key that is already bound; the relay URL, the epoch and
 * the key-changed flag stay as they were. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimedKeyBootOfTheBoundKeyOnlyFlushesTest {
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
    fun `a claimed key's ZBOOT announcing the bound relay key changes no relay URL, no epoch and no key-changed flag`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 63490), firstContact = true)
            val claimed = E2EEncryption.generateKeyPair()
            chat.prefs.e2eEnabled += chat.peer
            chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
            chat.prefs.e2ePeerKey[chat.peer] = claimed.publicKey
            chat.maps.claims[chat.peer] = claimed.publicKey
            chat.maps.modes[chat.peer] = ConversationMode.OPEN
            val bound = relayKey(83)
            chat.maps.nostr[chat.peer] = bound
            chat.maps.relayUrls[chat.peer] = ClaimMarkChat.RELAY
            chat.maps.epochs[chat.peer] = 1L
            chat.maps.keyChanged += chat.peer
            val vm = chat.viewModel()

            val signed = ZBootMessage.signedDataFor(CONV, bound, STRANGER_RELAY, 9L)
            val raw =
                ZBootMessage(CONV, bound, STRANGER_RELAY, E2EEncryption.sign(claimed.privateKey, signed), epoch = 9L, version = 3)
                    .serialize()
            chat.route(vm, raw)

            assertEquals("the claimed key's ZBOOT replaced the contact's relay URL", ClaimMarkChat.RELAY, chat.maps.relayUrls[chat.peer])
            assertEquals("the claimed key's ZBOOT raised the stored epoch", 1L, chat.maps.epochs[chat.peer])
            assertTrue("the claimed key's ZBOOT cleared the key-changed flag", chat.peer in chat.maps.keyChanged)
            assertEquals("the bound relay key changed", bound, chat.maps.nostr[chat.peer])
        }

    private companion object {
        const val CONV = "ZC634BF1"
        const val STRANGER_RELAY = "wss://relay.synthetic-stranger.example"
    }
}
