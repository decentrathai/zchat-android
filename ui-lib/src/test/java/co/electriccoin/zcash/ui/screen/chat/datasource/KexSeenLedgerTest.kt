package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KI-24 part A (fix-all 2.13.0, train T4; SPECS §2.3 steps 1-5, §2.2 A4): the processed-KEX store's height floor, on
 * the pure [KexSeenLedger] that ZchatPreferencesImpl keeps for the "zchat_kex_seen" file. Real processing order, real
 * cap (500) unless a test says otherwise. Every txid is synthetic.
 */
class KexSeenLedgerTest {
    private companion object {
        const val CAP = 500L
        const val FIRST_HEIGHT = 3_000_000L
        const val TIP = 3_100_000L
    }

    private fun ledger(cap: Long = CAP) = KexSeenLedger { cap }

    private fun txid(i: Int) = "synthetic-kex-%04d".format(i)

    @Test
    fun `501 KEX mined below the scanned tip - the first leaves the set and the floor covers it`() {
        val l = ledger()
        repeat(501) { i -> l.record(txid(i), FIRST_HEIGHT + i, TIP) }

        assertEquals(500, l.size)
        assertFalse("the oldest txid left the set", l.contains(txid(0)))
        assertEquals("the floor is the evicted KEX's height", FIRST_HEIGHT, l.floorHeight)
        assertTrue("the evicted KEX still counts as processed at its mined height", l.isProcessed(txid(0), FIRST_HEIGHT))
        assertTrue("the others are in the set", l.isProcessed(txid(500), FIRST_HEIGHT + 500))
    }

    @Test
    fun `the floor never rises above the fully scanned height`() {
        // T4c fixup KI24-FX2-1: an entry mined above the scanned height is not evicted (the floor could not cover it,
        // and it would be handled again); entries at or below it are, and the floor stays at or below the tip.
        val above = ledger(cap = 3)
        repeat(5) { i -> above.record(txid(i), TIP + 1_000 + i, scannedTip = TIP) }
        assertEquals("nothing mined above the scanned height is evicted", 5, above.size)
        assertEquals(0L, above.floorHeight)
        assertTrue("each one stays processed", (0 until 5).all { i -> above.isProcessed(txid(i), TIP + 1_000 + i) })

        val l = ledger(cap = 3)
        repeat(3) { i -> l.record(txid(i), TIP - 10 + i, scannedTip = TIP) }
        repeat(2) { i -> l.record(txid(10 + i), TIP + 1_000 + i, scannedTip = TIP) }
        assertEquals(TIP - 9, l.floorHeight)
        assertTrue(l.floorHeight <= TIP)
        assertFalse("a never-seen KEX mined above the scanned height is handled", l.isProcessed("synthetic-new", TIP + 1))
        assertTrue(l.isProcessed("synthetic-old", TIP - 9))
        assertTrue(l.isProcessed(txid(10), TIP + 1_000))
    }

    @Test
    fun `with no scanned height known the floor does not move`() {
        val l = ledger(cap = 2)
        repeat(4) { i -> l.record(txid(i), FIRST_HEIGHT + i, scannedTip = null) }

        assertEquals(0L, l.floorHeight)
        // T4c fixup KI24-FX2-1: with no floor possible nothing is evicted before the hard cap, so none is handled again.
        assertEquals(4, l.size)
        assertTrue(l.isProcessed(txid(0), FIRST_HEIGHT))
        assertFalse("a KEX never recorded is not covered", l.isProcessed("synthetic-new", FIRST_HEIGHT))
    }

    @Test
    fun `an unmined KEX is never floored`() {
        val l = ledger(cap = 1)
        l.record(txid(0), FIRST_HEIGHT, TIP)
        l.record(txid(1), FIRST_HEIGHT + 1, TIP)

        assertEquals(FIRST_HEIGHT, l.floorHeight)
        assertFalse(l.isProcessed("synthetic-mempool", null))
    }

