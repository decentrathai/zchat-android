package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1480 item 2 (OT213n B1b; FIX_PLAN REVISION 1, Fable M7 moved it here; L2 root cause 2): a relay INIT from a bound
 * contact files its conversation id under that contact.
 *
 * At 669cf19e1 observeNostrInbound unwraps a known peer's v4 INIT (parseV4Init(...).second) for the text and drops its
 * conversation id. HONOR's first Shielded message after an Open start is then a v4 REPLY carrying only that id and a
 * hash; the Fold's history pass finds no `conv:<id>` mapping and no on-chain row for the contact, keys the row by the
 * hash and shows a second chat, "Unknown sender".
 *
 * Wanted: the INIT's id is mapped to the SEAL-AUTHENTICATED sender (chat.peerAddress, the address bound to the relay
 * key), never to the address the INIT body claims, through setConversationMapping (which keeps the id written once,
 * ZC-892). Real ChatViewModel relay reader over [RelayKeyStore] via NostrChatBridge.dispatch. All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayInitFromBoundPeerMapsItsConversationIdTest {
    private val sendScopes = SendHarnessScopes()
    private val tag = "synthetic-zc1480-${UUID.randomUUID()}"
    private val peer = SyntheticUnifiedAddress.mainnet(14_801)
    private val claimed = SyntheticUnifiedAddress.mainnet(14_802)
    private val key = relayKey(1_480)

    private companion object {
        const val CONV = "ZINIT480"
    }

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

    private suspend fun deliver(
        init: String,
        id: String,
    ): CallLogPrefs {
        val store =
            RelayKeyStore().apply {
                pubkeys[peer] = key
                modes[peer] = ConversationMode.OPEN
            }
        val prefs = CallLogPrefs(store.proxy)
        val vm = B1bRelay.chatViewModel(prefs.proxy)
        RelayKeyChatScreen.start(vm, "observeNostrInbound")
        NostrChatBridge.dispatch(B1bRelay.dm(key, init, id), prefs.proxy)
        B1bRelay.awaitTrue { store.rows.isNotEmpty() }
        assertNotNull(store.rows["nmsg-rumor-$id"], "fixture: the INIT's text was not stored as a row")
        return prefs
    }

    @Test
    fun `a relay INIT from the bound contact maps its conversation id to that contact`() =
        runBlocking {
            val prefs = deliver(ZMSGProtocol.createV4InitMessage(CONV, peer, "synthetic first free words"), "$tag-1")

            val mapped = prefs.named(Regex("^setConversationMapping$")).map { it.second }
            assertTrue(
                listOf(CONV, peer) in mapped,
                "the INIT's conversation id was not mapped to the contact (mappings written: ${mapped.size})",
            )
        }

    @Test
    fun `the id goes to the sealed sender, never to the address the INIT body claims`() =
        runBlocking {
            val prefs = deliver(ZMSGProtocol.createV4InitMessage(CONV, claimed, "synthetic words claiming another address"), "$tag-2")

            val mapped = prefs.named(Regex("^setConversationMapping$")).map { it.second }
            assertEquals(emptyList(), mapped.filter { claimed in it }, "the id was filed under the unauthenticated claimed address")
            assertTrue(listOf(CONV, peer) in mapped, "the id was not mapped to the relay key's bound contact")
        }
}
