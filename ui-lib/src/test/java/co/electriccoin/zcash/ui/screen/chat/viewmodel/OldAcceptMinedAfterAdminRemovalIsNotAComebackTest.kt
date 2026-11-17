package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ActivatedBy
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerState
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.MemberAnnounceEntry
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ZC-506 Part A (also ZC-498) — on the creator's phone, a "I joined" reply (GROUP_ACCEPT) a member made BEFORE the
 * creator removed it is not that member's answer to the invitation sent AFTER the removal, whenever it is mined and
 * whatever the two phones' clocks say.
 *
 * T: X's phone submits its accept for the first invite (on-chain). T+5: the creator removes X (the key rotates from
 * epoch 1 to 2; the removal is recorded with its time and with removedAtEpoch 2). T+6: the creator invites X again (X
 * is INVITED; the invite carries key epoch 2). T+10: the old accept is mined. Today the replay guard compares the
 * accept's source time with the removal's time, and for a mined accept the source time is the block time, so the old
 * accept counts as newer than the removal: X is ACTIVE on the creator's phone with no Join, the other members are told
 * X is back, and group messages are sent to X, whose phone has not taken the new key. Over the relays the same happens
 * when the creator's clock is behind (ZC-498).
 *
 * Wanted: an accept that says which invitation it answers (`key_epoch`, the key epoch of that invite; unsigned) is
 * refused as a comeback when that epoch is older than the member's removal. The time rule stays for every accept.
 *
 * What the rule does NOT do, pinned here so nobody reads more into it (each case says so):
 * - (e) R1: an accept with no `key_epoch` (a 2.12.4 member's) is judged by the time rule alone, as before;
 * - (f) KI-138 / ZC-301: with the creator's clock ahead, a fresh accept not newer than the removal is still refused;
 * - (h) R5: X can raise the unsigned value on its own old accept; that is X's own action, so X's consent;
 * - (i) R6, [ZC-510]: an accept naming X that someone else sealed still makes X ACTIVE (recorded UNAUTH); the fix of
 *   ZC-510 changes this case on purpose;
 * - (j) R7: the check is per message; a refused old copy does not stop a later copy without the value.
 *
 * Consent GRANTED; nothing is proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OldAcceptMinedAfterAdminRemovalIsNotAComebackTest {
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

    /** The creator's phone after "remove X, invite X again", and the ways to put it back there between two tries. */
    private class World(
        val admin: AnnouncerPhone,
        val phoneX: AnnouncerPhone,
        val net: RelayNet,
        val seam: HandshakeSeam,
        val vm: ChatViewModel,
        val leftAt: Long,
    ) {
        private val roster = admin.base.rosters.getValue(GID)
        private val state: AnnounceState = admin.state()
        private val ledger = admin.ledger()
        private val counter = admin.counters.getValue(GID)
        val activatedAt: Long = state.members.getValue(X).activatedAtMs

        fun reset() {
            MA3.resetRuntime()
            admin.base.rosters[GID] = roster
            admin.base.states[GID] = state
            admin.ledgers[GID] = ledger
            admin.counters[GID] = counter
            net.clear()
        }

        fun announcedBack(): Int = net.gps(NPUB.getValue(A)).count { it.op == AnnounceOp.A && it.subject == X }

        /** X's announce record on the creator's phone. */
        fun entry(): MemberAnnounceEntry? = admin.state().members[X]
    }

    /**
     * C created the group; A is in it. X joined, was told about A and A about X, then C removed X fifteen minutes ago
     * (key epoch 1 to 2, recorded as a real removal records it) and invited X again: X's row is INVITED with a sent
     * invite.
     */
    private suspend fun world(): World {
        val leftAt = MA3.now() - 900_000L
        val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, X to MemberStatus.INVITED), epoch = 2)
        admin.base.keys["$GID|1"] = AnnounceDriver.newGroupKey()
        admin.base.floors[GID] = 2
        MA3.memberPhone(A, admin, epoch = 2)
        val phoneX = MA3.memberPhone(X, admin)
        listOf(A, X).forEach { admin.base.nostrKeys[it] = NPUB.getValue(it) }
        MA3.alreadyAnnounced(admin, listOf(A, X))
        val removed =
            admin.state().members.getValue(X).copy(
                n = leftAt + 1,
                op = AnnounceOp.R,
                epoch = 2,
                activatedAtMs = leftAt - 2_700_000L,
                activatedBy = ActivatedBy.AUTH,
                leftAtMs = leftAt,
                removedAtEpoch = 2,
                leftBy = ActivatedBy.AUTH,
            )
        admin.base.states[GID] = admin.state().copy(members = admin.state().members + (X to removed))
        admin.counters[GID] = leftAt + 1
        // A was told R X over the relays.
        admin.ledgers[GID] =
            admin.ledger().map {
                if (it.sAddr == X) {
                    it.copy(op = AnnounceOp.R, state = AnnounceLedgerState.RELAY_FINAL, txids = emptyList(), at = leftAt)
                } else {
                    it
                }
            }
        val net = RelayNet().also { it.register() }
        val seam = HandshakeSeam(C)
        val vm = MA3.chatVm(admin, seam)
        return World(admin, phoneX, net, seam, vm, leftAt)
    }

    /** [memo] (a GROUP_ACCEPT) saying it answers the invite of key epoch [epoch]; its signature is not touched. */
    private fun withKeyEpoch(
        memo: String,
        epoch: Int,
    ): String {
        val payload = ZMSGGroupProtocol.parsePayload(memo)!!
        return memo.removeSuffix(payload) + JSONObject(payload).put("key_epoch", epoch).toString()
    }

    private fun assertStillInvited(
        w: World,
        what: String,
    ) {
        assertEquals("$what made X ACTIVE without Join", MemberStatus.INVITED, w.admin.status(X))
        assertEquals("$what changed X's activation time", w.activatedAt, w.entry()?.activatedAtMs)
        assertEquals("$what: the members were told X is back", 0, w.announcedBack())
    }

    private fun assertCameBack(
        w: World,
        what: String,
        by: ActivatedBy = ActivatedBy.AUTH,
    ) {
        assertEquals("$what did not make X ACTIVE", MemberStatus.ACTIVE, w.admin.status(X))
        assertEquals("$what: how X came back", by, w.entry()?.activatedBy)
    }

    @Test
    fun `a - an accept for the first invite, mined five minutes after the removal, leaves X INVITED over three passes`() =
        runBlocking {
            val w = world()
            val old = withKeyEpoch(MA3.signedAccept(w.phoneX), 1)

            repeat(3) { pass ->
                MA3.chain(w.vm, old, tx = 7, timeMs = w.leftAt + 300_000)
                assertStillInvited(w, "pass ${pass + 1}: the accept for the invite of before the removal, mined after it,")
            }
            assertEquals(0, w.seam.proposalRequests.get())
        }

    @Test
    fun `b - the same accept seen unmined first and then mined after the removal leaves X INVITED`() =
        runBlocking {
            val w = world()
            val old = withKeyEpoch(MA3.signedAccept(w.phoneX), 1)

            repeat(2) {
                AnnounceDriver.receive(w.vm, old, null, AnnounceDriver.txId(7))
                assertStillInvited(w, "the unmined old accept")
            }
            repeat(3) { pass ->
                MA3.chain(w.vm, old, tx = 7, timeMs = w.leftAt + 300_000)
                assertStillInvited(w, "pass ${pass + 1}: the old accept, once mined,")
            }
            assertEquals(0, w.seam.proposalRequests.get())
        }

    @Test
    fun `c - a relay copy of the old accept whose time is after the removal on the creator's clock leaves X INVITED`() =
        runBlocking {
            val w = world()
            val old = withKeyEpoch(MA3.signedAccept(w.phoneX), 1)

            // ZC-498: a creator clock up to three minutes behind makes a copy made before the removal look newer. The
            // last one is dated in the future by X's phone; the relay layer turns that into "now".
            for (createdAt in listOf(w.leftAt + 1_000, w.leftAt + 60_000, w.leftAt + 180_000, MA3.now())) {
                MA3.relay(w.vm, old, sender = X, timeMs = createdAt)
                assertStillInvited(w, "the old accept over the relays, ${createdAt - w.leftAt} ms after the removal,")
            }
            assertEquals(0, w.seam.proposalRequests.get())
        }

    @Test
    fun `d - control - an accept for the new invite brings X back, authenticated, and the members are told once`() =
        runBlocking {
            for (overRelays in listOf(false, true)) {
                val w = world()
                val fresh = withKeyEpoch(MA3.signedAccept(w.phoneX), 2)

                repeat(3) {
                    if (overRelays) {
                        MA3.relay(w.vm, fresh, sender = X, timeMs = w.leftAt + 300_000)
                    } else {
                        MA3.chain(w.vm, fresh, tx = 8, timeMs = w.leftAt + 300_000)
                    }
                }

                assertCameBack(w, "relays=$overRelays: the accept for the new invite")
                assertEquals(w.leftAt + 300_000, w.entry()?.activatedAtMs)
                assertEquals("relays=$overRelays: A was not told exactly once that X is back", 1, w.announcedBack())
                assertEquals(0, w.seam.proposalRequests.get())
            }
        }

    @Test
    fun `e - control, residual R1 - an accept with no key epoch is judged by its time alone, as before`() =
        runBlocking {
            val w = world()
            val fieldless = MA3.signedAccept(w.phoneX)

            repeat(3) { pass ->
                AnnounceDriver.receive(w.vm, fieldless, null, AnnounceDriver.txId(9))
                assertStillInvited(w, "pass ${pass + 1}: an unmined accept with no key epoch")
                MA3.chain(w.vm, fieldless, tx = 9, timeMs = w.leftAt)
                assertStillInvited(w, "pass ${pass + 1}: an accept with no key epoch mined at the removal's time")
                MA3.chain(w.vm, fieldless, tx = 9, timeMs = w.leftAt - 60_000)
                assertStillInvited(w, "pass ${pass + 1}: an accept with no key epoch mined before the removal")
            }

            // The pinned residual: mined after the removal it is a comeback, whichever invite it answered.
            MA3.chain(w.vm, fieldless, tx = 9, timeMs = w.leftAt + 300_000)
            assertCameBack(w, "an accept with no key epoch mined after the removal")
            assertEquals(0, w.seam.proposalRequests.get())
        }

    @Test
    fun `f - control, KI-138 - a fresh accept that is not newer than the removal on the creator's clock stays refused`() =
        runBlocking {
            val w = world()
            val fresh = withKeyEpoch(MA3.signedAccept(w.phoneX), 2)

            MA3.chain(w.vm, fresh, tx = 8, timeMs = w.leftAt)
            assertStillInvited(w, "a fresh accept mined at the removal's time")
            MA3.relay(w.vm, fresh, sender = X, timeMs = w.leftAt - 120_000)
            assertStillInvited(w, "a fresh accept dated before the removal (the creator's clock is ahead)")
            assertEquals(0, w.seam.proposalRequests.get())
        }

    @Test
    fun `g - at every offset from the removal and on both transports the old accept is refused, the others follow the time rule`() =
        runBlocking {
            val offsets = listOf(-180_000L, -1_000L, 0L, 1_000L, 180_000L, 600_000L)
            for (overRelays in listOf(false, true)) {
                for (d in offsets) {
                    val w = world()
                    val accept = MA3.signedAccept(w.phoneX)
                    val where = "relays=$overRelays offset=${d / 1_000}s:"

                    fun deliver(
                        memo: String,
                        tx: Int,
                    ) {
                        if (overRelays) {
                            MA3.relay(w.vm, memo, sender = X, timeMs = w.leftAt + d)
                        } else {
                            MA3.chain(w.vm, memo, tx, w.leftAt + d)
                        }
                    }

                    deliver(withKeyEpoch(accept, 1), 20)
                    assertStillInvited(w, "$where the accept for the invite of before the removal")

                    for ((name, memo) in listOf("no key epoch" to accept, "the new invite's key epoch" to withKeyEpoch(accept, 2))) {
                        w.reset()
                        deliver(memo, 21)
                        if (d > 0) {
                            assertCameBack(w, "$where an accept with $name")
                        } else {
                            assertStillInvited(w, "$where an accept with $name")
                        }
                    }
                    assertEquals(0, w.seam.proposalRequests.get())
                }
            }
        }

    @Test
    fun `h - residual R5 - X raising the value on its own old accept is X's own answer`() =
        runBlocking {
            val w = world()
            val old = withKeyEpoch(MA3.signedAccept(w.phoneX), 1)
            // The same signed accept (the signature does not cover the value), re-sent by X with the new invite's epoch.
            val edited = withKeyEpoch(old, 2)

            MA3.chain(w.vm, edited, tx = 11, timeMs = w.leftAt + 300_000)

            assertCameBack(w, "X's own accept, re-sent by X for the new invite,")
            assertEquals(0, w.seam.proposalRequests.get())
        }

    @Test
    fun `i - residual R6, ZC-510 - an accept naming X that another member sealed still makes X ACTIVE, not authenticated`() =
        runBlocking {
            for (echo in listOf<Int?>(2, null)) {
                val w = world()
                val named = ZMSGGroupProtocol.createGroupAcceptMessage(GID, X, "", "")
                val forged = if (echo == null) named else withKeyEpoch(named, echo)

                MA3.relay(w.vm, forged, sender = A, timeMs = w.leftAt + 300_000)

                // [ZC-510] pinned as it is today: the fix of ZC-510 changes this case on purpose.
                assertCameBack(w, "key epoch=$echo: an accept naming X sealed by A", by = ActivatedBy.UNAUTH)
                assertEquals(0, w.seam.proposalRequests.get())
            }
        }

    @Test
    fun `j - residual R7 - the old accept is refused, and a later copy of it with no key epoch still counts by its time`() =
        runBlocking {
            val w = world()
            val accept = MA3.signedAccept(w.phoneX)

            MA3.chain(w.vm, withKeyEpoch(accept, 1), tx = 12, timeMs = w.leftAt + 300_000)
            assertStillInvited(w, "the accept for the invite of before the removal")

            MA3.chain(w.vm, accept, tx = 13, timeMs = w.leftAt + 360_000)
            assertCameBack(w, "a later copy with no key epoch, mined after the removal,")
            assertEquals("the check is per message", w.leftAt + 360_000, w.entry()?.activatedAtMs)
            assertEquals(0, w.seam.proposalRequests.get())
        }
}
