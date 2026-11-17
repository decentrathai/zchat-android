package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KI-24 fixup, G-KI24-1 (train T4 cross review): the seen list's safety cap must never make a message that was never
 * handled count as seen.
 *
 * [NostrSeenLedger.isSeen] counts every wrap created at or before the floor as seen, even when its id is not in the
 * list. A relay serves every wrap at or after its `since`, so the floor may only cover times no relay can serve again:
 * below the lowest per-relay `since`. At 1ca5f2e3c the cap branch raises the floor to the newest wrap time it forced out,
 * clamped only to now − 49 h, and also while a relay has no `since` yet. A phone that was offline for days (its relays'
 * `since` days old) that marks more than 20,000 handled wraps on reconnect (group traffic, or a stranger's flood of
 * contact requests, each marked when handled) then drops a real contact's DM or contact request made three days ago as
 * "seen", before it is stored, and drops every replay of it the same way: the message is lost.
 *
 * Wanted: a cap eviction raises the floor to at most one second below the lowest `since`, and not at all while some
 * relay has no `since`. It may then cause a duplicate, never a loss. A small cap stands for the release cap of 20,000.
 * Every id is synthetic.
 */
class CapFloorNeverPassesTheLowestSinceTest {
    private companion object {
        const val NOW = 1_790_000_000L
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
        const val CAP = 100L
    }

    private fun id(i: Int) = "synthetic-g1-evt-%05d".format(i)

    /** A burst of [count] handled wraps made in the last hour, as the flood marks them. */
    private fun NostrSeenLedger.flood(
        count: Int,
        lowestSinceSec: Long?,
    ) = repeat(count) { i -> record(id(i), NOW - HOUR + i % 60, lowestSinceSec, NOW) }

    @Test
    fun `after a cap eviction a three-day-old message from a relay whose since is six days old is not seen`() {
        val l = NostrSeenLedger { CAP }
        val lowestSince = NOW - 6 * DAY
        l.flood(CAP.toInt() + 1, lowestSince)

        assertTrue("fixture: the cap forced entries out", l.size <= CAP)
        assertFalse(
            "a DM made three days ago, still at or after every relay's since, counts as seen after a cap eviction: " +
                "dispatch drops it before it is stored, and drops every replay the same way",
            l.isSeen("synthetic-g1-never-handled-dm", NOW - 3 * DAY),
        )
        assertTrue("the floor stays below the lowest since", l.floorSec < lowestSince)
    }

    @Test
    fun `a wrap exactly at the lowest since is still served by that relay, so it is not seen`() {
        val l = NostrSeenLedger { CAP }
        val lowestSince = NOW - 6 * DAY
        l.flood(CAP.toInt() + 1, lowestSince)

        assertFalse(l.isSeen("synthetic-g1-at-since", lowestSince))
    }

    @Test
    fun `while some relay has no since a cap eviction raises no floor`() {
        val l = NostrSeenLedger { CAP }
        l.flood(CAP.toInt() + 1, lowestSinceSec = null)

        assertTrue("fixture: the cap forced entries out", l.size <= CAP)
        assertEquals("a relay with no since serves everything it holds, so nothing may be floored", 0L, l.floorSec)
        assertFalse(l.isSeen("synthetic-g1-never-handled-request", NOW - 3 * DAY))
    }

    /** Control, GREEN before and after: an id still in the list is seen, whatever its time. */
    @Test
    fun `control - a handled wrap still in the list is seen`() {
        val l = NostrSeenLedger { CAP }
        l.flood(CAP.toInt() + 1, NOW - 6 * DAY)

        assertTrue(l.isSeen(id(CAP.toInt()), NOW - HOUR))
    }
}
