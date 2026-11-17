package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.B1cPlanned
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1474 as a feature (OT213n B1c; owner W-123; FIX_PLAN REVISION 2): the receiver of a `ZATT|v1|…` rumor.
 *
 * At 669cf19e1 the relay reader does not know ZATT: a ZATT from the bound relay key of an Open chat falls through to
 * the text branch and is shown, and stored, as a raw text bubble with the file key in it; the on-chain reader shows a
 * ZATT inside a ZMSG the same way.
 *
 * Wanted:
 * - from the seal-authenticated bound relay key of the chat (NostrChatBridge.dispatch mapped the sender): one file
 *   bubble naming the ZATT's blob (fileHash, fileType), whose text is not the raw wire text and carries no file key;
 * - guard: from a relay key bound to no contact: nothing (no bubble, no stored row, no request holding the key);
 * - an on-chain memo carrying a ZATT (anyone who knows the address can send one; no seal authenticates it): no bubble
 *   at all, neither a file bubble nor the raw text. A plain on-chain text in the same pass still shows (control).
 * Real relay reader (observeNostrInbound via NostrChatBridge.dispatch) over [RelayKeyStore], real history pass
 * (convertToConversations) over [HandshakePassFixture]. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZattAcceptedOnlyFromTheBoundRelayKeyTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val tag = "synthetic-b1c-${UUID.randomUUID()}"
    private val peer = SyntheticUnifiedAddress.mainnet(14_743)
    private val key = relayKey(1_743)
    private val strangerKey = relayKey(1_744)

    private companion object {
        const val CONV = "ZCHN1474"
        const val TEXT = "synthetic plain words on chain"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterChatNotifier()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun boundOpenChat(): RelayKeyStore =
        RelayKeyStore().apply {
            pubkeys[peer] = key
            modes[peer] = ConversationMode.OPEN
        }

    private fun ChatMessage.showsWire(): Boolean = text.contains("ZATT|") || text.contains(B1cPlanned.FILE_KEY_B64)

    @Test
    fun `a ZATT from the bound relay key becomes one file bubble, never raw text`() =
        runBlocking {
            val store = boundOpenChat()
            val prefs = CallLogPrefs(store.proxy)
            val vm = B1bRelay.chatViewModel(prefs.proxy)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")

            NostrChatBridge.dispatch(B1bRelay.dm(key, B1cPlanned.wire(), "$tag-1"), prefs.proxy)
            B1bRelay.awaitTrue { "$tag-1" in store.seen }
            B1bRelay.awaitTrue { RelayKeyChatScreen.rows(vm).any { it.peerAddress == peer } }

            val rows = RelayKeyChatScreen.rows(vm).filter { it.peerAddress == peer }
            assertEquals(1, rows.size, "the ZATT from the bound key made ${rows.size} bubbles")
            val row = rows.single()
            assertFalse(row.showsWire(), "the ZATT is shown as raw text with its file key (no ZATT reader)")
            assertEquals(B1cPlanned.HASH, row.fileHash, "the ZATT did not become a file bubble for its blob")
            assertEquals(ZFILEType.JPEG, row.fileType)
            assertTrue(row.authenticated, "a sealed relay file bubble is not marked authenticated")
            assertTrue(
                store.rows.values.none { it.text.contains("ZATT|") || it.text.contains(B1cPlanned.FILE_KEY_B64) },
                "the raw ZATT text was stored as a row's text",
            )
        }

    @Test
    fun `guard - a ZATT from a relay key bound to no contact shows and stores nothing`() =
        runBlocking {
            val store = boundOpenChat()
            val prefs = CallLogPrefs(store.proxy)
            val vm = B1bRelay.chatViewModel(prefs.proxy)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")

            NostrChatBridge.dispatch(B1bRelay.dm(strangerKey, B1cPlanned.wire(), "$tag-2"), prefs.proxy)
            B1bRelay.awaitTrue(ms = 500) { RelayKeyChatScreen.rows(vm).isNotEmpty() }

            assertEquals(0, RelayKeyChatScreen.rows(vm).size, "a ZATT from an unbound key made a bubble")
            assertEquals(0, store.rows.size, "a ZATT from an unbound key was stored")
            val held =
                prefs.calls.filter { (name, args) ->
                    Regex("(?i)request").containsMatchIn(name) && args.any { it.toString().contains("ZATT|") }
                }
            assertEquals(emptyList(), held.map { it.first }, "a ZATT from an unbound key was kept as a chat request")
        }

    @Test
    fun `a ZATT in an on-chain memo shows no bubble, while a plain on-chain text still does`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer)
            f.open()
            f.received("onchain-text", ZMSGProtocol.createV4InitMessage(CONV, peer, TEXT), minedAt = NEW + 10)
            f.received("onchain-zatt", ZMSGProtocol.createV4InitMessage(CONV, peer, B1cPlanned.wire()), minedAt = NEW + 11)
            f.pass()

            val messages = f.rows().flatMap { it.messages }
            assertEquals(1, messages.count { it.text == TEXT }, "control: the plain on-chain text is not shown once")
            // Counts only: the bubbles' ids are (synthetic) txids.
            assertEquals(0, messages.count { it.showsWire() }, "an unauthenticated on-chain ZATT is shown as a bubble with its file key")
            assertEquals(
                0,
                messages.count { it.fileHash == B1cPlanned.HASH || it.fileZfileContent?.contains("ZATT|") == true },
                "an unauthenticated on-chain ZATT became a file bubble (it would be downloaded)",
            )
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun HandshakePassFixture.rows(): List<Conversation> {
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "convertToConversations" && it.parameterCount == 7 }
                .apply { isAccessible = true }
        val txs =
            (
                HandshakePassFixture::class.java
                    .getDeclaredField("txs")
                    .apply { isAccessible = true }
                    .get(this) as List<Transaction>
            ).sortedBy { it.timestamp ?: Instant.MIN }
        val listed =
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                try {
                    val pending = PaymentHarness.pendingMessages(vm)
                    method.invoke(vm, txs, HandshakePassFixture.SELF, emptySet<String>(), pending, emptySet<String>(), true, continuation)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as? List<Conversation> ?: fail("the history pass suspended")
        idle()
        return listed
    }
}
