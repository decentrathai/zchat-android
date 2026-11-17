package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.D
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 8 MA3, design test 17 (the task's "leave" case; MEMBER_ANNOUNCE_DESIGN.md v2 §2.2, §4 T2, review M3 / F9) —
 * a leave is announced only when the creator can verify it, and never costs anything.
 *
 * The creator C has A, B and X (all reachable over the relays) and D (not reachable), all told about each other.
 * - X's leave arrives on-chain unsigned (a 2.12.4 leave, or one with no key exchange): C marks X LEFT, tells nobody,
 *   and X's row says "Left (not confirmed)". "Remove for everyone" runs the removal, which then tells the others.
 * - X's leave arrives signed with X's key for C, or sealed by X over the relays: C tells A, B and X itself `R X` over
 *   the relays, free. D (no relay route) is owed nothing on-chain for a leave: no banner, nothing paid.
 *
 * At the MA2 tip no leave is ever announced and there is no "not confirmed" row note.
 *
 * Consent GRANTED. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaveAnnounceTest {
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

    private class World(
        val admin: AnnouncerPhone,
        val phoneX: AnnouncerPhone,
    )

    private fun world(): World {
        val admin =
            MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE, X to MemberStatus.ACTIVE, D to MemberStatus.ACTIVE))
        listOf(A, B, D).forEach { MA3.memberPhone(it, admin) }
        val phoneX = MA3.memberPhone(X, admin)
        listOf(A, B, X).forEach { admin.base.nostrKeys[it] = NPUB.getValue(it) }
        MA3.alreadyAnnounced(admin, listOf(A, B, X, D))
        return World(admin, phoneX)
    }

    private fun rx(npub: String) = net.gps(npub).filter { it.op == AnnounceOp.R && it.subject == X }

    @Test
    fun `an unsigned on-chain leave marks X left locally, announces nothing, and shows Left (not confirmed)`() =
        runBlocking {
            val w = world()
            net.register()
            val seam = HandshakeSeam(C)
            val vm = MA3.chatVm(w.admin, seam)
            val leftAt = MA3.now() + 60_000

            MA3.chain(vm, ZMSGGroupProtocol.createGroupLeaveMessage(GID, X, null, leftAt / 1000), tx = 500, timeMs = leftAt)

            assertEquals(MemberStatus.LEFT, w.admin.status(X))
            val f = GroupPaidOnceFixture().apply { scheduler = this@LeaveAnnounceTest.scheduler }
            val gseam = AnnounceSeam()
            val gvm = MA3.groupVm(w.admin, f, gseam)
            val conversation = MA3.load(gvm, f)
            assertTrue("an unconfirmed leave was announced: ${net.gps().map { it.op to it.subject }}", net.gps().none { it.subject == X })
            assertEquals("X's row does not say Left (not confirmed)", setOf(X), MA3.introduce(conversation, "leftUnconfirmed"))
            assertEquals(0, seam.proposalRequests.get() + gseam.proposals.size)

            // "Remove for everyone" is the removal: afterwards the others are told R X.
            f.awaitNewJobs(gvm) { gvm.kickMember(GID, X) }
            gvm.paidActionConfirmation.value?.let { MA3.confirm(gvm, f) }
            assertTrue("Remove for everyone did not tell A R X", rx(NPUB.getValue(A)).isNotEmpty())
            assertTrue("Remove for everyone did not tell B R X", rx(NPUB.getValue(B)).isNotEmpty())
        }

    @Test
    fun `a signed on-chain leave is announced R X over the relays to the rest and to X, with no on-chain debt`() =
        runBlocking {
            val w = world()
            net.register()
            val seam = HandshakeSeam(C)
            val vm = MA3.chatVm(w.admin, seam)
            val leftAt = MA3.now() + 60_000
            val ts = leftAt / 1000
            val sig = E2EEncryption.sign(MA3.keyFor(w.phoneX, C).privateKey, ZMSGGroupProtocol.groupLeaveSignedData(GID, X, ts))

            MA3.chain(vm, ZMSGGroupProtocol.createGroupLeaveMessage(GID, X, sig, ts), tx = 501, timeMs = leftAt)

            assertLeaveAnnouncedFree(w, seam)
        }

    @Test
    fun `a leave sealed by X over the relays is announced R X over the relays, with no on-chain debt`() =
        runBlocking {
            val w = world()
            net.register()
            val seam = HandshakeSeam(C)
            val vm = MA3.chatVm(w.admin, seam)
            val leftAt = MA3.now() + 60_000

            MA3.relay(vm, ZMSGGroupProtocol.createGroupLeaveMessage(GID, X, null, leftAt / 1000), sender = X, timeMs = leftAt)

            assertLeaveAnnouncedFree(w, seam)
        }

    private suspend fun assertLeaveAnnouncedFree(
        w: World,
        seam: HandshakeSeam,
    ) {
        assertEquals(MemberStatus.LEFT, w.admin.status(X))
        for (m in listOf(A, B, X)) {
            assertEquals("${m.take(22)} was not told R X over the relays", 1, rx(NPUB.getValue(m)).size)
        }
        val f = GroupPaidOnceFixture().apply { scheduler = this@LeaveAnnounceTest.scheduler }
        val gseam = AnnounceSeam()
        val gvm = MA3.groupVm(w.admin, f, gseam)
        val conversation = MA3.load(gvm, f)
        assertEquals("a leave made on-chain debt", 0, MA3.introduce(conversation, "owedOnChainPairs"))
        assertEquals("a verified leave is shown as not confirmed", emptySet<String>(), MA3.introduce(conversation, "leftUnconfirmed"))
        assertEquals("a leave was paid for", 0, seam.proposalRequests.get() + gseam.proposals.size)
    }
}
