package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-074 (fix-all 2.13.0, train T5b; high-items SPECS §3.4 N2, §3.6): the rules of the processed-KEX overflow on the
 * pure [KexSeenLedger] and on the real ZchatPreferencesImpl store ([realPreferences]): what moves there, when a line
 * leaves (the floor covers it), its bound (8 times the cap; past it the oldest line goes: the declared residual), its
 * stored form, and a file with no overflow (2.12.x, or written before ZC-074). Every txid is synthetic.
 */
class KexOverflowTest {
    private companion object {
        const val OFFER = 3_000_000L
        const val TIP = 3_100_000L
        const val TOP = 3_050_000L
    }

    private fun txid(i: Int) = "%016x".format(0x7500_0000_0000L + i) + "d1".repeat(24)

    private fun pinnedLedger(cap: Long): KexSeenLedger =
        KexSeenLedger { cap }.apply {
            load(null, storedFloor = 0L, storedCeiling = 0L)
            updateCeiling(passComplete = true, passTopHeight = OFFER - 1, lowestUnrecordedOfferHeight = OFFER)
        }

    @Test
    fun `the hard cap moves an uncovered entry to the overflow and reports it`() {
        val l = pinnedLedger(cap = 2)
        val changes = (0..9).map { i -> l.record(txid(i), OFFER + 1 + i, TIP) }

        assertEquals("the first removal lifts the floor to the ceiling, as before", KexSeenLedger.Change.FLOOR_RAISED, changes[8])
        assertEquals(KexSeenLedger.Change.OVERFLOWED, changes[9])
        assertEquals(8, l.size)
        assertEquals(2, l.overflowSize)
        assertFalse("contains answers for the set alone", l.contains(txid(0)))
        assertNull(l.heightOf(txid(0)))
        assertTrue(l.isProcessed(txid(0), OFFER + 1))
        assertTrue(l.isProcessed(txid(1), OFFER + 2))
        assertEquals("the floor still stops at the ceiling", OFFER - 1, l.floorHeight)
    }

    @Test
    fun `an entry the floor will cover leaves the set as before and is not kept in the overflow`() {
        val l = KexSeenLedger { 2L }.apply { load(null, storedFloor = 0L, storedCeiling = 0L) }
        l.updateCeiling(passComplete = true, passTopHeight = TOP, lowestUnrecordedOfferHeight = null)
        repeat(9) { i -> l.record(txid(i), OFFER + 1 + i, TIP) }

        assertEquals(0, l.overflowSize)
        assertTrue(l.floorHeight >= OFFER + 1)
        assertTrue(l.isProcessed(txid(0), OFFER + 1))
    }

    @Test
    fun `a line leaves the overflow once the floor covers its height, and stays processed`() {
        val l = pinnedLedger(cap = 2)
        repeat(9) { i -> l.record(txid(i), OFFER + 1 + i, TIP) }
        assertEquals(1, l.overflowSize)

        l.updateCeiling(passComplete = true, passTopHeight = TOP, lowestUnrecordedOfferHeight = null)
        assertEquals(KexSeenLedger.Change.FLOOR_RAISED, l.record(txid(100), OFFER + 100, TIP))

        assertEquals("covered by the floor", 0, l.overflowSize)
        assertTrue(l.floorHeight >= OFFER + 1)
        assertTrue(l.isProcessed(txid(0), OFFER + 1))
    }

    @Test
    fun `a heightless line gets its height when the pass marks it mined, without coming back into the set`() {
        val l = pinnedLedger(cap = 2)
        l.record(txid(0), minedHeight = null, scannedTip = TIP)
        repeat(8) { i -> l.record(txid(1 + i), OFFER + 1 + i, TIP) }
        assertEquals(1, l.overflowSize)

        assertEquals(KexSeenLedger.Change.HEIGHT_FILLED, l.record(txid(0), OFFER + 50, TIP))
        assertEquals(KexSeenLedger.Change.NONE, l.record(txid(0), OFFER + 50, TIP))
        assertEquals(8, l.size)
        assertFalse(l.contains(txid(0)))
        assertTrue(l.serializeOverflow().endsWith(":${OFFER + 50}"))
    }

