package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PoolType
import co.electriccoin.zcash.ui.screen.chat.model.PrivacyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.Instant

/**
 * ZC-1365 (money-path rule 2): Delete chat on a chat that shows other rows (here an earlier message from the contact) only
 * hides its "waiting for secure connection" row. The row's payload stays in the process-wide tunnelPendingPayloads and the
 * row stays on disk, so the contact's relay key arriving later (flushTunnelPendingPayloads) publishes the deleted message,
 * and a restart queues it again; the ZBOOT that delivers our relay identity pays for it.
 *
 * Must hold: deleting a chat cancels every unsent row's payload (queue and disk); no later flush, restart, block or contact
 * answer publishes it or pays for it. ZC-1352 pinned the chat holding nothing else (WithdrawnSetupMarkTest). Synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletedChatCancelsEveryUnsentPayloadTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        clearTunnelQueue()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
        clearTunnelQueue()
    }

    private fun tunnelQueue(): MutableMap<*, *> = staticField("tunnelPendingPayloads") as MutableMap<*, *>

    private fun clearTunnelQueue() {
        tunnelQueue().clear()
        (staticField("tunnelQueueRehydrated") as MutableCollection<*>).clear()
    }

    private fun staticField(name: String): Any? {
        val field = ChatViewModel::class.java.declaredFields.first { it.name == name && Modifier.isStatic(it.modifiers) }
        field.isAccessible = true
        return field.get(null)
    }

    /** A Tunnel chat with an earlier message from the contact and our first reply waiting for a secure connection. */
    private suspend fun chatWithHistoryAndWaitingRow(): Pair<OneDecisionWorld, ChatViewModel> {
        val world = OneDecisionWorld()
        world.memory.answers["getQuotedChatSetupPeers"] = setOf(world.peer)
        world.memory.consent = true
        world.chat.fixture.mode = ConversationMode.TUNNEL
        val vm = world.chatScreen()
        world.store.addPendingMessage(
            ZchatPreferences.PendingMessageData(
                id = TUNNEL_ROW,
                text = OneDecisionWorld.FIRST_WORDS,
                timestampMillis = System.currentTimeMillis(),
                peerAddress = world.peer,
                status = MessageStatus.SENDING.name,
                submitTracked = true,
            ),
        )
        PaymentHarness.loadPendingMessagesFromPrefs(vm)
        val queued = PaymentHarness.eventually(SETTLE_MS) { tunnelQueue().isNotEmpty() }
        assertTrue("fixture: the loader did not queue the row's payload", queued)
        showChatWithHistory(vm, world.peer)
        return world to vm
    }

    private fun storedRow(world: OneDecisionWorld): ZchatPreferences.PendingMessageData? =
        world.store.getPendingMessages().firstOrNull { it.id == TUNNEL_ROW }

    private fun published(world: OneDecisionWorld): List<String> =
        world.store
            .getPendingMessages()
            .filter { it.peerAddress == world.peer && it.id.startsWith("nostr-out-") }
            .map { it.id }

    @Test
    fun `deleting a chat with other rows cancels the waiting row's payload, and no flush or restart publishes it`() =
        runBlocking {
            val (world, vm) = chatWithHistoryAndWaitingRow()

            vm.hideChat(world.peer)

            assertTrue("Delete chat left the waiting row's payload queued for the relay", tunnelQueue().isEmpty())
            assertNull("Delete chat left the waiting row on disk: a restart queues it again", storedRow(world))

            // The contact's relay key arrives (an inbound ZBOOT, or one piggybacked on a KEX): the queue is flushed.
            flush(vm, world.peer)
            assertEquals("a flush after Delete chat published the deleted message", emptyList<String>(), published(world))

            // The process ends and starts again: the loader finds nothing to queue.
            AutoSpendVm.resetProcessState()
            AutoSpendVm.setHistoryRead(true)
            clearRehydratedMarks()
            val restarted = world.chatScreen()
            PaymentHarness.loadPendingMessagesFromPrefs(restarted)
            PaymentHarness.eventually(SHORT_MS) { tunnelQueue().isNotEmpty() }
            assertTrue("after a restart the deleted message was queued for the relay again", tunnelQueue().isEmpty())
            flush(restarted, world.peer)
            assertEquals("a flush after a restart published the deleted message", emptyList<String>(), published(world))
        }

    @Test
    fun `deleting a chat with other rows pays nothing for its waiting row`() =
        runBlocking {
            val (world, vm) = chatWithHistoryAndWaitingRow()

            vm.hideChat(world.peer)

            world.fundsConfirmed()
            repeat(BLOCKS) {
                world.funds.block(vm)
                world.funds.paymentsSettled()
            }
            assertEquals("a block paid for the deleted chat's waiting message", 0, world.submits())
            world.contactAnswered()
            world.funds.block(vm)
            world.openChat(vm)
            world.funds.paymentsSettled()
            assertEquals("the contact's answer paid for the deleted chat's waiting message", 0, world.submits())
        }

    private fun clearRehydratedMarks() {
        (staticField("tunnelQueueRehydrated") as MutableCollection<*>).clear()
    }

    private fun flush(
        vm: ChatViewModel,
        peer: String,
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod("flushTunnelPendingPayloads", String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, peer, PEER_RELAY_KEY)
    }

    /** The list's last pass drew the chat: an earlier message from the contact, then the waiting row. */
    @Suppress("UNCHECKED_CAST")
    private fun showChatWithHistory(
        vm: ChatViewModel,
        peer: String,
    ) {
        val earlier =
            ChatMessage(
                id = "synthetic-inbound-1365",
                txId = null,
                text = "synthetic earlier words from the contact",
                timestamp = Instant.now().minusSeconds(EARLIER_S),
                isOutgoing = false,
                peerAddress = peer,
            )
        val waiting =
            ChatMessage(
                id = TUNNEL_ROW,
                txId = null,
                text = OneDecisionWorld.FIRST_WORDS,
                timestamp = Instant.now(),
                isOutgoing = true,
                peerAddress = peer,
                isPending = true,
                status = MessageStatus.SENDING,
            )
        val rows = listOf(earlier, waiting)
        val state =
            ChatViewModel::class.java
                .getDeclaredField("_chatListState")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<ChatListState>
        state.value =
            ChatListState.Success(
                conversations = listOf(Conversation(peerAddress = peer, messages = rows, lastMessage = rows.last())),
                currentUserAddress = HandshakeHarness.SELF,
                privacyStatus =
                    PrivacyStatus(
                        poolType = PoolType.ORCHARD,
                        orchardBalance = Zatoshi(100_000),
                        ironwoodBalance = Zatoshi(0),
                    ),
            )
    }

    private companion object {
        const val BLOCKS = 3
        const val SETTLE_MS = 5_000L
        const val SHORT_MS = 500L
        const val EARLIER_S = 3_600L
        const val TUNNEL_ROW = "tunnel-wait-synthetic-1365"
        const val PEER_RELAY_KEY = "synthetic-relay-key-1365"
    }
}
