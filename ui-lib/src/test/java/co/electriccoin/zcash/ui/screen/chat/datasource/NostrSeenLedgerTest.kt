package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KI-24 part B (fix-all 2.13.0, train T4; SPECS §2.3 steps 10-11, §2.2 B6): the relay seen list kept by time, on the
 * pure [NostrSeenLedger] that ZchatPreferencesImpl keeps for the "zchat_nostr_seen" file. Every id is synthetic.
 */
class NostrSeenLedgerTest {
    private companion object {
        const val NOW = 1_790_000_000L
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
        const val CAP = 20_000L
        const val MARGIN = NostrSeenLedger.FLOOR_MARGIN_SEC
    }

    private fun ledger(cap: Long = CAP) = NostrSeenLedger { cap }

    private fun id(i: Int) = "synthetic-evt-%06d".format(i)

    @Test
    fun `2001 events inside the window - the first is still seen (the 2000 cap is gone)`() {
        val l = ledger()
        repeat(2_001) { i -> l.record(id(i), NOW - HOUR + i, lowestSinceSec = NOW - 3 * DAY, nowSec = NOW) }

        assertEquals(2_001, l.size)
        assertTrue(l.isSeen(id(0), NOW - HOUR))
        assertEquals(0L, l.floorSec)
    }

    @Test
    fun `the old newline-only list loads with the load time, so nothing seen becomes unseen (B6)`() {
        val l = ledger()
        l.load("${id(0)}\n${id(1)}\n", storedFloor = 0L, loadTimeSec = NOW)

        assertTrue(l.isSeen(id(0), null))
        assertTrue(l.isSeen(id(1), NOW - 20 * DAY))
        assertEquals(NOW, l.timeOf(id(0)))
        assertEquals("rewritten in the new form", "${id(0)}:$NOW\n${id(1)}:$NOW", l.serialize())
    }

    @Test
    fun `migrated entries stay until the since line passes their load time`() {
        val l = ledger()
        l.load(id(0), storedFloor = 0L, loadTimeSec = NOW)
        // The since line can only be at or below now - 49 h, so a migrated entry outlives any replay of its event.
        repeat(NostrSeenLedger.PRUNE_STEP) { i -> l.record(id(i + 1), NOW, lowestSinceSec = NOW - MARGIN, nowSec = NOW) }
        assertTrue(l.contains(id(0)))
    }

    @Test
    fun `the new stored form round-trips with the floor`() {
        val l = ledger()
        l.record(id(0), NOW - 5, lowestSinceSec = null, nowSec = NOW)
        val reloaded = ledger()
        reloaded.load(l.serialize(), storedFloor = NOW - 10 * DAY, loadTimeSec = NOW + 999)

        assertEquals(NOW - 5, reloaded.timeOf(id(0)))
        assertEquals(NOW - 10 * DAY, reloaded.floorSec)
    }

    @Test
    fun `entries older than the lowest since leave, and the floor covers them`() {
        val l = ledger()
        val since = NOW - 3 * DAY
        l.record(id(0), since - DAY, lowestSinceSec = null, nowSec = NOW)
        repeat(NostrSeenLedger.PRUNE_STEP) { i -> l.record(id(i + 1), NOW - HOUR, lowestSinceSec = since, nowSec = NOW) }

        assertFalse("an entry older than every relay's since can never be served again", l.contains(id(0)))
        assertEquals(since - DAY, l.floorSec)
        assertTrue("its event still counts as seen by its wrap time", l.isSeen(id(0), since - DAY))
    }

    @Test
    fun `with some relay lacking a since nothing leaves by time`() {
        val l = ledger()
        l.record(id(0), NOW - 25 * DAY, lowestSinceSec = null, nowSec = NOW)
        repeat(NostrSeenLedger.PRUNE_STEP * 2) { i -> l.record(id(i + 1), NOW, lowestSinceSec = null, nowSec = NOW) }

        assertTrue(l.contains(id(0)))
        assertEquals(0L, l.floorSec)
    }

    @Test
    fun `the safety cap forces the oldest out and raises the floor, never to the lowest since nor above now - 49 h`() {
        val l = ledger(cap = 10)
        val since = NOW - 3 * DAY
        repeat(11) { i -> l.record(id(i), NOW - i, lowestSinceSec = since, nowSec = NOW) }

        assertTrue("the list is back under the cap", l.size <= 10)
        assertFalse("the oldest by wrap time left first", l.contains(id(10)))
        assertTrue("the newest stays", l.contains(id(0)))
        assertEquals("young entries were forced out, so the floor stops just below the lowest since", since - 1, l.floorSec)
        assertTrue(l.floorSec <= NOW - MARGIN)
    }

    @Test
    fun `after the cap raised the floor, a fresh wrap back-dated 47 h is not seen (B3)`() {
        val l = ledger(cap = 10)
        val since = NOW - 3 * DAY
        repeat(50) { i -> l.record(id(i), NOW - i, lowestSinceSec = since, nowSec = NOW) }

        assertFalse(l.isSeen("synthetic-fresh-jittered", NOW - 47 * HOUR))
        assertTrue("a wrap older than the floor counts as seen", l.isSeen("synthetic-ancient", since - 1))
    }

    @Test
    fun `the floor never decreases`() {
        val l = ledger(cap = 2)
        repeat(3) { i -> l.record(id(i), NOW - 10 * DAY + i, lowestSinceSec = NOW - 30 * DAY, nowSec = NOW) }
        val first = l.floorSec
        assertTrue(first > 0)
        l.record(id(9), NOW - 20 * DAY, lowestSinceSec = NOW - 30 * DAY, nowSec = NOW)
        assertTrue(l.floorSec >= first)
    }

    @Test
    fun `an id marked again keeps its first time, and a removed id is unseen again (deferred handling)`() {
        val l = ledger()
        l.record(id(0), NOW - 7, lowestSinceSec = null, nowSec = NOW)
        assertFalse(l.record(id(0), NOW, lowestSinceSec = null, nowSec = NOW).changed)
        assertEquals(NOW - 7, l.timeOf(id(0)))

        assertTrue(l.remove(id(0)))
        assertFalse(l.isSeen(id(0), NOW - 7))
        assertNull(l.timeOf(id(0)))
    }

    @Test
    fun `clear empties the list and the floor, as the destroy and reset wipe does`() {
        val l = ledger(cap = 1)
        repeat(3) { i -> l.record(id(i), NOW - 10 * DAY + i, lowestSinceSec = null, nowSec = NOW) }
        l.clear()

        assertEquals(0, l.size)
        assertEquals(0L, l.floorSec)
    }
}
