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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1 (fix-all 2.13.0, train T4b; money SPEC §1.2 control, §5 R1-5): the relay-only Open default never moves a chat
 * with on-chain history or a mode the user or the contact chose.
 *
 * A chat that received an on-chain first message has E2E keys and usually no stored mode (it is Shielded by default);
 * moving it to Open on its first relay text would send its next messages over the relays instead of on-chain. So: a
 * chat with an E2E key (ours or the contact's) and no stored mode stays unset (Shielded); a chat whose mode is Shielded
 * or Tunnel keeps it. Green before and after the fix. All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayMessageToShieldedPeerKeepsModeTest {
    private val sendScopes = SendHarnessScopes()
    private val tag = "synthetic-m1-keep-${UUID.randomUUID()}"

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

    private suspend fun deliverText(
        store: RelayKeyStore,
        key: String,
        n: Int,
    ) {
        val vm = RelayKeyChatScreen.chatViewModel(store)
        RelayKeyChatScreen.start(vm, "observeNostrInbound")
        NostrChatBridge.dispatch(
            NostrInboxManager.InboundDm(
                senderPubkeyHex = key,
                content = "synthetic relay text $n",
                createdAtSec = System.currentTimeMillis() / 1000,
                eventId = "$tag-$n",
                rumorId = "rumor-$tag-$n",
            ),
            store.proxy,
        )
        withTimeoutOrNull(5_000) { while (store.rows.isEmpty()) delay(20) }
        assertNotNull(store.rows["nmsg-rumor-$tag-$n"], "the relay text was not stored")
    }

    @Test
    fun `a chat with our E2E key and no stored mode stays Shielded`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(4_601)
            val store =
                RelayKeyStore().apply {
                    pubkeys[peer] = relayKey(61)
                    e2eOurKeys[peer] = "synthetic-our-e2e-public"
                }
            deliverText(store, relayKey(61), 1)
            assertNull(store.modes[peer], "a chat with on-chain history was moved off Shielded")
            assertFalse(store.writes.any { it.startsWith("setConversationMode:$peer") }, "a mode was written: ${store.writes}")
        }

    @Test
    fun `a chat with the contact's E2E key and no stored mode stays Shielded`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(4_602)
            val store =
                RelayKeyStore().apply {
                    pubkeys[peer] = relayKey(62)
                    e2ePeerKeys[peer] = "synthetic-peer-e2e-public"
                }
            deliverText(store, relayKey(62), 2)
            assertNull(store.modes[peer], "a chat with on-chain history was moved off Shielded")
        }

    @Test
    fun `an explicit Shielded chat keeps its mode`() =
        runBlocking {
            val vault = SyntheticUnifiedAddress.mainnet(4_603)
            val store =
                RelayKeyStore().apply {
                    pubkeys[vault] = relayKey(63)
                    modes[vault] = ConversationMode.VAULT
                    explicitModes += vault
                }
            deliverText(store, relayKey(63), 3)
            assertEquals(ConversationMode.VAULT, store.modes[vault])
            assertTrue(vault in store.explicitModes)
        }

    @Test
    fun `a Tunnel chat keeps its mode`() =
        runBlocking {
            val tunnel = SyntheticUnifiedAddress.mainnet(4_604)
            val store2 =
                RelayKeyStore().apply {
                    pubkeys[tunnel] = relayKey(64)
                    modes[tunnel] = ConversationMode.TUNNEL
                }
            deliverText(store2, relayKey(64), 4)
            assertEquals(ConversationMode.TUNNEL, store2.modes[tunnel])
        }
}
