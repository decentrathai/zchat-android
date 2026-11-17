package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X2
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Y
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
 * Round 8 MA2, MA-R1 (the removal case of the member-announce task, receiver side; design §5.1 "Local LEFT", §5.2) —
 * a member who is out of the group is out of every member's fan-out.
 *
 * On a member's phone the creator announced X in form c2 ([X2]) and Y; the phone had also learned X from a group
 * message in another form, c1 ([X]). Today removing X marks only the rows that match the removed form, so the c1 row
 * stays ACTIVE and every message keeps going (and being paid for) to X.
 *
 * Wanted: after a verified removal of X (the creator's `R X`, the creator's kick of X, or X's own verified leave:
 * signed on-chain or sealed over the relays), neither form of X is in messageRecipients; Y still is.
 *
 * Consent GRANTED; nothing is proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberRemovedFromEveryFanOutTest {
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

    private class Setup(
        val phone: AnnouncePhone,
        val admin: E2EKeyPair,
        val x2: E2EKeyPair,
        val vm: ChatViewModel,
        val seam: HandshakeSeam,
    )

    private suspend fun announced(): Setup {
        val phone = AnnouncePhone()
        val admin =
            AnnounceDriver.memberPhone(
                phone,
                extraRows = listOf(GroupMember(address = X, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE)),
            )
        val x2 = phone.link(X2)
        val seam = HandshakeSeam(ADMIN)
        val vm = AnnounceDriver.chatVm(phone, seam)
        AnnounceDriver.receive(
            vm,
            AnnounceDriver.gp(AnnounceOp.A, X2, T + 1_000, 1, admin),
            Instant.ofEpochMilli(T + 1_000),
            AnnounceDriver.txId(1)
        )
        AnnounceDriver.receive(
            vm,
            AnnounceDriver.gp(AnnounceOp.A, Y, T + 1_001, 1, admin),
            Instant.ofEpochMilli(T + 1_001),
            AnnounceDriver.txId(2)
        )
        return Setup(phone, admin, x2, vm, seam)
    }

    private suspend fun assertXOut(
        s: Setup,
        how: String
    ) {
        val f = GroupPaidOnceFixture().apply { scheduler = this@MemberRemovedFromEveryFanOutTest.scheduler }
        val recipients = AnnounceDriver.messageRecipients(AnnounceDriver.groupVm(s.phone, f))
        assertFalse("after $how, a form of X is still a recipient: $recipients", recipients.any { it == X || it == X2 })
        assertTrue("after $how, the announced member Y is not a recipient: $recipients", Y in recipients)
        assertEquals(0, s.seam.proposalRequests.get())
        assertEquals(0, f.seam.proposalRequests.get())
    }

    @Test
    fun `after the creator's R X, no form of X is a recipient`() =
        runBlocking {
            val s = announced()
            AnnounceDriver.receive(
                s.vm,
                AnnounceDriver.gp(AnnounceOp.R, X2, T + 5_000, 1, s.admin),
                Instant.ofEpochMilli(T + 5_000),
                AnnounceDriver.txId(5)
            )
            assertXOut(s, "R X")
        }

    @Test
    fun `after the creator's kick of X, no form of X is a recipient`() =
        runBlocking {
            val s = announced()
            val kick = AnnounceDriver.kick(s.phone, s.admin, X2, newEpoch = 2, newKey = AnnounceDriver.newGroupKey())
            AnnounceDriver.receive(s.vm, kick, Instant.ofEpochMilli(T + 5_000), AnnounceDriver.txId(5))
            assertXOut(s, "the kick of X")
        }

    @Test
    fun `after X's signed on-chain leave, no form of X is a recipient`() =
        runBlocking {
            val s = announced()
            AnnounceDriver.receive(
                s.vm,
                AnnounceDriver.gl(X2, (T + 5_000) / 1_000, s.x2),
                Instant.ofEpochMilli(T + 5_000),
                AnnounceDriver.txId(5)
            )
            assertXOut(s, "X's signed leave")
        }

    @Test
    fun `after X's leave sealed over the relays, no form of X is a recipient`() =
        runBlocking {
            val s = announced()
            AnnounceDriver.receive(
                s.vm,
                AnnounceDriver.gl(X2, (T + 5_000) / 1_000),
                Instant.ofEpochMilli(T + 5_000),
                sender = X2,
                eventId = "synthetic-event-gl"
            )
            assertXOut(s, "X's sealed leave")
        }
}
