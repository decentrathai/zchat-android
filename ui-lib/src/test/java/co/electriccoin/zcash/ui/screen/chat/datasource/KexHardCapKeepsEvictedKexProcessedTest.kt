package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-074 (fix-all 2.13.0, train T5b; high-items SPECS §3.2, §3.5 K1): the processed-KEX set past its hard cap.
 *
 * Past the cap the soft loop of [KexSeenLedger.record] evicts only entries mined at or below the ceiling (KI24-FX2), and
 * the floor never passes the ceiling. While the ceiling stays pinned (an offer put off, an inbound memo that keeps failing,
 * an own message the pass cannot tie to a chat), every new KEX/KEXACK mined above it stays in the set, until four times
 * the cap: there the oldest go whatever their height, and an entry mined above the ceiling, or with no height, is then
 * neither in the set nor under the floor. The next pass reads it, finds it unprocessed and handles it again: a false "key
 * changed" for a contact whose key changed since, or, after a Reset, the old key stored and a KEXACK (about 17,000
 * zatoshi) paid for it. Re-handling marks it again as the newest entry, and the hard cap then drops the next oldest
 * uncovered one: the churn the set was built to stop.
 *
 * Wanted: an entry the hard cap removes stays processed (within a stated bound), and marking it again does not push
 * another one out. Every txid is synthetic (64 hex characters, distinct in their first 16).
 */
class KexHardCapKeepsEvictedKexProcessedTest {
    private companion object {
        const val OFFER = 3_000_000L
        const val TIP = 3_100_000L
    }

    private fun txid(i: Int) = "%016x".format(0x7400_0000_0000L + i) + "c0".repeat(24)

    /** A ledger as ZchatPreferencesImpl loads it, with the ceiling pinned just below a put-off offer. */
    private fun pinnedLedger(cap: Long): KexSeenLedger =
        KexSeenLedger { cap }.apply {
            load(null, storedFloor = 0L, storedCeiling = 0L)
            updateCeiling(passComplete = true, passTopHeight = OFFER - 1, lowestUnrecordedOfferHeight = OFFER)
        }

    @Test
    fun `an entry the hard cap removes above a pinned ceiling stays processed at its height`() {
        val l = pinnedLedger(cap = 10)
        repeat(41) { i -> l.record(txid(i), OFFER + 1 + i, TIP) }
        assertEquals("fixture: the ceiling is pinned below the offer", OFFER - 1, l.ceilingHeight)
        assertEquals("fixture: the hard cap (four times 10) holds", 40, l.size)

        assertTrue(
            "the hard cap dropped a KEX mined above the ceiling, which the floor may not cover: the next pass handles it again",
            l.isProcessed(txid(0), OFFER + 1),
        )
        assertTrue("the floor still stops at the ceiling", l.floorHeight <= OFFER - 1)
        assertFalse("a KEX never seen is still new", l.isProcessed(txid(9_000), OFFER + 1))
    }

    @Test
    fun `a heightless entry the hard cap removes stays processed unmined and once it is mined`() {
        val l = pinnedLedger(cap = 10)
        l.record(txid(0), minedHeight = null, scannedTip = TIP)
        repeat(40) { i -> l.record(txid(1 + i), OFFER + 1 + i, TIP) }
        assertEquals(40, l.size)

        assertTrue("the KEX handled from the mempool is handled again while unmined", l.isProcessed(txid(0), null))
        assertTrue("the KEX handled from the mempool is handled again once mined", l.isProcessed(txid(0), OFFER + 500))
    }

    @Test
    fun `marking the removed entry again pushes no other entry out (no churn)`() {
        val l = pinnedLedger(cap = 10)
        repeat(41) { i -> l.record(txid(i), OFFER + 1 + i, TIP) }

        // The next pass reads the removed KEX again and marks it, as handleKEXMessage does before handling.
        l.record(txid(0), OFFER + 1, TIP)

        assertEquals("the set size is unchanged", 40, l.size)
        for (i in 0..40) {
            assertTrue("entry $i was pushed out by marking the removed one again", l.isProcessed(txid(i), OFFER + 1 + i))
        }
    }

    @Test
    fun `the real store keeps the removed entry processed across a reload, and the wipe clears it`() {
        val (prefs, _) = realPreferences()
        prefs.limitKexFloor(passComplete = true, passTopHeight = OFFER - 1, lowestUnrecordedOfferHeight = OFFER)
        for (i in 0..2_000) prefs.markKexTxProcessed(txid(i), OFFER + 1 + i, TIP)

        assertTrue(
            "past 2,000 KEX/KEXACK transactions above a pinned ceiling the store forgets the first one",
            prefs.hasProcessedKexTx(txid(0), OFFER + 1),
        )
        prefs.reloadWalletScopedStores()
        assertTrue("read back after a wallet-scope reload", prefs.hasProcessedKexTx(txid(0), OFFER + 1))
        assertTrue(prefs.hasProcessedKexTx(txid(2_000), OFFER + 2_001))

        prefs.clearAll()
        assertFalse("a reset handles history again", prefs.hasProcessedKexTx(txid(0), OFFER + 1))
    }
}
