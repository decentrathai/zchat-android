package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.W
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GR-OPEN fixup (train T4b cross review, finding G-GR-1): in an all-Open group, a member whose sealed group key got no
 * relay ack still counts as able to read, and the next message is lost for them.
 *
 * At de241eba1 `keyReachableNow` asks only whether the relays are up (`reachableFree`), not whether this member's key
 * was acked, and `sendQuotedGroupMessage` ignores the outcome of the sealed-only key re-send it runs first. So a member
 * B whose sealed key the relays did not ack stays a recipient: the creator's message goes to B under a key B lacks, B's
 * phone marks the relay event seen, fails to decrypt it and never replays it. The creator's "can't read new messages
 * yet" line leaves B out, and a send that reaches nobody else is reported as sent.
 *
 * Wanted: after the sealed-only re-send, a member still owed the key with no session (its key got no ack in that pass)
 * is not a recipient and is named as unable to read; a member whose sealed key was not acked counts on the cost line as
 * unable to read; a later acked re-send makes it a recipient again. Nothing is ever paid on-chain here.
 *
 * Real GroupViewModel over the MA3 creator phone; the relays record every copy and ack per copy type (the member's key
 * copies get 0 acks, every other copy 1). All addresses, keys and ids are synthetic; nothing pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenGroupUnackedKeyMemberCannotReadTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

    /** Whether a key copy (sealed GROUP_KICK or GROUP_KEY) to B is acked. */
    @Volatile private var bKeyAcked = false

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        NostrChatBridge.registerPublisher { plaintext, recipient ->
            net.published += recipient to plaintext
            val type = ZMSGGroupProtocol.parseMessageType(plaintext)
            val keyCopy = type == GroupMessageType.GROUP_KICK || type == GroupMessageType.GROUP_KEY
            val acks = if (recipient == OpenGroup.npub(B) && keyCopy && !bKeyAcked) 0 else 1
            NostrChatBridge.PublishResult(acks = acks, messageId = "synthetic-gropen-fixup-${net.published.size}")
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private class Admin(
        val phone: AnnouncerPhone,
        val vm: GroupViewModel,
        val f: GroupPaidOnceFixture,
        val seam: AnnounceSeam,
    )

    private suspend fun admin(members: List<String>): Admin {
        val phone = OpenGroup.creator(members)
        val f = GroupPaidOnceFixture().apply { scheduler = this@OpenGroupUnackedKeyMemberCannotReadTest.scheduler }
        val seam = AnnounceSeam()
        return Admin(phone, MA3.groupVm(phone, f, seam), f, seam)
    }

    private fun row(
        a: Admin,
        address: String,
    ): GroupMember =
        a.phone.base
            .roster(GID)
            .single { it.address == address }

    private suspend fun sendMessage(
        a: Admin,
        text: String,
    ) {
        a.f.awaitNewJobs(a.vm) { a.vm.sendGroupMessage(GID, text) }
        if (a.vm.paidActionConfirmation.value != null) a.f.awaitNewJobs(a.vm) { a.vm.confirmPaidAction() }
    }

    private fun messagesTo(address: String): List<String> = net.ofType(GroupMessageType.GROUP_MSG, OpenGroup.npub(address))

    @Test
    fun `U1 B's sealed key is not acked at the removal - the cost line names B as unable to read`() =
        runBlocking {
            val a = admin(listOf(B, W, X))

            OpenGroup.kick(a.vm, a.f, X)
            assertEquals("fixture: B is owed the new key after an unacked sealed kick", 2, row(a, B).pendingKeyEpoch)
            assertNull("fixture: W's sealed key was acked", row(a, W).pendingKeyEpoch)

            val cost = a.vm.groupMessageCost(GID)
            assertEquals("B, whose key the relays did not ack, is not counted as unable to read", 1, cost.cannotRead)
            assertEquals(0, cost.onChainMessages)
            assertTrue("something was paid on-chain: ${a.seam.proposals}", a.seam.proposals.isEmpty())
        }

    @Test
    fun `U2 the re-send before the message is not acked either - the message goes to W only and B is still named`() =
        runBlocking {
            val a = admin(listOf(B, W, X))
            OpenGroup.kick(a.vm, a.f, X)
            net.clear()

            sendMessage(a, "admin while B has no key")

            assertTrue(
                "fixture: B was not re-sent the key before the message: ${net.to(OpenGroup.npub(B))}",
                net.ofType(GroupMessageType.GROUP_KEY, OpenGroup.npub(B)).isNotEmpty(),
            )
            assertTrue("the message did not reach W: ${a.vm.groupSendEvent.value}", messagesTo(W).isNotEmpty())
            assertTrue(
                "the message was published to B under a key B does not hold (lost on B's phone): ${messagesTo(B).size}",
                messagesTo(B).isEmpty(),
            )
            assertEquals("B is no longer owed the key although no re-send was acked", 2, row(a, B).pendingKeyEpoch)
            assertEquals("the cost line after the send does not name B", 1, a.vm.groupMessageCost(GID).cannotRead)
            assertTrue("something was paid on-chain: ${a.seam.proposals}", a.seam.proposals.isEmpty())
        }

    @Test
    fun `U3 B is the only other member - nothing is sent and the banner names B as unable to read`() =
        runBlocking {
            val a = admin(listOf(B, X))
            OpenGroup.kick(a.vm, a.f, X)
            net.clear()

            sendMessage(a, "admin while B has no key")

            assertTrue("the message was published to B under a key B does not hold", messagesTo(B).isEmpty())
            val event = a.vm.groupSendEvent.value
            assertTrue("the send was not reported as reaching nobody: $event", event is GroupSendResult.NoActiveRecipients)
            assertEquals(
                "the banner does not name B as unable to read",
                listOf(B_NAME),
                (event as GroupSendResult.NoActiveRecipients).cannotRead,
            )
            assertTrue("something was paid on-chain: ${a.seam.proposals}", a.seam.proposals.isEmpty())
        }

    @Test
    fun `U4 once a re-send is acked B is a recipient again and the cost line no longer names B (guard)`() =
        runBlocking {
            val a = admin(listOf(B, W, X))
            OpenGroup.kick(a.vm, a.f, X)
            bKeyAcked = true
            net.clear()

            sendMessage(a, "admin after the relays recovered")

            assertTrue(
                "B was not re-sent the key: ${net.to(OpenGroup.npub(B))}",
                net.ofType(GroupMessageType.GROUP_KEY, OpenGroup.npub(B)).isNotEmpty()
            )
            assertTrue("the message did not reach B after an acked re-send", messagesTo(B).isNotEmpty())
            assertNull("B is still owed after an acked re-send", row(a, B).pendingKeyEpoch)
            assertEquals(0, a.vm.groupMessageCost(GID).cannotRead)
            assertTrue("something was paid on-chain: ${a.seam.proposals}", a.seam.proposals.isEmpty())
        }

    private companion object {
        /** How the group screens name a member with no nickname and no saved contact: GroupMember.displayName. */
        val B_NAME = "${B.take(8)}...${B.takeLast(6)}"
    }
}
