package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * ZC-1352: the per-peer mark a deleted first message leaves ([ZchatPreferences.isAutomaticSetupWithdrawn]). What sets it, what
 * clears it, what it does not stop, and what a Delete does when the mark cannot be saved. No key or address here is real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WithdrawnSetupMarkTest {
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

    private suspend fun waitingRow(standingConsent: Boolean = true): OneDecisionWorld {
        val world = OneDecisionWorld()
        world.memory.answers["getQuotedChatSetupPeers"] = setOf(world.peer)
        world.memory.consent = standingConsent
        world.fundsStillConfirming()
        val form = world.newChatIn(ConversationMode.TUNNEL)
        world.tap(form)
        world.pay(form)
        assertNotNull("fixture: Pay saved no waiting row", world.funds.row())
        assertEquals("fixture: Pay paid while the funds confirm", 0, world.submits())
        return world
    }

    private suspend fun assertNoFurtherPayment(
        world: OneDecisionWorld,
        vm: ChatViewModel,
        paidBefore: Int = 0,
    ) {
        world.fundsConfirmed()
        repeat(BLOCKS) {
            world.funds.block(vm)
            world.funds.paymentsSettled()
        }
        assertEquals("a block paid a setup the user withdrew", paidBefore, world.submits())
        world.contactAnswered()
        world.funds.block(vm)
        world.openChat(vm)
        world.funds.paymentsSettled()
        assertEquals("the contact's answer paid a setup the user withdrew", paidBefore, world.submits())
    }

    @Test
    fun `deleting the whole chat of a waiting first message pays nothing`() =
        runBlocking {
            val world = waitingRow()
            val vm = world.chatScreen()
            canHideMessages(vm)
            PaymentHarness.loadPendingMessagesFromPrefs(vm)
            showChat(vm, world.peer)

            vm.hideChat(world.peer)

            assertNoFurtherPayment(world, vm)
        }

    @Test
    fun `a payment after the delete pays itself only and leaves the mark`() =
        runBlocking {
            val world = waitingRow()
            val vm = world.chatScreen()
            vm.hideMessage(world.funds.row()!!.id)
            assertTrue("fixture: the delete left no mark", world.store.isAutomaticSetupWithdrawn(world.peer))
            world.fundsConfirmed()

            vm.sendPayment(world.peer, PAYMENT_ZEC)
            world.funds.paymentsSettled()

            assertEquals("the payment took a setup with it", 1, world.submits())
            assertTrue("a payment cleared the withdrawn mark", world.store.isAutomaticSetupWithdrawn(world.peer))
            assertNoFurtherPayment(world, vm, paidBefore = 1)
        }

    @Test
    fun `a new key exchange from the contact clears the mark`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 11)
            val prefs = HandshakePrefs(peer, firstContact = true).apply { setupWithdrawn += peer }
            val vm = HandshakeHarness.handshakeViewModel(prefs, HandshakeSeam(peer))

            val memo = HandshakeHarness.kexMemoFrom(peer, E2EEncryption.generateKeyPair())
            HandshakeHarness.handleKexMemo(vm, memo, txId = "synthetic-new-kex")
            HandshakeHarness.awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertFalse("the contact wrote first and the mark stayed", peer in prefs.setupWithdrawn)
        }

    @Test
    fun `control - a key exchange already handled leaves the mark`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 12)
            val prefs =
                HandshakePrefs(peer, firstContact = true).apply {
                    setupWithdrawn += peer
                    processedKexTx += "synthetic-old-kex"
                }
            val vm = HandshakeHarness.handshakeViewModel(prefs, HandshakeSeam(peer))

            val memo = HandshakeHarness.kexMemoFrom(peer, E2EEncryption.generateKeyPair())
            HandshakeHarness.handleKexMemo(vm, memo, txId = "synthetic-old-kex")
            HandshakeHarness.awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertTrue("a replayed key exchange cleared the mark", peer in prefs.setupWithdrawn)
        }

    @Test
    fun `deleting a row waiting for a secure connection takes its payload, leaves the mark, pays nothing`() =
        runBlocking {
            val world = OneDecisionWorld()
            world.memory.answers["getQuotedChatSetupPeers"] = setOf(world.peer)
            world.memory.consent = true
            world.chat.fixture.mode = ConversationMode.TUNNEL
            val vm = world.chatScreen()
            canHideMessages(vm)
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

            vm.hideMessage(TUNNEL_ROW)

            assertNull(
                "the deleted row is still stored",
                world.store.getPendingMessages().firstOrNull { it.id == TUNNEL_ROW },
            )
            assertTrue("the deleted row's payload stays queued", tunnelQueue().isEmpty())
            assertTrue("the delete left no mark", world.store.isAutomaticSetupWithdrawn(world.peer))
            assertNoFurtherPayment(world, vm)
        }

    @Test
    fun `a mark that cannot be saved pays nothing either`() =
        runBlocking {
            val world = waitingRow()
            world.memory.answers["setAutomaticSetupWithdrawn"] = false
            val vm = world.chatScreen()

            vm.hideMessage(world.funds.row()!!.id)

            assertFalse("fixture: the mark was saved", world.store.isAutomaticSetupWithdrawn(world.peer))
            assertNoFurtherPayment(world, vm)
        }

    /** The list's last pass drew the chat with its rows, as on the phone when Delete chat is tapped. */
    @Suppress("UNCHECKED_CAST")
    private fun showChat(
        vm: ChatViewModel,
        peer: String,
    ) {
        val rows =
            ChatViewModel::class.java
                .getDeclaredField("pendingMessages")
                .apply { isAccessible = true }
                .get(vm)
                .let { (it as MutableStateFlow<List<ChatMessage>>).value }
        assertTrue("fixture: the waiting row is not on the screen", rows.isNotEmpty())
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
        const val PAYMENT_ZEC = 0.001
        const val SETTLE_MS = 5_000L
        const val TUNNEL_ROW = "tunnel-wait-synthetic-1352"
    }
}
