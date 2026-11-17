package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.NostrInboxManager
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M1 (fix-all 2.13.0, train T4b; money SPEC §1.1 item 5, §1.2, §1.3 step 6, §5 R1-5 and R1-6): a relay-only chat is
 * not Shielded by default.
 *
 * At HEAD, observeNostrInbound stores a relay text from a contact whose relay key we hold and never writes a mode. A
 * chat with no stored mode is Shielded (ConversationMode.DEFAULT = VAULT), so when A has pasted B's code without
 * sending (B's key is bound, no mode written) and B then sends A a free first message, A's chat with B is Shielded and
 * A's reply is quoted 34,000 zatoshi (KEX + message) for a contact who is free over the relays. The phantom chat of the
 * Fold finding is the same case.
 *
 * Wanted: when the sender's chat has no stored mode AND no E2E keys either way (nothing ever went on-chain), the relay
 * text sets it to Open, without the explicit pin, so a later ZMODE from the contact can still be adopted. The composer
 * shows no cost for an Open chat (ChatDetailView's cost row shows only for Shielded, or Tunnel before the handshake).
 * The control cases are RelayMessageToShieldedPeerKeepsModeTest. Real ChatViewModel inbound reader over
 * [RelayKeyStore]; the message comes through NostrChatBridge.dispatch. All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayMessageToUnsetModePeerOpensFreeChatTest {
    private val sendScopes = SendHarnessScopes()
    private val tag = "synthetic-m1-open-${UUID.randomUUID()}"
    private val peer = SyntheticUnifiedAddress.mainnet(4_501)
    private val key = relayKey(51)

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NostrChatBridge.unregisterChatNotifier()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a relay text from a scanned-first contact with no mode and no E2E keys opens a free Open chat`() =
        runBlocking {
            val store = RelayKeyStore().apply { pubkeys[peer] = key }
            val vm = RelayKeyChatScreen.chatViewModel(store)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")

            NostrChatBridge.dispatch(
                NostrInboxManager.InboundDm(
                    senderPubkeyHex = key,
                    content = "synthetic first free message",
                    createdAtSec = System.currentTimeMillis() / 1000,
                    eventId = "$tag-1",
                    rumorId = "rumor-$tag-1",
                ),
                store.proxy,
            )
            withTimeoutOrNull(5_000) { while (store.rows.isEmpty()) delay(20) }

            val row = assertNotNull(store.rows["nmsg-rumor-$tag-1"], "the relay text was not stored")
            assertEquals(peer, row.peerAddress)
            assertEquals(ConversationMode.OPEN, store.modes[peer], "the relay-only chat stays Shielded (no stored mode)")
            assertFalse(peer in store.explicitModes, "the Open default was pinned, so a later ZMODE could not be adopted")
            val mode = store.proxy.getConversationMode(peer)
            assertTrue(
                !mode.isShieldedOnlyTransport && !mode.needsBootstrap,
                "the composer shows a cost for this chat (mode $mode)",
            )
        }
}
