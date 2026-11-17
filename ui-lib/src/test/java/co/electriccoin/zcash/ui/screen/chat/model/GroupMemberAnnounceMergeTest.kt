package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Round 8 MA1, design test 2 (MEMBER_ANNOUNCE_DESIGN.md v2 §5.1) — the pure merge a receiver applies for a verified
 * member announce. Nothing here authenticates: `applyMemberAnnounce` is handed an already-verified GP.
 *
 * | Entry | Row absent | INVITED | ACTIVE | LEFT |
 * |---|---|---|---|---|
 * | A | add ACTIVE | → ACTIVE | no row change | → ACTIVE only if e >= st.removedAtEpoch and n > st.leftAtMs |
 * | R | add a LEFT tombstone | → LEFT | → LEFT | no row change |
 *
 * `n <= st.n` is dropped; an A naming this phone or the creator is ignored; an applied entry sets st.n / o / e and
 * hw = max(hw, n); an applied A sets activatedAtMs = n and activatedBy = AUTH; the first applied GP sets
 * rosterAnnounced. All addresses are synthetic.
 */
class GroupMemberAnnounceMergeTest {
    private val api = AnnounceApi

    /** Two forms of X's address resolve to one canonical address (the #205/#214 canonicalizer). */
    private val canon: (String) -> String = { if (it == X_ALIAS) X else it }
    private val book = mapOf(X to "Xavier", Y to "Yolanda")
    private val nickname: (String) -> String? = { book[canon(it)] }

    private fun row(
        address: String,
        status: MemberStatus,
        isAdmin: Boolean = false
    ) = GroupMember(address = address, joinedAt = Instant.ofEpochMilli(1_000L), status = status, isAdmin = isAdmin)

    private fun ann(
        op: MaOp,
        subject: String,
        n: Long,
        epoch: Int = 1
    ) = MaAnn(GID, op, subject, n, epoch, "c2lnbmF0dXJl")

    private fun apply(
        state: MaState,
        roster: List<GroupMember>,
        ann: MaAnn,
        isSelf: Boolean = false,
        isCreator: Boolean = false,
    ) = api.applyMemberAnnounce(state, roster, ann, isSelf, isCreator, canon, nickname)

    private val base = listOf(row(CREATOR, MemberStatus.ACTIVE, isAdmin = true), row(SELF, MemberStatus.ACTIVE))

    private fun statusOf(
        roster: List<GroupMember>,
        address: String
    ) = roster.filter { canon(it.address) == canon(address) }.map { it.status }

