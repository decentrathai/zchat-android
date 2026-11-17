package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 8 MA3, test MA-M1 (the task's "a member's message reaches every member"; MEMBER_ANNOUNCE_DESIGN.md v2 §1, §6.4)
 * — three phones: the creator C and the members A and B. B's member list is built only from C's announces.
 *
 * B writes to the group. The message goes to A and to C: to C free over the relays (B holds C's relay key), to A as
 * an on-chain memo (B and A share no relay link), paid only after B accepted the group's message cost. A's phone
 * stores the message under B's name, with no "not in your member list" note.
 *
 * At the MA2 tip C never announces anyone, so B's message goes to C only and A never sees it (PHONE_TRIAGE F4).
 *
 * Consent GRANTED; the only payment is the one B accepted. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberMessageReachesEveryMemberTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `B's message goes to A and to C, and A shows it as B's with no note`() =
        runBlocking {
            val admin = MA3.adminPhone(listOf(A to MemberStatus.INVITED, B to MemberStatus.ACTIVE))
            val phoneA = MA3.memberPhone(A, admin)
            val phoneB = MA3.memberPhone(B, admin)
            admin.base.nostrKeys[A] = NPUB.getValue(A)
            admin.base.nostrKeys[B] = NPUB.getValue(B)
            net.register()
            val adminVm = MA3.chatVm(admin)
            val vmA = MA3.chatVm(phoneA)
            val vmB = MA3.chatVm(phoneB)

            // A joins; C's announces reach both members.
            MA3.relay(adminVm, MA3.signedAccept(phoneA), sender = A, timeMs = MA3.now() + 60_000)
            net.to(NPUB.getValue(A)).filter { MA3.parseGp(it) != null }.forEach { MA3.relay(vmA, it, sender = C) }
            net.to(NPUB.getValue(B)).filter { MA3.parseGp(it) != null }.forEach { MA3.relay(vmB, it, sender = C) }

            // B writes to the group.
            val f = GroupPaidOnceFixture().apply { scheduler = this@MemberMessageReachesEveryMemberTest.scheduler }
            val seamB = AnnounceSeam()
            val gvmB = MA3.groupVm(phoneB, f, seamB)
            net.clear()
            f.awaitNewJobs(gvmB) { gvmB.sendGroupMessage(GID, "hello from B") }
            val quote = gvmB.paidActionConfirmation.value
            assertEquals("nothing may be paid before B accepts the message cost", 0, seamB.proposals.size)
            assertNotNull("B's message to A (paid on-chain) was never quoted: A is not a recipient", quote)
            assertEquals(PaidAction.GROUP_MESSAGE, quote!!.action)
            assertEquals("the quote does not count exactly A as paid", 1, quote.onChainMessages)
            MA3.confirm(gvmB, f)

            val toC = net.ofType(GroupMessageType.GROUP_MSG, NPUB.getValue(C))
            assertEquals("B's message did not go to C over the relays", 1, toC.size)
            val toA = seamB.proposals.filter { it.recipient == A }
            assertEquals("B's message was not paid for exactly once to A: ${seamB.proposals.map { it.recipient }}", 1, toA.size)
            assertEquals("B paid for someone other than A", 1, seamB.proposals.size)
            assertEquals(GroupMessageType.GROUP_MSG, ZMSGGroupProtocol.parseMessageType(toA.single().memo))

            // A's scan finds the memo.
            MA3.chain(vmA, toA.single().memo, tx = 700)
            val fA = GroupPaidOnceFixture().apply { scheduler = this@MemberMessageReachesEveryMemberTest.scheduler }
            val gvmA = MA3.groupVm(phoneA, fA, AnnounceSeam())
            val conversation = MA3.load(gvmA, fA)
            val fromB = conversation?.messages?.filter { it.senderAddress == B }.orEmpty()
            assertEquals("A does not show B's message under B's name", listOf("hello from B"), fromB.map { it.decryptedContent })
            assertFalse("A marks B as not in its member list", B in conversation!!.sendersNotInList)
            assertTrue("A lost the creator from its member list", conversation.members.any { it.address == C })
        }
}
