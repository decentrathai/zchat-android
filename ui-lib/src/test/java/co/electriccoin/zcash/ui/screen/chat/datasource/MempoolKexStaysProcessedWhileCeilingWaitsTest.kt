package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KI-24 fixup, G-FX-2 (train T4 cross review of the fixup, REVIEW_CROSS_FIXUP.md): while the ceiling keeps mined
 * entries in the processed-KEX set, the soft cap must not evict an entry that has no mined height.
 *
 * At c194f764a the soft loop of [KexSeenLedger.record] evicts an entry with no height ahead of every mined entry the
 * ceiling protects, and such an eviction never raises the floor ([KexSeenLedger.isProcessed] covers only a mined height
 * at or below the floor). During a restore or a first sync the ceiling is 0, so a set that holds 500 mined KEX/KEXACK
 * txids evicts a KEX just handled from the mempool (marked with no height) at the very next record. The next history
 * pass then takes that KEX for unprocessed and handles it again; with a Reset Encryption in that window the old key is
 * stored as the contact's and a KEXACK (about 17,000 zatoshi) is paid for it. A legacy 2.12.x line with no height is
 * evicted the same way during the first passes after the upgrade.
 *
 * Wanted: in the soft loop only an entry mined at or below the ceiling is evicted (or any entry on a bare ledger with no
 * ceiling); an entry with no height leaves only through the hard cap, in insertion order, as before the fixup. Every
 * txid is synthetic; the cap is the real one (500).
 */
class MempoolKexStaysProcessedWhileCeilingWaitsTest {
    private companion object {
        const val CAP = 500L
        const val FIRST_HEIGHT = 3_000_000L
        const val TIP = 3_100_000L
    }

    private fun txid(i: Int) = "synthetic-fx2-mined-%04d".format(i)

    /** A ledger as ZchatPreferencesImpl loads it for a new or restored wallet: no stored ceiling, so 0. */
    private fun storeLedger(stored: String? = null) =
        KexSeenLedger { CAP }.apply { load(stored, storedFloor = 0L, storedCeiling = 0L) }

    @Test
    fun `a KEX handled from the mempool while the ceiling is 0 stays processed after the next record`() {
        val l = storeLedger()
        repeat(CAP.toInt()) { i -> l.record(txid(i), FIRST_HEIGHT + i, TIP) }
        assertEquals("fixture: 500 mined entries, none evictable under ceiling 0", CAP.toInt(), l.size)

        l.record("synthetic-fx2-mempool-kex", minedHeight = null, scannedTip = TIP)
        l.record("synthetic-fx2-next", FIRST_HEIGHT + 600, TIP)

        assertEquals("the floor cannot rise while the ceiling is 0", 0L, l.floorHeight)
        assertTrue(
            "the KEX handled from the mempool was evicted ahead of the mined entries the ceiling keeps: " +
                "the next pass handles it again while it is still unmined",
            l.isProcessed("synthetic-fx2-mempool-kex", minedHeight = null),
        )
        assertTrue(
            "the KEX handled from the mempool is handled again once it is mined above the floor",
            l.isProcessed("synthetic-fx2-mempool-kex", minedHeight = FIRST_HEIGHT + 700),
        )
    }

    @Test
    fun `a legacy line with no height is not evicted ahead of mined entries while the ceiling is 0`() {
        val legacy = "synthetic-fx2-legacy-kex"
        val mined = (0 until CAP.toInt() - 1).map { "${txid(it)}:${FIRST_HEIGHT + it}" }
        val l = storeLedger(stored = (listOf(legacy) + mined).joinToString("\n"))
        assertEquals(CAP.toInt(), l.size)

        l.record("synthetic-fx2-next", FIRST_HEIGHT + 600, TIP)

        assertTrue(
            "the 2.12.x line with no height left the set ahead of the mined entries, and no floor covers it",
            l.isProcessed(legacy, minedHeight = FIRST_HEIGHT - 10),
        )
    }

    /** Guard, GREEN before and after: past four times the cap the oldest go anyway, heightless or not. */
    @Test
    fun `guard - past four times the cap an entry with no height leaves in insertion order`() {
        val l = KexSeenLedger { 2L }.apply { load(null, storedFloor = 0L, storedCeiling = 0L) }
        l.record("synthetic-fx2-first-heightless", minedHeight = null, scannedTip = TIP)
        repeat(8) { i -> l.record(txid(i), FIRST_HEIGHT + i, TIP) }

        assertEquals(8, l.size)
        assertTrue("the oldest (heightless) entry went through the hard cap", !l.contains("synthetic-fx2-first-heightless"))
        assertEquals("the floor still stops at the ceiling", 0L, l.floorHeight)
    }

    /** With the ceiling up, mined entries at or below it are evicted and covered, still never the one with no height. */
    @Test
    fun `with the ceiling up the soft cap evicts covered mined entries, not a KEX that has no height yet`() {
        val l = storeLedger()
        l.updateCeiling(passComplete = true, passTopHeight = TIP, lowestUnrecordedOfferHeight = null)
        l.record("synthetic-fx2-mempool-kex", minedHeight = null, scannedTip = TIP)
        repeat(CAP.toInt()) { i -> l.record(txid(i), FIRST_HEIGHT + i, TIP) }

        assertTrue(
            "the KEX with no height (still unmined) was evicted and no floor covers it: the next pass handles it again",
            l.isProcessed("synthetic-fx2-mempool-kex", minedHeight = null),
        )
        assertTrue("the floor covers the mined entry evicted instead", l.floorHeight >= FIRST_HEIGHT)
        assertTrue(l.isProcessed(txid(0), FIRST_HEIGHT))
    }
}
