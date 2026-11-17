package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Round 8 MA2, design test 22 (MEMBER_ANNOUNCE_DESIGN.md v2 §0, §5.1 "Local LEFT", review H3) — a member who left and
 * came back stays in, however often the scan replays the old leave.
 *
 * The chain scan replays every group memo on every pass, in time order. Today GROUP_LEAVE marks the leaver LEFT every
 * time it is seen. On the creator's phone that undoes a comeback on every pass: X leaves, the creator invites X again
 * (X is INVITED), X joins (its new GROUP_ACCEPT), and at the next pass X's old leave marks X LEFT again, after which
 * processGroupAccept ignores X's new accept because X is LEFT. X is cut off from every message for good.
 *
 * Wanted: a leave applies only when the member's row is not already LEFT and the leave is newer than the member's last
 * activation (and than the last leave applied); an accept older than the member's last leave is not a comeback. So
 * leave -> re-invite -> comeback over three scan passes leaves X ACTIVE, on a member's phone (comeback by a verified
 * `A X`) and on the creator's (comeback by X's new accept), and the same leave applied twice changes nothing.
 *
 * Round 8 MA-V2 (VERIFIED_MA_FINDINGS.md #2, design §5.0): an accept with no source time (seen unmined on-chain) is not
 * newer than a leave. On the creator's phone, after X's leave and a re-invite, a replayed old accept that is still
 * unmined must not make X ACTIVE (and so must not announce X); it is judged once it is mined.
 *
 * Consent GRANTED; nothing is proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupLeaveReplayTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
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

    private val leaveAt = T + 10_000
    private val leave = AnnounceDriver.gl(X, leaveAt / 1_000)

    private fun signedAccept(x: E2EKeyPair): String {
        val sig = E2EEncryption.sign(x.privateKey, ZMSGGroupProtocol.groupAcceptSignedData(GID, X, x.publicKey))
        return ZMSGGroupProtocol.createGroupAcceptMessage(GID, X, x.publicKey, sig)
    }

    @Test
    fun `on a member's phone - leave, then a newer A X - X stays ACTIVE over three passes`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone)
            val seam = HandshakeSeam(ADMIN)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val joined = AnnounceDriver.gp(AnnounceOp.A, X, T + 1_000, 1, admin)
            val cameBack = AnnounceDriver.gp(AnnounceOp.A, X, T + 30_000, 1, admin)

            repeat(3) { pass ->
                AnnounceDriver.receive(vm, joined, Instant.ofEpochMilli(T + 1_000), AnnounceDriver.txId(1))
                AnnounceDriver.receive(vm, leave, Instant.ofEpochMilli(leaveAt), AnnounceDriver.txId(2))
                AnnounceDriver.receive(vm, cameBack, Instant.ofEpochMilli(T + 30_000), AnnounceDriver.txId(3))
                assertEquals("pass ${pass + 1}: X, who came back, is not ACTIVE", MemberStatus.ACTIVE, phone.status(GID, X))
            }
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `the same leave applied twice leaves the roster and the announce state unchanged`() =
        runBlocking {
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
            AnnounceDriver.receive(vm, leave, Instant.ofEpochMilli(leaveAt), AnnounceDriver.txId(2))
            assertEquals("fixture: the leave took X out", MemberStatus.LEFT, phone.status(GID, X))
            val roster = phone.rosters[GID]
            val state = phone.state(GID)
            assertEquals("the leave's time is not recorded", leaveAt, state.members[X]?.leftAtMs)

            AnnounceDriver.receive(vm, leave, Instant.ofEpochMilli(leaveAt), AnnounceDriver.txId(2))

            assertEquals(roster, phone.rosters[GID])
            assertEquals(state, phone.state(GID))
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `on the creator's phone - leave, re-invite, new accept - X stays ACTIVE over three passes`() =
        runBlocking {
            val phone = AnnouncePhone()
            phone.selfCreated += GID
            phone.putGroup(
                GroupInfo(groupId = GID, name = "Leave replay", creatorAddress = phone.self, createdAt = Instant.ofEpochMilli(T)),
                listOf(
                    GroupMember(address = phone.self, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(address = X, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.INVITED),
                ),
                1,
                AnnounceDriver.newGroupKey(),
            )
            val x = phone.link(X)
            val seam = HandshakeSeam(X)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val firstAccept = signedAccept(x)
            val secondAccept = signedAccept(x)

            AnnounceDriver.receive(vm, firstAccept, Instant.ofEpochMilli(T + 1_000), AnnounceDriver.txId(1))
            assertEquals("fixture: X's accept did not make X ACTIVE", MemberStatus.ACTIVE, phone.status(GID, X))
            AnnounceDriver.receive(vm, leave, Instant.ofEpochMilli(leaveAt), AnnounceDriver.txId(2))
            assertEquals("fixture: X's leave did not take X out", MemberStatus.LEFT, phone.status(GID, X))

            // The creator invites X again (the Add Member flow marks X INVITED).
            phone.rosters[GID] =
                ZMSGGroupProtocol.serializeGroupMembers(
                    phone.roster(GID).map { if (it.address == X) it.copy(status = MemberStatus.INVITED) else it },
                )

            repeat(3) { pass ->
                AnnounceDriver.receive(vm, firstAccept, Instant.ofEpochMilli(T + 1_000), AnnounceDriver.txId(1))
                AnnounceDriver.receive(vm, leave, Instant.ofEpochMilli(leaveAt), AnnounceDriver.txId(2))
                AnnounceDriver.receive(vm, secondAccept, Instant.ofEpochMilli(T + 30_000), AnnounceDriver.txId(3))
                assertEquals(
                    "pass ${pass + 1}: X's old leave undid X's comeback on the creator's phone",
                    MemberStatus.ACTIVE,
                    phone.status(GID, X),
                )
            }
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `on the creator's phone - leave, re-invite, then an unmined old accept replayed - X stays INVITED over three passes`() =
        runBlocking {
            val x = MA3.X
            val admin = MA3.adminPhone(listOf(MA3.A to MemberStatus.ACTIVE, x to MemberStatus.ACTIVE))
            MA3.memberPhone(MA3.A, admin)
            val phoneX = MA3.memberPhone(x, admin)
            listOf(MA3.A, x).forEach { admin.base.nostrKeys[it] = MA3.NPUB.getValue(it) }
            MA3.alreadyAnnounced(admin, listOf(MA3.A, x))
            val net = RelayNet().also { it.register() }
            val seam = HandshakeSeam(MA3.C)
            val vm = MA3.chatVm(admin, seam)

            // X leaves at t (sealed over the relays): X is LEFT and the leave's time is recorded.
            val leftAt = MA3.now() + 60_000
            MA3.relay(vm, ZMSGGroupProtocol.createGroupLeaveMessage(MA3.GID, x, null, leftAt / 1_000), sender = x, timeMs = leftAt)
            assertEquals("fixture: X's leave did not take X out", MemberStatus.LEFT, admin.status(x))
            assertEquals("fixture: the leave's time is not recorded", leftAt, admin.state().members[x]?.leftAtMs)

            // The creator invites X again (the Add Member flow marks X INVITED).
            admin.base.rosters[MA3.GID] =
                ZMSGGroupProtocol.serializeGroupMembers(
                    admin.base.roster(MA3.GID).map { if (it.address == x) it.copy(status = MemberStatus.INVITED) else it },
                )
            val activatedAt = admin.state().members[x]?.activatedAtMs
            net.clear()

            // X's old accept, seen by the scan while it is still unmined (no block time), on three passes.
            val oldAccept = MA3.signedAccept(phoneX)
            repeat(3) { pass ->
                AnnounceDriver.receive(vm, oldAccept, null, AnnounceDriver.txId(7))
                assertEquals(
                    "pass ${pass + 1}: an unmined accept counted as newer than X's leave",
                    MemberStatus.INVITED,
                    admin.status(x),
                )
                assertEquals("pass ${pass + 1}: X's activation time changed", activatedAt, admin.state().members[x]?.activatedAtMs)
            }
            assertTrue("X was announced on an unmined accept", net.gps().none { it.subject == x })

            // The same accept once mined, before the leave: still not a comeback.
            AnnounceDriver.receive(vm, oldAccept, Instant.ofEpochMilli(leftAt - 1_000), AnnounceDriver.txId(7))
            assertEquals("an accept mined before the leave made X ACTIVE", MemberStatus.INVITED, admin.status(x))
            assertTrue("X was announced on an accept older than its leave", net.gps().none { it.subject == x })

            // Control: a new accept mined after the leave is a comeback.
            AnnounceDriver.receive(vm, MA3.signedAccept(phoneX), Instant.ofEpochMilli(leftAt + 60_000), AnnounceDriver.txId(8))
            assertEquals("X's new accept, mined after the leave, did not make X ACTIVE", MemberStatus.ACTIVE, admin.status(x))
            assertEquals(0, seam.proposalRequests.get())
        }
}
