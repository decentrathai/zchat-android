package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.UpgradeReminders
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
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1465 (OT213n B1b; FIX_PLAN REVISION 1): the "pending request, only one message reaches them" hint in an Open chat
 * shows only while there is no evidence that the contact accepted.
 *
 * At 669cf19e1 UpgradeReminders.showsChatRequestHint(mode, messages) is "Open and no inbound relay message". In the
 * owner's case HONOR scanned the Fold's code (both sides hold each other's key, every message arrives) and only the
 * Fold's read receipts and mode changes came back, never a text row, so the hint stayed and its claim was false.
 *
 * Wanted:
 * - the predicate takes a heardFromPeer flag (a Boolean parameter): with it set the hint is off even with no inbound
 *   row; without it the hint keeps today's rule (control);
 * - the relay reader records that flag for the sender of any authenticated relay rumor, a read receipt included (a
 *   store call whose name contains "HeardFrom", first argument the contact's address).
 * The new overload and store method are found by name, so this compiles before they exist. All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRequestHintNeedsNoEvidenceOfAcceptanceTest {
    private val sendScopes = SendHarnessScopes()
    private val tag = "synthetic-zc1465-${UUID.randomUUID()}"
    private val peer = SyntheticUnifiedAddress.mainnet(14_651)
    private val key = relayKey(1_465)

    private val ours =
        ChatMessage(
            id = "nmsg-ours-1",
            txId = null,
            text = "synthetic words we sent",
            timestamp = Instant.parse("2026-11-01T10:00:00Z"),
            isOutgoing = true,
            peerAddress = peer,
            status = MessageStatus.SENT,
        )

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

    /** showsChatRequestHint with a heardFromPeer Boolean, arguments placed by type; fails when no such overload exists. */
    private fun hint(
        mode: ConversationMode,
        messages: List<ChatMessage>,
        heardFromPeer: Boolean,
    ): Boolean {
        val m =
            UpgradeReminders::class.java.methods.firstOrNull { m ->
                m.name == "showsChatRequestHint" && m.parameterTypes.any { it == java.lang.Boolean.TYPE }
            } ?: fail("UpgradeReminders.showsChatRequestHint takes no heardFromPeer flag: the hint ignores evidence of acceptance")
        val args =
            m.parameterTypes
                .map {
                    when (it) {
                        ConversationMode::class.java -> mode
                        List::class.java -> messages
                        java.lang.Boolean.TYPE -> heardFromPeer
                        else -> fail("unexpected parameter ${it.simpleName}")
                    }
                }.toTypedArray()
        return try {
            m.invoke(UpgradeReminders, *args) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `an Open chat whose contact was heard from over the relays shows no request hint`() {
        assertEquals(false, hint(ConversationMode.OPEN, listOf(ours), heardFromPeer = true), "the hint claims a pending request")
        assertEquals(true, hint(ConversationMode.OPEN, listOf(ours), heardFromPeer = false), "control: no evidence keeps the hint")
        assertEquals(false, hint(ConversationMode.TUNNEL, listOf(ours), heardFromPeer = false), "control: only Open shows it")
    }

    @Test
    fun `a read receipt from the bound relay key records that the contact was heard from`() =
        runBlocking {
            val store =
                RelayKeyStore().apply {
                    pubkeys[peer] = key
                    modes[peer] = ConversationMode.OPEN
                }
            val prefs = CallLogPrefs(store.proxy)
            val vm = B1bRelay.chatViewModel(prefs.proxy)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")

            NostrChatBridge.dispatch(
                B1bRelay.dm(key, ZMSGProtocol.createReadReceipt("nmsg-ours-1", peer), "$tag-rr"),
                prefs.proxy,
            )
            B1bRelay.awaitTrue { "$tag-rr" in store.seen }
            B1bRelay.awaitTrue { prefs.named(Regex("HeardFrom")).isNotEmpty() }

            assertTrue(
                prefs.named(Regex("HeardFrom")).any { it.second.firstOrNull() == peer },
                "a read receipt from the contact left no heard-from mark (calls: ${prefs.names()})",
            )
            assertEquals(emptyList(), RelayKeyChatScreen.rows(vm), "control: the read receipt is not a row")
        }
}
