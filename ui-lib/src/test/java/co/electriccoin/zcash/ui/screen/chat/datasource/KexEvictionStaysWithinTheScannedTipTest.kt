package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4c fixup, KI24-FX2-1 (train T4c cross review, REVIEW_CROSS.md; register ZC-305): the processed-KEX soft cap must
 * evict only an entry the floor can cover.
 *
 * At 6f0678116 the soft loop of [KexSeenLedger.record] evicts any mined entry at or below the ceiling. But the floor
 * rises only to min(highest evicted, fully scanned height, ceiling), and not at all with no scanned height known. The
 * ceiling comes from the highest height a "complete" pass read, and a complete pass does not need the scan to reach
 * the chain tip (the SDK scans recent blocks first after a gap), so the ceiling can sit above the scanned height. An
 * entry mined between the two is evicted with no floor under it; the next pass takes that KEX for unprocessed and
 * handles it again: a false "key changed" warning, or after a Reset an old key stored and a second KEXACK paid.
 *
 * Wanted: the soft loop evicts only an entry mined at or below both the ceiling and the scanned height, and nothing
 * while no scanned height is known; the hard cap (four times the cap) is unchanged. Every txid is synthetic.
 */
class KexEvictionStaysWithinTheScannedTipTest {
    private companion object {
        const val CAP = 500L
        const val TIP = 3_050_000L
        const val CEILING = 3_100_000L
        const val ABOVE_TIP = 3_080_000L
        const val BELOW_TIP = 3_000_000L
    }

    private fun txid(i: Int) = "synthetic-fx1-kex-%04d".format(i)

    /** A ledger as ZchatPreferencesImpl keeps it after a complete pass that read up to [CEILING]. */
    private fun ledgerWithCeiling(cap: Long): KexSeenLedger =
        KexSeenLedger { cap }.apply {
            load(null, storedFloor = 0L, storedCeiling = 0L)
            updateCeiling(passComplete = true, passTopHeight = CEILING, lowestUnrecordedOfferHeight = null)
        }

    @Test
    fun `an entry mined above the scanned height stays processed while the ceiling is above it`() {
        val l = ledgerWithCeiling(cap = 3)
        repeat(4) { i -> l.record(txid(i), ABOVE_TIP + i, scannedTip = TIP) }

        assertTrue("fixture: the floor may not pass the scanned height", l.floorHeight <= TIP)
        assertTrue(
            "the KEX mined above the scanned height was evicted with no floor under it: the next pass handles it again",
            l.isProcessed(txid(0), ABOVE_TIP),
        )
    }

    @Test
    fun `with no scanned height known nothing is evicted uncovered`() {
        val l = ledgerWithCeiling(cap = 3)
        repeat(4) { i -> l.record(txid(i), BELOW_TIP + i, scannedTip = null) }

        assertEquals("the floor does not move with no scanned height", 0L, l.floorHeight)
        assertTrue(
            "a KEX was evicted while no scanned height was known, so no floor covers it: the next pass handles it again",
            l.isProcessed(txid(0), BELOW_TIP),
        )
    }

    @Test
    fun `past the cap an entry the floor covers is evicted instead of an older one above the scanned height`() {
        val l = ledgerWithCeiling(cap = 3)
        l.record(txid(0), ABOVE_TIP, scannedTip = TIP)
        repeat(3) { i -> l.record(txid(1 + i), BELOW_TIP + i, scannedTip = TIP) }

        assertEquals("back within the cap", 3, l.size)
        assertTrue("the entry above the scanned height is kept", l.contains(txid(0)))
        assertFalse("the oldest covered entry left the set", l.contains(txid(1)))
        assertEquals("the floor covers the entry evicted", BELOW_TIP, l.floorHeight)
        assertTrue(l.isProcessed(txid(0), ABOVE_TIP))
        assertTrue(l.isProcessed(txid(1), BELOW_TIP))
    }

    /** Guard, GREEN before and after: entries at or below both heights are still evicted and covered. */
    @Test
    fun `guard - entries mined below the scanned height are evicted and covered by the floor`() {
        val l = ledgerWithCeiling(cap = 3)
        repeat(5) { i -> l.record(txid(i), BELOW_TIP + i, scannedTip = TIP) }

        assertEquals(3, l.size)
        assertEquals(BELOW_TIP + 1, l.floorHeight)
        assertTrue(l.isProcessed(txid(0), BELOW_TIP))
        assertTrue(l.isProcessed(txid(1), BELOW_TIP + 1))
    }

    /** Entries the floor cannot cover leave only through the hard cap (four times the cap), oldest first. */
    @Test
    fun `entries above the scanned height leave only past four times the cap and the floor never passes the tip`() {
        val l = ledgerWithCeiling(cap = 2)
        repeat(9) { i -> l.record(txid(i), ABOVE_TIP + i, scannedTip = TIP) }

        assertEquals("kept past the cap, since the floor cannot cover them", 8, l.size)
        assertFalse(l.contains(txid(0)))
        assertTrue(l.floorHeight <= TIP)
    }

    @Test
    fun `the real store keeps a KEX mined above the scanned height past the cap`() {
        val (prefs, _) = realPreferences()
        prefs.limitKexFloor(passComplete = true, passTopHeight = CEILING, lowestUnrecordedOfferHeight = null)
        for (i in 0..CAP.toInt()) prefs.markKexTxProcessed(txid(i), ABOVE_TIP + i, TIP)

        assertTrue(
            "the real store evicted a KEX mined above the scanned height with no floor under it",
            prefs.hasProcessedKexTx(txid(0), ABOVE_TIP),
        )
        assertFalse(
            "a KEX never recorded, mined above the scanned height, is still handled",
            prefs.hasProcessedKexTx(
                "synthetic-fx1-new",
                TIP + 1
            )
        )
    }
}
