package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ZC-293 — a member who left and came back is announced back in (`A`) whatever the announcer's clock says.
 *
 * The removal's number `n` comes from the announcer's own clock ([nextAnnounceNumber]); the comeback's time
 * (`activatedAtMs`) and the leave's time (`leftAtMs`) are source times (the block time, or the member's relay time).
 * The creator's phone accepts the comeback when its time is newer than the leave, so the numbering pass must announce
 * it by the same rule. Comparing the comeback with `n` skips it whenever the announcer's clock runs ahead of a quick
 * rejoin: the row is ACTIVE on the creator's phone, but the other members keep the `R` and stop sending to the member.
 */
class ComebackIsAnnouncedWhateverTheAnnouncerClockTest {
    private val api = AnnounceApi

    @Test
    fun `a comeback newer than the leave is announced although the removal number is from a clock ahead`() {
        val removed = entry(activatedAtMs = COMEBACK_MS)
        val numbered = number(removed)
        val after = numbered.state.members.getValue(MEMBER)
        assertEquals("the member who came back is announced in", MaOp.A, after.op)
        assertTrue("the comeback's number ${after.n} must pass the removal's $REMOVAL_N", after.n > REMOVAL_N)
        assertEquals("the counter moves to the new number", after.n, numbered.counter)
        assertEquals("the leave's time is kept", LEFT_MS, after.leftAtMs)
        assertEquals("the comeback's time is kept", COMEBACK_MS, after.activatedAtMs)
    }

    @Test
    fun `an activation at the leave's own time is not a comeback and is not announced`() {
        val removed = entry(activatedAtMs = LEFT_MS)
        val numbered = number(removed)
        assertEquals(removed, numbered.state.members.getValue(MEMBER))
        assertEquals("no number is used", REMOVAL_N, numbered.counter)
    }

    @Test
    fun `an activation older than the leave is not a comeback and is not announced`() {
        val removed = entry(activatedAtMs = LEFT_MS - 60_000)
        val numbered = number(removed)
        assertEquals(removed, numbered.state.members.getValue(MEMBER))
        assertEquals("no number is used", REMOVAL_N, numbered.counter)
    }

    /** The member's record after its removal was numbered: `R` with a number from a clock five minutes ahead. */
    private fun entry(activatedAtMs: Long) =
        MaEntry(
            n = REMOVAL_N,
            op = MaOp.R,
            epoch = 1,
            activatedAtMs = activatedAtMs,
            activatedBy = MaBy.AUTH,
            leftAtMs = LEFT_MS,
            removedAtEpoch = 1,
            annForm = MEMBER,
            leftBy = MaBy.AUTH,
        )

    /** One numbering pass with the member's row ACTIVE again, a stored key and a secure link. */
    private fun number(entry: MaEntry): MaNumbering {
        val roster =
            listOf(
                GroupMember(ADMIN, joinedAt = Instant.EPOCH, status = MemberStatus.ACTIVE, isAdmin = true),
                GroupMember(MEMBER, joinedAt = Instant.EPOCH, status = MemberStatus.ACTIVE),
            )
        return api.assignAnnounceNumbers(
            "zgrp_0123456789abcdef01234567",
            MaState(members = mapOf(MEMBER to entry)),
            roster,
            REMOVAL_N,
            ANNOUNCER_NOW_MS,
            0,
            1,
            { it },
            { it == ADMIN },
            { "key-$it" },
            { true },
        )
    }

    private companion object {
        val ADMIN = "u1" + "a".repeat(60)
        val MEMBER = "u1" + "m".repeat(60)

        // Source times: the leave, and the accept that came 90 s later.
        const val LEFT_MS = 1_000_000_000L
        const val COMEBACK_MS = 1_000_090_000L

        // The announcer's clock runs five minutes ahead: the removal's number, and the clock at the next pass.
        const val REMOVAL_N = 1_000_300_000L
        const val ANNOUNCER_NOW_MS = 1_000_400_000L
    }
}
