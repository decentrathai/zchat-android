package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * KI24-D-ALT (W-12 part A; KI-24 spec §2.5 (d)), the on-chain half: a contact's key offer (KEX) mined while the
 * receiver was offline for 50 hours is handled exactly once after the restart, with the processed-KEX floor and its
 * ceiling (G-KI24-2, KI24-FX2-1) in play. A GUARD: it passes at the KI-24 code with the KI24-FX2 fixups.
 *
 * The real ZchatPreferencesImpl over in-memory files ([realPreferences]; release cap 500). Blocks of 75 s, so 50 hours
 * are 2,400 blocks; H0 is the fully scanned height when the receiver went offline. Each history pass is played as
 * ChatViewModel plays it: it reports the lowest unrecorded mined offer first (limitKexFloor), then handles every
 * transaction the store does not count as processed and marks it with its mined height and the fully scanned height.
 * During the catch-up after the restart the pass is not complete and the offer is put off (not marked), as a restore
 * or a first sync does; 600 other handled transactions mined above the offer (other contacts' KEX and KEXACK) are
 * recorded meanwhile, so that without the ceiling their evictions would carry the floor past the offer. No entry is
 * heightless, so this does not depend on G-FX-2. Every txid is synthetic.
 */
class OfflineFiftyHoursKexOfferIsHandledOnceTest {
    private companion object {
        const val CAP = 500
        const val H0 = 3_200_000L
        const val GAP_BLOCKS = 2_400L
        const val OFFER_AT = H0 + 48
        const val OTHERS = 600
        const val FIRST_OTHER = H0 + 49
        const val LAST_OTHER = H0 + 2_352
    }

    private data class Tx(
        val id: String,
        val height: Long,
    )

    private val tag = "synthetic-kex-offline-${UUID.randomUUID()}"
    private val made = realPreferences()
    private val prefs = made.first
    private val kexFile = made.second.getValue("kexSeenPrefs")
    private val handled = HashMap<String, Int>()

    private fun floor(): Long = kexFile.getLong("floor_height", 0L)

    private fun storedLines(): List<String> =
        kexFile
            .getString("ids", null)
            .orEmpty()
            .split('\n')
            .filter { it.isNotEmpty() }

    /** One history pass over [history] up to [tip] (the fully scanned height; the pass reads nothing above it). */
    private fun pass(
        history: List<Tx>,
        tip: Long,
        complete: Boolean,
        offer: Tx,
        offerMayAct: Boolean,
    ) {
        val visible = history.filter { it.height <= tip }
        val offerUnrecorded = offer in visible && !prefs.hasProcessedKexTx(offer.id, offer.height)
        prefs.limitKexFloor(complete, tip, if (offerUnrecorded) offer.height else null)
        visible.forEach { tx ->
            if (prefs.hasProcessedKexTx(tx.id, tx.height)) return@forEach
            if (tx == offer && !offerMayAct) return@forEach // put off, not marked
            handled[tx.id] = (handled[tx.id] ?: 0) + 1
            prefs.markKexTxProcessed(tx.id, tx.height, tip)
        }
    }

    @Test
    fun `a key offer mined during 50 hours offline is handled exactly once after the restart`() {
        prefs.reloadWalletScopedStores() // a start: the ledger is loaded from the store (no ceiling yet: 0)
        val before = (1..CAP).map { Tx("$tag-before-$it", H0 - CAP + it) }
        val offer = Tx("$tag-offer", OFFER_AT)
        val others = (0 until OTHERS).map { Tx("$tag-other-$it", FIRST_OTHER + it * (LAST_OTHER - FIRST_OTHER) / (OTHERS - 1)) }
        assertEquals("fixture: the others span H0 + 49 … H0 + 2,352", LAST_OTHER, others.last().height)

        // Before T0: the 500 handled as a first pass reads them, then a complete pass at H0.
        pass(before, H0, complete = false, offer = offer, offerMayAct = true)
        pass(before, H0, complete = true, offer = offer, offerMayAct = true)
        assertEquals("fixture: the complete pass let the ceiling rise to H0", H0, kexFile.getLong("floor_ceiling", 0L))
        before.forEach { assertEquals("each is handled once before the gap", 1, handled[it.id]) }

        // The restart after 50 hours: the history now holds the offer and 600 others above it.
        prefs.reloadWalletScopedStores()
        val history = before + offer + others
        listOf(H0 + 800, H0 + 1_600, H0 + GAP_BLOCKS).forEach { tip ->
            pass(history, tip, complete = false, offer = offer, offerMayAct = false)
            assertFalse("catch-up to $tip: the put-off offer does not count as processed", prefs.hasProcessedKexTx(offer.id, OFFER_AT))
            assertTrue("catch-up to $tip: the floor (${floor()}) stays below the offer", floor() <= OFFER_AT - 1)
        }
        val kept = storedLines().size
        assertTrue("the ceiling kept entries past the cap instead of flooring over the offer ($kept)", kept > CAP)
        assertEquals("the old entries were evicted and the floor covers them, up to the ceiling", H0, floor())

        // The stable pass: the offer is handled and marked.
        pass(history, H0 + GAP_BLOCKS, complete = true, offer = offer, offerMayAct = true)
        assertEquals("the offer is handled on the stable pass", 1, handled[offer.id])
        assertTrue(prefs.hasProcessedKexTx(offer.id, OFFER_AT))

        // Later passes, one with a new transaction whose record evicts past the cap again: nothing is handled twice.
        pass(history, H0 + GAP_BLOCKS, complete = true, offer = offer, offerMayAct = true)
        val later = history + Tx("$tag-later", H0 + GAP_BLOCKS)
        pass(later, H0 + GAP_BLOCKS, complete = true, offer = offer, offerMayAct = true)
        pass(later, H0 + GAP_BLOCKS, complete = true, offer = offer, offerMayAct = true)
        assertTrue("the later record evicted and raised the floor (${floor()})", floor() > OFFER_AT)

        assertEquals("the offer is handled exactly once", 1, handled[offer.id])
        (before + others).forEach { assertEquals("every other transaction is handled exactly once", 1, handled[it.id]) }
        later.forEach { assertTrue("${it.id} still counts as processed", prefs.hasProcessedKexTx(it.id, it.height)) }
        assertTrue("no heightless entry", storedLines().all { it.substringAfterLast(':', "").toLongOrNull() != null })
    }
}