    // ── A ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `A on an absent row adds the member as ACTIVE with the contact-book nickname`() {
        val r = apply(MaState(), base, ann(MaOp.A, X, 100))
        assertTrue(r.applied)
        val added = r.roster.single { it.address == X }
        assertEquals(MemberStatus.ACTIVE, added.status)
        assertFalse(added.isAdmin)
        assertEquals("Xavier", added.nickname)
        assertEquals(base, r.roster.filter { it.address != X })
        assertEquals(MaEntry(n = 100, op = MaOp.A, epoch = 1, activatedAtMs = 100, activatedBy = MaBy.AUTH), r.state.members[X])
        assertEquals(100L, r.state.hw)
        assertTrue(r.state.rosterAnnounced)
    }

    @Test
    fun `A on an INVITED row makes it ACTIVE`() {
        val r = apply(MaState(), base + row(X, MemberStatus.INVITED), ann(MaOp.A, X, 100))
        assertTrue(r.applied)
        assertEquals(listOf(MemberStatus.ACTIVE), statusOf(r.roster, X))
        assertEquals(base.size + 1, r.roster.size)
    }

    @Test
    fun `A on an ACTIVE row leaves the roster as it is and records the announce`() {
        val roster = base + row(X, MemberStatus.ACTIVE)
        val r = apply(MaState(), roster, ann(MaOp.A, X, 100))
        assertTrue(r.applied)
        assertEquals(roster, r.roster)
        assertEquals(MaOp.A, r.state.members[X]?.op)
        assertEquals(100L, r.state.members[X]?.n)
    }

    @Test
    fun `A on a LEFT row brings it back only at or above its removal epoch and after it left`() {
        val roster = base + row(X, MemberStatus.LEFT)
        val left = MaState(members = mapOf(X to MaEntry(n = 50, op = MaOp.R, epoch = 2, leftAtMs = 200, removedAtEpoch = 3)))

        val back = apply(left, roster, ann(MaOp.A, X, 300, epoch = 3))
        assertTrue(back.applied)
        assertEquals(listOf(MemberStatus.ACTIVE), statusOf(back.roster, X))
        assertEquals(300L, back.state.members[X]?.activatedAtMs)
        assertEquals("the removal record stays", 3, back.state.members[X]?.removedAtEpoch)

        val oldEpoch = apply(left, roster, ann(MaOp.A, X, 300, epoch = 2))
        assertFalse("signed below the epoch X was removed at", oldEpoch.applied)
        assertEquals(roster, oldEpoch.roster)
        assertEquals(left, oldEpoch.state)

        val beforeLeaving = apply(left, roster, ann(MaOp.A, X, 200, epoch = 3))
        assertFalse("n not after the time X left", beforeLeaving.applied)
        assertEquals(roster, beforeLeaving.roster)
        assertEquals(left, beforeLeaving.state)
    }

    // ── R ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `R on an absent row adds a LEFT tombstone`() {
        val r = apply(MaState(), base, ann(MaOp.R, X, 100))
        assertTrue(r.applied)
        assertEquals(listOf(MemberStatus.LEFT), statusOf(r.roster, X))
        assertEquals(MaOp.R, r.state.members[X]?.op)
        assertNull("an R never marks an activation", r.state.members[X]?.activatedBy)
        assertTrue(r.state.rosterAnnounced)
    }

    @Test
    fun `R on an INVITED or ACTIVE row makes it LEFT`() {
        for (status in listOf(MemberStatus.INVITED, MemberStatus.ACTIVE)) {
            val r = apply(MaState(), base + row(X, status), ann(MaOp.R, X, 100))
            assertTrue("$status", r.applied)
            assertEquals("$status", listOf(MemberStatus.LEFT), statusOf(r.roster, X))
            assertEquals(base.size + 1, r.roster.size)
        }
    }

    @Test
    fun `R on a LEFT row leaves the roster as it is and records the announce`() {
        val roster = base + row(X, MemberStatus.LEFT)
        val r = apply(MaState(), roster, ann(MaOp.R, X, 100))
        assertTrue(r.applied)
        assertEquals(roster, r.roster)
        assertEquals(MaEntry(n = 100, op = MaOp.R, epoch = 1), r.state.members[X])
    }

    // ── order, self, creator ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an announce not newer than the last applied one for that member is dropped`() {
        val roster = base + row(X, MemberStatus.ACTIVE)
        val st = MaState(hw = 100, rosterAnnounced = true, members = mapOf(X to MaEntry(n = 100, op = MaOp.A, epoch = 1)))
        for (n in listOf(100L, 99L, 1L)) {
            for (op in listOf(MaOp.A, MaOp.R)) {
                val r = apply(st, roster, ann(op, X, n))
                assertFalse("op=$op n=$n", r.applied)
                assertEquals(roster, r.roster)
                assertEquals(st, r.state)
            }
        }
        assertTrue(apply(st, roster, ann(MaOp.R, X, 101)).applied)
    }

    @Test
    fun `the same announce replayed changes nothing the second time`() {
        val first = apply(MaState(), base + row(X, MemberStatus.INVITED), ann(MaOp.A, X, 100))
        val again = apply(first.state, first.roster, ann(MaOp.A, X, 100))
        assertFalse(again.applied)
        assertEquals(first.roster, again.roster)
        assertEquals(first.state, again.state)
    }

    @Test
    fun `an A naming this phone or the creator is ignored`() {
        val selfA = apply(MaState(), base, ann(MaOp.A, SELF, 100), isSelf = true)
        assertFalse(selfA.applied)
        assertEquals(base, selfA.roster)
        assertEquals(MaState(), selfA.state)

        for (op in listOf(MaOp.A, MaOp.R)) {
            val creator = apply(MaState(), base, ann(op, CREATOR, 100), isCreator = true)
            assertFalse("creator $op", creator.applied)
            assertEquals(base, creator.roster)
            assertEquals(MaState(), creator.state)
        }
    }

    @Test
    fun `an R naming this phone applies only after the join and at or above the join epoch`() {
        val joined = MaState(hw = 500, rosterAnnounced = true, selfJoinN = 500, selfJoinEpoch = 4)
        val tooOld = apply(joined, base, ann(MaOp.R, SELF, 500, epoch = 4), isSelf = true)
        assertFalse(tooOld.applied)
        assertEquals(base, tooOld.roster)
        val oldEpoch = apply(joined, base, ann(MaOp.R, SELF, 600, epoch = 3), isSelf = true)
        assertFalse(oldEpoch.applied)

        val removed = apply(joined, base, ann(MaOp.R, SELF, 600, epoch = 4), isSelf = true)
        assertTrue(removed.applied)
        assertEquals("the own row is kept, as LEFT", listOf(MemberStatus.LEFT), statusOf(removed.roster, SELF))
        assertEquals(base.size, removed.roster.size)
        assertEquals(600L, removed.state.hw)
    }

    @Test
    fun `per-row epoch - X kicked at e1 and re-announced at e1 still comes back after Y is kicked at e2`() {
        // X was kicked at epoch 1 and re-invited; the admin's A for X is signed at epoch 1. Before it reaches this
        // phone, Y is kicked (epoch 2). A group-wide removal epoch would drop X's comeback; the per-row one does not.
        val roster = base + row(X, MemberStatus.LEFT) + row(Y, MemberStatus.LEFT)
        val st =
            MaState(
                hw = 400,
                rosterAnnounced = true,
                members =
                    mapOf(
                        X to MaEntry(n = 100, op = MaOp.A, epoch = 0, leftAtMs = 150, removedAtEpoch = 1),
                        Y to MaEntry(n = 110, op = MaOp.A, epoch = 0, leftAtMs = 400, removedAtEpoch = 2),
                    ),
            )
        val r = apply(st, roster, ann(MaOp.A, X, 300, epoch = 1))
        assertTrue(r.applied)
        assertEquals(listOf(MemberStatus.ACTIVE), statusOf(r.roster, X))
        assertEquals(listOf(MemberStatus.LEFT), statusOf(r.roster, Y))
        assertEquals("hw = max(hw, n)", 400L, r.state.hw)
    }

    @Test
    fun `an applied A sets activatedAtMs to n, activatedBy to AUTH and raises hw`() {
        val st = MaState(hw = 50, members = mapOf(X to MaEntry(activatedAtMs = 7, activatedBy = MaBy.UNAUTH)))
        val r = apply(st, base + row(X, MemberStatus.ACTIVE), ann(MaOp.A, X, 900, epoch = 5))
        assertTrue(r.applied)
        assertEquals(MaEntry(n = 900, op = MaOp.A, epoch = 5, activatedAtMs = 900, activatedBy = MaBy.AUTH), r.state.members[X])
        assertEquals(900L, r.state.hw)
    }

    @Test
    fun `the first applied GP sets rosterAnnounced and a dropped one does not`() {
        val dropped = apply(MaState(), base, ann(MaOp.A, SELF, 100), isSelf = true)
        assertFalse(dropped.state.rosterAnnounced)
        val first = apply(MaState(), base, ann(MaOp.R, Y, 100))
        assertTrue(first.state.rosterAnnounced)
    }

    @Test
    fun `an announce under another form of the address updates the existing row and one state entry`() {
        val r = apply(MaState(), base + row(X, MemberStatus.INVITED), ann(MaOp.A, X_ALIAS, 100))
        assertTrue(r.applied)
        assertEquals("no second row for the other form", base.size + 1, r.roster.size)
        assertEquals(MemberStatus.ACTIVE, r.roster.single { it.address == X }.status)
        assertEquals(setOf(X), r.state.members.keys)
    }

    private companion object {
        const val GID = "zgrp_0123456789abcdef01234567"
        val CREATOR = "u1" + "c".repeat(60)
        val SELF = "u1" + "s".repeat(60)
        val X = "u1" + "x".repeat(60)
        val X_ALIAS = "u1" + "w".repeat(60)
        val Y = "u1" + "y".repeat(60)
    }
}