    @Test
    fun `past eight times the cap the oldest overflow line goes (the declared residual)`() {
        val l = pinnedLedger(cap = 1)
        repeat(13) { i -> l.record(txid(i), OFFER + 1 + i, TIP) }

        assertEquals(4, l.size)
        assertEquals(KexSeenLedger.OVERFLOW_FACTOR.toInt(), l.overflowSize)
        assertFalse("the oldest line went uncovered", l.isProcessed(txid(0), OFFER + 1))
        for (i in 1..12) assertTrue(l.isProcessed(txid(i), OFFER + 1 + i))
    }

    @Test
    fun `the overflow keeps a txid's first 16 hex characters and reads back from its stored form`() {
        val l = pinnedLedger(cap = 2)
        l.record(txid(0), minedHeight = null, scannedTip = TIP)
        repeat(9) { i -> l.record(txid(1 + i), OFFER + 1 + i, TIP) }
        val stored = l.serializeOverflow()
        assertEquals(listOf(txid(0).take(16), "${txid(1).take(16)}:${OFFER + 1}"), stored.split('\n'))

        val back = KexSeenLedger { 2L }
        back.load(l.serialize(), l.floorHeight, l.ceilingHeight, stored)
        assertEquals(stored, back.serializeOverflow())
        assertTrue(back.isProcessed(txid(0), null))
        assertTrue(back.isProcessed(txid(1), OFFER + 1))
        assertFalse(back.isProcessed(txid(500), OFFER + 1))
        assertEquals("a non-hex id is kept whole", "synthetic-id", KexSeenLedger.overflowKey("synthetic-id"))
    }

    @Test
    fun `a stored line the floor already covers is dropped on load, and no overflow loads empty`() {
        val l = KexSeenLedger { 2L }
        val covered = "${txid(1).take(16)}:${OFFER + 1}"
        l.load("${txid(5)}:${OFFER + 5}", storedFloor = OFFER + 10, storedCeiling = TOP, storedOverflow = covered)
        assertEquals(0, l.overflowSize)

        val old = KexSeenLedger { 2L }
        old.load("${txid(5)}:${OFFER + 5}\n${txid(6)}", storedFloor = 0L, storedCeiling = 0L)
        assertEquals(0, old.overflowSize)
        assertEquals("", old.serializeOverflow())
        assertTrue(old.isProcessed(txid(6), null))
    }

    @Test
    fun `the real store writes the overflow only when it holds lines, reads a file without it, and the wipe clears it`() {
        val (prefs, files) = realPreferences()
        val file = files.getValue("kexSeenPrefs")
        file
            .edit()
            .putString("ids", "${txid(9_000)}:${OFFER + 9_000}\nsynthetic-legacy-line")
            .putLong("floor_height", 0L)
            .putLong("floor_ceiling", OFFER - 1)
            .commit()
        prefs.reloadWalletScopedStores()
        prefs.markKexTxProcessed(txid(9_001), OFFER + 9_001, TIP)
        assertFalse("nothing overflowed: no key written", file.contains("overflow"))
        assertTrue(prefs.hasProcessedKexTx("synthetic-legacy-line", null))

        for (i in 0..2_000) prefs.markKexTxProcessed(txid(i), OFFER + 1 + i, TIP)
        val stored = file.getString("overflow", null)
        assertTrue("the removed entries are written to the same file", !stored.isNullOrEmpty())
        prefs.reloadWalletScopedStores()
        assertTrue(prefs.hasProcessedKexTx(txid(0), OFFER + 1))
        assertTrue(prefs.hasProcessedKexTx(txid(9_000), OFFER + 9_000))
        assertTrue("a heightless 2.12.x line the hard cap removed stays processed", prefs.hasProcessedKexTx("synthetic-legacy-line", null))

        prefs.clearAll()
        assertFalse(file.contains("overflow"))
        prefs.reloadWalletScopedStores()
        assertFalse(prefs.hasProcessedKexTx(txid(0), OFFER + 1))
    }
}
