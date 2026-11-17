package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KI-24 fixup, G-KI24-2 (train T4 cross review): the rules of the processed-KEX floor's ceiling, on the pure
 * [KexSeenLedger] and on the real ZchatPreferencesImpl store ([realPreferences]).
 *
 * The history pass reports the lowest mined inbound key offer the store has not recorded; the floor stays below it, and
 * only a complete, stable pass lets it rise, never above the highest mined height that pass read. An entry the floor may
 * not cover is kept past the cap (up to four times the cap) instead of being evicted uncovered, so it is not handled
 * again. Every txid is synthetic.
 */
class KexFloorCeilingTest {
    private companion object {
        const val CAP = 500L
        const val OFFER = 3_000_000L
        const val TIP = 3_100_000L
        const val TOP = 3_050_000L
    }

    private fun txid(i: Int) = "synthetic-ceiling-%04d".format(i)

    /** A ledger as ZchatPreferencesImpl loads it for a new or restored wallet: no stored ceiling, so 0. */
    private fun storeLedger(cap: Long = CAP) = KexSeenLedger { cap }.apply { load(null, storedFloor = 0L, storedCeiling = 0L) }

    @Test
    fun `before any complete pass the floor does not rise and nothing mined is evicted uncovered`() {
        val l = storeLedger()
        repeat(501) { i -> l.record(txid(i), OFFER + 1 + i, TIP) }

        assertEquals(0L, l.floorHeight)
        assertEquals("kept past the cap, since the floor may not cover it", 501, l.size)
        assertTrue(l.isProcessed(txid(0), OFFER + 1))
        assertFalse(l.isProcessed("synthetic-put-off-offer", OFFER))
    }

    @Test
    fun `an offer put off keeps the floor below it through the complete pass, and the next complete pass lets it rise`() {
        val l = storeLedger()
        l.updateCeiling(passComplete = false, passTopHeight = TOP, lowestUnrecordedOfferHeight = OFFER)
        assertEquals("an incomplete pass never raises the ceiling", 0L, l.ceilingHeight)

        repeat(501) { i -> l.record(txid(i), OFFER + 1 + i, TIP) }
        l.updateCeiling(passComplete = true, passTopHeight = TOP, lowestUnrecordedOfferHeight = OFFER)
        assertEquals("the complete pass that handles the offer still keeps the floor below it", OFFER - 1, l.ceilingHeight)
        l.record("synthetic-offer", OFFER, TIP)
        assertFalse("nothing above the ceiling was evicted", l.floorHeight >= OFFER)

        l.updateCeiling(passComplete = true, passTopHeight = TOP, lowestUnrecordedOfferHeight = null)
        assertEquals(TOP, l.ceilingHeight)
        l.record(txid(9_000), OFFER + 9_000, TIP)
        assertTrue("back within the cap", l.size <= CAP)
        assertTrue("the floor now covers what was evicted", l.floorHeight in (OFFER + 1)..TOP)
        assertTrue(l.isProcessed(txid(0), OFFER + 1))
        assertTrue(l.isProcessed("synthetic-offer", OFFER))
    }

    @Test
    fun `a complete pass never lifts the ceiling above the highest height it read, and an offer lowers it at once`() {
        val l = storeLedger()
        l.updateCeiling(passComplete = true, passTopHeight = TOP, lowestUnrecordedOfferHeight = null)
        assertEquals(TOP, l.ceilingHeight)

        l.updateCeiling(passComplete = false, passTopHeight = TOP + 10, lowestUnrecordedOfferHeight = OFFER)
        assertEquals(OFFER - 1, l.ceilingHeight)
        assertFalse("nothing changes on a pass with no offer and no complete read", l.updateCeiling(false, TOP, null))
        assertEquals(OFFER - 1, l.ceilingHeight)
    }

    @Test
    fun `past four times the cap the oldest go anyway, and the floor still stops at the ceiling`() {
        val l = storeLedger(cap = 10)
        l.updateCeiling(passComplete = true, passTopHeight = OFFER - 1, lowestUnrecordedOfferHeight = OFFER)
        repeat(41) { i -> l.record(txid(i), OFFER + 1 + i, TIP) }

        assertEquals(40, l.size)
        assertFalse(l.contains(txid(0)))
        assertTrue("the floor never passes the ceiling", l.floorHeight <= OFFER - 1)
        assertFalse(l.isProcessed("synthetic-put-off-offer", OFFER))
    }

    @Test
    fun `the entry just added is never the one evicted`() {
        val l = storeLedger(cap = 2)
        l.updateCeiling(passComplete = true, passTopHeight = OFFER + 5, lowestUnrecordedOfferHeight = null)
        l.record(txid(0), OFFER + 10, TIP)
        l.record(txid(1), OFFER + 11, TIP)
        l.record(txid(2), OFFER + 1, TIP)

        assertTrue(l.contains(txid(2)))
        assertEquals(3, l.size)
    }

    @Test
    fun `the real store keeps the ceiling in the KEX file, reads it back, and the wipe resets it`() {
        val (prefs, files) = realPreferences()
        prefs.limitKexFloor(passComplete = false, passTopHeight = TOP, lowestUnrecordedOfferHeight = OFFER)
        val file = files.getValue("kexSeenPrefs")
        assertEquals(OFFER - 1, file.getLong("floor_ceiling", -1L))

        for (i in 0..500) prefs.markKexTxProcessed(txid(i), OFFER + 1 + i, TIP)
        assertFalse(prefs.hasProcessedKexTx("synthetic-put-off-offer", OFFER))
        assertTrue(prefs.hasProcessedKexTx(txid(0), OFFER + 1))

        prefs.reloadWalletScopedStores()
        prefs.markKexTxProcessed(txid(600), OFFER + 600, TIP)
        assertFalse("the ceiling is read back with the set", prefs.hasProcessedKexTx("synthetic-put-off-offer", OFFER))

        prefs.clearAll()
        prefs.reloadWalletScopedStores()
        for (i in 0..500) prefs.markKexTxProcessed(txid(i), OFFER + 1 + i, TIP)
        assertFalse("after a wipe the floor waits for a complete pass", prefs.hasProcessedKexTx("synthetic-put-off-offer", OFFER))
        assertEquals(0L, files.getValue("kexSeenPrefs").getLong("floor_height", 0L))
    }
}