    @Test
    fun `a KEX mined above the floor that was never recorded is not processed`() {
        val l = ledger(cap = 1)
        l.record(txid(0), FIRST_HEIGHT, TIP)
        l.record(txid(1), FIRST_HEIGHT + 10, TIP)

        assertFalse(l.isProcessed("synthetic-historic", FIRST_HEIGHT + 5))
        assertTrue(l.isProcessed("synthetic-at-floor", FIRST_HEIGHT))
    }

    @Test
    fun `the floor never decreases`() {
        val l = ledger(cap = 1)
        l.record(txid(0), FIRST_HEIGHT + 100, TIP)
        l.record(txid(1), FIRST_HEIGHT + 200, TIP)
        assertEquals(FIRST_HEIGHT + 100, l.floorHeight)
        // A KEX processed out of order (mined lower than the one before it) is evicted later: the floor stays.
        l.record(txid(2), FIRST_HEIGHT + 150, TIP)
        assertEquals(FIRST_HEIGHT + 200, l.floorHeight)
        l.record(txid(3), null, TIP)
        assertEquals("evicting the lower entry must not lower the floor", FIRST_HEIGHT + 200, l.floorHeight)
    }

    @Test
    fun `a txid the floor already covers is not added again, so the set does not churn`() {
        val l = ledger(cap = 1)
        l.record(txid(0), FIRST_HEIGHT, TIP)
        l.record(txid(1), FIRST_HEIGHT + 1, TIP)

        assertEquals(KexSeenLedger.Change.NONE, l.record(txid(0), FIRST_HEIGHT, TIP))
        assertTrue("the newer entry stays", l.contains(txid(1)))
        assertEquals(1, l.size)
    }

    @Test
    fun `a legacy entry with no height gets its height on the first pass that sees it mined (A4)`() {
        val l = ledger()
        l.load("${txid(0)}\n${txid(1)}", storedFloor = 0L)
        assertNull(l.heightOf(txid(0)))

        assertEquals(KexSeenLedger.Change.HEIGHT_FILLED, l.record(txid(0), FIRST_HEIGHT, TIP))
        assertEquals(FIRST_HEIGHT, l.heightOf(txid(0)))
        assertEquals("seen again: nothing more to write", KexSeenLedger.Change.NONE, l.record(txid(0), FIRST_HEIGHT, TIP))
    }

    @Test
    fun `a backfilled legacy entry raises the floor when it is evicted, one never backfilled does not`() {
        val filled = ledger(cap = 2)
        filled.load("${txid(0)}\n${txid(1)}", storedFloor = 0L)
        filled.record(txid(0), FIRST_HEIGHT, TIP)
        filled.record(txid(2), FIRST_HEIGHT + 2, TIP)
        assertEquals(FIRST_HEIGHT, filled.floorHeight)

        val legacy = ledger(cap = 2)
        legacy.load("${txid(0)}\n${txid(1)}", storedFloor = 0L)
        legacy.record(txid(2), FIRST_HEIGHT + 2, TIP)
        assertEquals("an evicted entry with no height gives the floor nothing", 0L, legacy.floorHeight)
    }

    @Test
    fun `the stored form keeps heights and the floor and still reads the old one-txid-per-line form`() {
        val l = ledger()
        l.record(txid(0), FIRST_HEIGHT, TIP)
        l.record(txid(1), null, TIP)
        val stored = l.serialize()
        assertEquals("${txid(0)}:$FIRST_HEIGHT\n${txid(1)}", stored)

        val reloaded = ledger()
        reloaded.load(stored, storedFloor = 42L)
        assertEquals(FIRST_HEIGHT, reloaded.heightOf(txid(0)))
        assertNull(reloaded.heightOf(txid(1)))
        assertTrue(reloaded.contains(txid(1)))
        assertEquals(42L, reloaded.floorHeight)
    }

    @Test
    fun `clear empties the set and the floor, as the destroy and reset wipe does`() {
        val l = ledger(cap = 1)
        l.record(txid(0), FIRST_HEIGHT, TIP)
        l.record(txid(1), FIRST_HEIGHT + 1, TIP)
        l.clear()

        assertEquals(0, l.size)
        assertEquals(0L, l.floorHeight)
        assertFalse(l.isProcessed(txid(0), FIRST_HEIGHT))
    }
}
