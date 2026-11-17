package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
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
 * Round 8 MA3, test MA-J1 (the task's "join / rotation" case; MEMBER_ANNOUNCE_DESIGN.md v2 §4 table, §5.1, §6.1 (c),
 * review L6) — comebacks are announced again, a key rotation alone announces nothing, a later joiner is told about the
 * earlier members at the new key epoch, and an announce from a newer epoch makes a member pull its roster, never ask
 * for the key.
 *
 * - X leaves (sealed over the relays; C announces `R X`), is re-invited (INVITED again) and sends a newer signed
 *   accept: C announces a newer `A X`, and B's phone, which took `R X`, takes X back.
 * - C rotates the group key: no GP goes out, nothing is owed, and B's phone keeps A (and C) as recipients after
 *   taking the new key.
 * - D joins at key epoch 2: C tells D about A and B, and A and B about D, each signed at epoch 2.
 * - B's phone (epoch 1) takes a verified GP signed at epoch 2: it sends C one roster query (GQ) over the relays, not a
 *   key request (GR), and a second such GP sends nothing more.
 *
 * At the MA2 tip nothing announces and no GQ is ever sent.
 *
 * Consent GRANTED; nothing is paid on any phone. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RejoinAndRotationReannounceTest {
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
    fun `after a leave, a re-invite and a newer signed accept, C announces A X again and B takes X back`() =
        runBlocking {
            val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE, X to MemberStatus.ACTIVE))
            MA3.memberPhone(A, admin)
            val phoneB = MA3.memberPhone(B, admin)
            val phoneX = MA3.memberPhone(X, admin)
            listOf(A, B, X).forEach { admin.base.nostrKeys[it] = NPUB.getValue(it) }
            MA3.alreadyAnnounced(admin, listOf(A, B, X))
            net.register()
            val seam = HandshakeSeam(C)
            val vm = MA3.chatVm(admin, seam)
            val vmB = MA3.chatVm(phoneB)
            MA3.chain(
                vmB,
                MA3.gpFrom(
                    admin,
                    B,
                    AnnounceOp.A,
                    X,
                    admin
                        .state()
                        .members
                        .getValue(X)
                        .n
                ),
                tx = 1
            )
            assertEquals(MemberStatus.ACTIVE, phoneB.status(X))

            // X leaves (sealed); C announces R X; B takes it.
            val leftAt = MA3.now() + 60_000
            MA3.relay(vm, ZMSGGroupProtocol.createGroupLeaveMessage(GID, X, null, leftAt / 1000), sender = X, timeMs = leftAt)
            val rToB = net.gps(NPUB.getValue(B)).filter { it.op == AnnounceOp.R && it.subject == X }
            assertTrue("C did not announce X's leave", rToB.isNotEmpty())
            net.to(NPUB.getValue(B)).filter { MA3.parseGp(it) != null }.forEach { MA3.relay(vmB, it, sender = C) }
            assertEquals(MemberStatus.LEFT, phoneB.status(X))

            // C re-invites X (the invite puts X back to INVITED), and X accepts again, later.
            admin.base.rosters[GID] =
                ZMSGGroupProtocol.serializeGroupMembers(
                    admin.base.roster(GID).map { if (it.address == X) it.copy(status = MemberStatus.INVITED) else it },
                )
            net.clear()
            MA3.relay(vm, MA3.signedAccept(phoneX), sender = X, timeMs = leftAt + 60_000)

            val aToB = net.gps(NPUB.getValue(B)).filter { it.op == AnnounceOp.A && it.subject == X }
            assertEquals("C did not announce X's comeback to B", 1, aToB.size)
            assertTrue("the comeback is not newer than the leave", aToB.single().n > rToB.maxOf { it.n })
            net.to(NPUB.getValue(B)).filter { MA3.parseGp(it) != null }.forEach { MA3.relay(vmB, it, sender = C) }
            assertEquals("B did not take X back", MemberStatus.ACTIVE, phoneB.status(X))
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `a key rotation alone sends no GP, owes nothing, and keeps every announced member in every member's fan-out`() =
        runBlocking {
            val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE))
            MA3.memberPhone(A, admin)
            val phoneB = MA3.memberPhone(B, admin)
            listOf(A, B).forEach { admin.base.nostrKeys[it] = NPUB.getValue(it) }
            MA3.alreadyAnnounced(admin, listOf(A, B))
            val vmB = MA3.chatVm(phoneB)
            MA3.chain(
                vmB,
                MA3.gpFrom(
                    admin,
                    B,
                    AnnounceOp.A,
                    A,
                    admin
                        .state()
                        .members
                        .getValue(A)
                        .n
                ),
                tx = 2
            )
            net.register()
            val seam = AnnounceSeam()
            val f = GroupPaidOnceFixture().apply { scheduler = this@RejoinAndRotationReannounceTest.scheduler }
            val gvm = MA3.groupVm(admin, f, seam)
            MA3.load(gvm, f)
            net.clear()

            f.awaitNewJobs(gvm) { gvm.rotateGroupKey(GID) }
            gvm.paidActionConfirmation.value?.let { MA3.confirm(gvm, f) }

            assertEquals("the rotation did not happen", 2, admin.base.epochs[GID])
            assertTrue("a key rotation sent a GP: ${net.gps().map { it.op to it.subject }}", net.gps().isEmpty())
            val conversation = MA3.load(gvm, f)
            assertEquals("a key rotation made announce debt", 0, MA3.introduce(conversation, "owedOnChainPairs"))
            assertEquals(0, seam.proposals.size)

            net.ofType(GroupMessageType.GROUP_KEY, NPUB.getValue(B)).forEach { MA3.relay(vmB, it, sender = C) }
            assertEquals("setup: B did not take the new key", 2, phoneB.base.epochs[GID])
            val recipients = AnnounceDriver.messageRecipients(MA3.groupVm(phoneB, GroupPaidOnceFixture(), AnnounceSeam()), GID)
            assertEquals("B's fan-out lost a member after the rotation", setOf(A, C), recipients.toSet())
        }

    @Test
    fun `a member who joins after a rotation is told about the earlier members, signed at the new epoch`() =
        runBlocking {
            val admin =
                MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE, D to MemberStatus.INVITED), epoch = 2)
            MA3.memberPhone(A, admin, epoch = 2)
            MA3.memberPhone(B, admin, epoch = 2)
            val phoneD = MA3.memberPhone(D, admin, epoch = 2)
            listOf(A, B, D).forEach { admin.base.nostrKeys[it] = NPUB.getValue(it) }
            MA3.alreadyAnnounced(admin, listOf(A, B))
            net.register()
            val vm = MA3.chatVm(admin)

            MA3.relay(vm, MA3.signedAccept(phoneD), sender = D, timeMs = MA3.now() + 60_000)

            val toD = net.gps(NPUB.getValue(D))
            assertEquals("D was not told about A and B", setOf(A, B), toD.map { it.subject }.toSet())
            assertTrue("D was told at an old epoch: ${toD.map { it.epoch }}", toD.all { it.epoch == 2 })
            for (m in listOf(A, B)) {
                val aboutD = net.gps(NPUB.getValue(m)).filter { it.subject == D }
                assertEquals("${m.take(22)} was not told about D", 1, aboutD.size)
                assertEquals(2, aboutD.single().epoch)
            }
        }

    @Test
    fun `a verified GP from a newer key epoch makes the member send one roster query over the relays, never a key request`() =
        runBlocking {
            val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE, D to MemberStatus.ACTIVE))
            val phoneB = MA3.memberPhone(B, admin)
            net.register()
            val seam = HandshakeSeam(B)
            val vmB = MA3.chatVm(phoneB, seam)
            val gqBefore = net.ofType(GroupMessageType.GROUP_ROSTER_QUERY, NPUB.getValue(C)).size

            MA3.relay(vmB, MA3.gpFrom(admin, B, AnnounceOp.A, A, MA3.now(), epoch = 2), sender = C)

            assertEquals(MemberStatus.ACTIVE, phoneB.status(A))
            val gq = net.ofType(GroupMessageType.GROUP_ROSTER_QUERY, NPUB.getValue(C))
            assertEquals("B did not ask C for the roster once over the relays", gqBefore + 1, gq.size)
            MA3.relay(vmB, MA3.gpFrom(admin, B, AnnounceOp.A, D, MA3.now() + 1, epoch = 2), sender = C)
            assertEquals(
                "B asked for the roster twice for the same epoch",
                gqBefore + 1,
                net.ofType(GroupMessageType.GROUP_ROSTER_QUERY).size
            )
            assertTrue("B asked for the key (GR)", net.ofType(GroupMessageType.GROUP_KEY_REQUEST).isEmpty())
            assertEquals(0, seam.proposalRequests.get())
        }
}
