package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Z
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Round 10b R10B-2 (F-R9B-2) — a removed member's old messages say "Not in your member list yet".
 *
 * On a member's phone whose roster the creator announced, GroupViewModel puts every message sender who is not ACTIVE
 * in the roster into sendersNotInList, and the bubble then reads group_sender_not_in_list ("Not in your member list
 * yet: your messages don't reach them."). That includes a member the creator removed (an R announce, or a kick): the
 * "yet" promises a listing that will not come.
 *
 * Wanted: a sender whose announce entry is R, or whose roster row is KICKED or LEFT, is in the new set
 * GroupConversation.sendersRemoved, labelled group_sender_removed ("No longer in this group: your messages don't
 * reach them." / "Ya no está en este grupo: tus mensajes no le llegan."), and not in sendersNotInList, which keeps
 * only senders who were never listed.
 *
 * sendersRemoved is read by name, so this file compiles before it exists. Consent GRANTED; nothing is proposed. All
 * addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemovedMemberSenderLabelTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private data class Phone(
        val phone: AnnouncePhone,
        val admin: E2EKeyPair,
        val vm: ChatViewModel,
        val seam: HandshakeSeam,
    )

    /** A member's phone whose roster the creator announced (A X), holding one message from X. */
    private suspend fun phoneWithMessageFromX(): Phone {
        val phone = AnnouncePhone()
        val admin = AnnounceDriver.memberPhone(phone)
        val seam = HandshakeSeam(ADMIN)
        val vm = AnnounceDriver.chatVm(phone, seam)
        AnnounceDriver.receive(
            vm,
            AnnounceDriver.gp(AnnounceOp.A, X, T + 1_000, 1, admin),
            Instant.ofEpochMilli(T + 1_000),
            AnnounceDriver.txId(1)
        )
        assertEquals("fixture: the verified announce did not add X", MemberStatus.ACTIVE, phone.status(GID, X))
        AnnounceDriver.receive(vm, AnnounceDriver.gm(phone, X, "hi from X"), Instant.ofEpochMilli(T + 2_000), AnnounceDriver.txId(2))
        assertEquals("fixture: X's message was not stored", 1, phone.storedMessageIds(GID).size)
        return Phone(phone, admin, vm, seam)
    }

    private suspend fun load(phone: AnnouncePhone): GroupConversation? {
        val f = GroupPaidOnceFixture().apply { scheduler = this@RemovedMemberSenderLabelTest.scheduler }
        return AnnounceDriver.loadedConversation(AnnounceDriver.groupVm(phone, f), f)
    }

    private fun assertRemovedLabel(
        conversation: GroupConversation?,
        sender: String
    ) {
        val removed = AnnounceDriver.conversationSet(conversation, "sendersRemoved")
        val notInList = AnnounceDriver.conversationSet(conversation, "sendersNotInList")
        assertTrue("the removed member's messages are not marked removed: $removed", removed?.contains(sender) == true)
        assertFalse("the removed member's messages still say \"not in your member list yet\"", notInList?.contains(sender) == true)
    }

    @Test
    fun `a member the creator removed with an R announce is labelled removed, not not-listed-yet`() =
        runBlocking {
            val (phone, admin, vm, seam) = phoneWithMessageFromX()

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.R, X, T + 3_000, 1, admin),
                Instant.ofEpochMilli(T + 3_000),
                AnnounceDriver.txId(3)
            )
            assertEquals("fixture: the R announce did not take X out", MemberStatus.LEFT, phone.status(GID, X))
            assertEquals("fixture: X's old message was dropped", 1, phone.storedMessageIds(GID).size)

            assertRemovedLabel(load(phone), X)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `a member the creator kicked is labelled removed, not not-listed-yet`() =
        runBlocking {
            val (phone, admin, vm, seam) = phoneWithMessageFromX()

            val kick = AnnounceDriver.kick(phone, admin, X, newEpoch = 2, newKey = AnnounceDriver.newGroupKey())
            AnnounceDriver.receive(vm, kick, Instant.ofEpochMilli(T + 3_000), AnnounceDriver.txId(3))
            assertEquals("fixture: the kick did not take X out", MemberStatus.LEFT, phone.status(GID, X))

            assertRemovedLabel(load(phone), X)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `control - a sender who was never listed keeps not-listed-yet and is not marked removed`() =
        runBlocking {
            val (phone, _, vm, seam) = phoneWithMessageFromX()

            AnnounceDriver.receive(vm, AnnounceDriver.gm(phone, Z, "hello from Z"), Instant.ofEpochMilli(T + 3_000), AnnounceDriver.txId(3))
            val conversation = load(phone)

            val notInList = AnnounceDriver.conversationSet(conversation, "sendersNotInList")
            assertTrue("an unlisted sender lost its note: $notInList", notInList?.contains(Z) == true)
            assertFalse("a listed member is marked not listed", notInList?.contains(X) == true)
            val removed = AnnounceDriver.conversationSet(conversation, "sendersRemoved")
            assertFalse("a never-listed sender is marked removed", removed?.contains(Z) == true)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `the removed label is a resource in English and Spanish, and the bubble shows it`() {
        assertEquals(
            "No longer in this group: your messages don't reach them.",
            ChatResourceText.strings("values")["group_sender_removed"],
        )
        assertEquals("Ya no está en este grupo: tus mensajes no le llegan.", ChatResourceText.strings("values-es")["group_sender_removed"])
        val view = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupDetailView.kt")
        assertTrue(
            "GroupDetailView does not pass senderRemoved",
            "senderRemoved = message.senderAddress in conversation.sendersRemoved" in view
        )
        assertTrue("GroupDetailView does not show group_sender_removed", "R.string.group_sender_removed" in view)
    }
}
