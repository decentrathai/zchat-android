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
 * ZC-1365 reopened (phone round 4 on c39a0f61c, TIMELINE4 12:55-12:57): the chat screen wrote a "waiting for a secure
 * connection" row (disk + process-wide tunnelPendingPayloads); Delete chat ran on the chat LIST's ChatViewModel, a second
 * instance whose pendingMessages were loaded before that row existed, so its drawn conversation held only the older
 * history. hideChat hid the history and cancelled only the unsent rows it drew (d7128f68e): the stored row and its queued
 * payload stayed, and after a force-stop the chat came back with that row still Sending, queued for the relay.
 *
 * Must hold: Delete chat removes every unsent row of the chat from the store and from the queue, whether or not this
 * instance drew it; after a process restart the loader queues nothing, the chat shows no row, a flush publishes nothing
 * and no block or contact answer pays for it. Synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletedChatRemovesWaitingRowTheListNeverDrewTest {
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

    private class Phone(
        val world: OneDecisionWorld,
        val list: ChatViewModel,
        val screen: ChatViewModel,
    )

    /**
     * The list screen is up first (it loaded the store when nothing was waiting); the chat screen then writes and queues
     * the first Tunnel message. The list's last pass drew the chat from its history only.
     */
    private suspend fun phoneWithWaitingRowOnlyTheChatScreenSaw(): Phone {
        val world = OneDecisionWorld()
        world.memory.answers["getQuotedChatSetupPeers"] = setOf(world.peer)
        world.memory.consent = true
        world.chat.fixture.mode = ConversationMode.TUNNEL
        val list = world.chatScreen()
        PaymentHarness.loadPendingMessagesFromPrefs(list)
        val screen = world.chatScreen()
        world.store.addPendingMessage(
            ZchatPreferences.PendingMessageData(
                id = TUNNEL_ROW,
                text = OneDecisionWorld.FIRST_WORDS,
                timestampMillis = System.currentTimeMillis(),
                peerAddress = world.peer,
                isOutgoing = true,
                isPending = true,
                status = MessageStatus.SENDING.name,
                submitTracked = true,
            ),
        )
        PaymentHarness.loadPendingMessagesFromPrefs(screen)
        val queued = PaymentHarness.eventually(SETTLE_MS) { tunnelQueue().isNotEmpty() }
        assertTrue("fixture: the chat screen did not queue the row's payload", queued)
        assertTrue("fixture: the list drew the waiting row", pendingRows(list).none { it.id == TUNNEL_ROW })
        listDrewHistoryOnly(list, world.peer)
        return Phone(world, list, screen)
    }

    @Test
    fun `deleting the chat from the list removes the waiting row it never drew, and a restart brings nothing back`() =
        runBlocking {
            val phone = phoneWithWaitingRowOnlyTheChatScreenSaw()
            val world = phone.world

            phone.list.hideChat(world.peer)

            assertNull("Delete chat left the waiting row on disk: a restart shows it and queues it again", storedRow(world))
            assertTrue("Delete chat left the waiting row's payload queued for the relay", tunnelQueue().isEmpty())

            flush(phone.screen, world.peer)
            assertEquals("a flush after Delete chat published the deleted message", emptyList<String>(), published(world))

            // Force-stop and reopen: the process-wide queue and marks are gone, the store stays.
            AutoSpendVm.resetProcessState()
            AutoSpendVm.setHistoryRead(true)
            clearTunnelQueue()
            val restarted = world.chatScreen()
            PaymentHarness.loadPendingMessagesFromPrefs(restarted)
            PaymentHarness.eventually(SHORT_MS) { tunnelQueue().isNotEmpty() }
            assertTrue("after a restart the deleted message was queued for the relay again", tunnelQueue().isEmpty())
            val hidden = world.store.getHiddenMessageIds()
            val shown = pendingRows(restarted).filter { it.peerAddress == world.peer && it.id !in hidden }.map { it.id }
            assertEquals("after a restart the deleted chat shows its waiting row again", emptyList<String>(), shown)
            flush(restarted, world.peer)
            assertEquals("a flush after a restart published the deleted message", emptyList<String>(), published(world))
        }

    @Test
    fun `deleting the chat from the list pays nothing for the waiting row, before or after a restart`() =
        runBlocking {
            val phone = phoneWithWaitingRowOnlyTheChatScreenSaw()
            val world = phone.world

            phone.list.hideChat(world.peer)

            AutoSpendVm.resetProcessState()
            AutoSpendVm.setHistoryRead(true)
            clearTunnelQueue()
            val restarted = world.chatScreen()
            PaymentHarness.loadPendingMessagesFromPrefs(restarted)
            world.fundsConfirmed()
            repeat(BLOCKS) {
                world.funds.block(restarted)
                world.funds.paymentsSettled()
            }
            assertEquals("a block after a restart paid for the deleted chat's waiting message", 0, world.submits())
            world.contactAnswered()
            world.funds.block(restarted)
            world.openChat(restarted)
            world.funds.paymentsSettled()
            assertEquals("the contact's answer paid for the deleted chat's waiting message", 0, world.submits())
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

    @Suppress("UNCHECKED_CAST")
    private fun pendingRows(vm: ChatViewModel): List<ChatMessage> =
        (
            ChatViewModel::class.java
                .getDeclaredField("pendingMessages")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<List<ChatMessage>>
        ).value

    private fun storedRow(world: OneDecisionWorld): ZchatPreferences.PendingMessageData? =
        world.store.getPendingMessages().firstOrNull { it.id == TUNNEL_ROW }

    private fun published(world: OneDecisionWorld): List<String> =
        world.store
            .getPendingMessages()
            .filter { it.peerAddress == world.peer && it.id.startsWith("nostr-out-") }
            .map { it.id }

    private fun flush(
        vm: ChatViewModel,
        peer: String,
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod("flushTunnelPendingPayloads", String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, peer, PEER_RELAY_KEY)
    }

    /** The list's last pass: the chat with an earlier message from the contact; the waiting row was never in its input. */
    @Suppress("UNCHECKED_CAST")
    private fun listDrewHistoryOnly(
        vm: ChatViewModel,
        peer: String,
    ) {
        val earlier =
            ChatMessage(
                id = "synthetic-inbound-1365-r4",
                txId = null,
                text = "synthetic earlier words from the contact",
                timestamp = Instant.now().minusSeconds(EARLIER_S),
                isOutgoing = false,
                peerAddress = peer,
            )
        val state =
            ChatViewModel::class.java
                .getDeclaredField("_chatListState")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<ChatListState>
        state.value =
            ChatListState.Success(
                conversations = listOf(Conversation(peerAddress = peer, messages = listOf(earlier), lastMessage = earlier)),
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
        const val TUNNEL_ROW = "tunnel-wait-synthetic-1365-r4"
        const val PEER_RELAY_KEY = "synthetic-relay-key-1365-r4"
    }
}
