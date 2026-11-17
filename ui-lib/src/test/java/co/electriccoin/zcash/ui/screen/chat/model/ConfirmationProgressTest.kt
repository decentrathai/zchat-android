package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * RED pins for the "N/10 confirmations, ~X min" progress shown while Ironwood funds confirm.
 *
 * The SDK decides spendability, not the app: a shielded note is spendable once
 * `tip >= mined_height + N - 1`, with N = 3 for the wallet's own change (Internal scope) or a
 * trusted transaction and N = 10 otherwise (ZIP 315 defaults, hard-wired in the SDK's Rust JNI).
 * These pins keep the display arithmetic honest against that rule — the mined block itself is
 * confirmation 1 — so the card never promises "spendable" earlier or later than the SDK will be.
 *
 * Heights are the real mainnet heights of the HONOR device's first Ironwood deposit
 * (mined 3475143, observed while the tip walked from 3475146 to 3475152).
 */
class ConfirmationProgressTest {
    private val mined = 3475143L

    @Test
    fun constants_mirror_the_sdk_zip315_defaults() {
        assertEquals(10, UNTRUSTED_CONFIRMATIONS, "third-party deposit needs 10 confirmations")
        assertEquals(3, TRUSTED_CONFIRMATIONS, "own change / trusted tx needs 3")
        assertEquals(
            90L,
            SECONDS_PER_BLOCK,
            "90 s per block: mainnet drifts 75–100 s, and 75 s under-promised by a third",
        )
    }

    @Test
    fun third_party_deposit_is_complete_at_ten_confirmations() {
        // tip = mined + 9 → tip >= mined + 10 - 1 → the SDK counts it spendable.
        val p = confirmationProgress(networkHeight = 3475152L, minedHeight = mined, trusted = false)

        assertEquals(10, p?.required)
        assertEquals(10, p?.confirmations)
        assertEquals(0, p?.remainingBlocks)
        assertTrue(p!!.isComplete)
        assertEquals(0L, p.etaSeconds)
    }

    @Test
    fun mined_block_counts_as_the_first_confirmation() {
        // tip = mined + 3 → 4 confirmations, 6 blocks to go, 6 × 90 s = 540 s → ~9 min.
        val p = confirmationProgress(networkHeight = 3475146L, minedHeight = mined, trusted = false)

        assertEquals(4, p?.confirmations)
        assertEquals(10, p?.required)
        assertEquals(6, p?.remainingBlocks)
        assertFalse(p!!.isComplete)
        assertEquals(540L, p.etaSeconds)
    }

    @Test
    fun own_change_and_trusted_receipts_need_three() {
        val justMined = confirmationProgress(networkHeight = mined, minedHeight = mined, trusted = true)
        assertEquals(3, justMined?.required)
        assertEquals(1, justMined?.confirmations)
        assertEquals(2, justMined?.remainingBlocks)
        assertEquals(180L, justMined?.etaSeconds)
        assertFalse(justMined!!.isComplete)

        val done = confirmationProgress(networkHeight = mined + 2, minedHeight = mined, trusted = true)
        assertTrue(done!!.isComplete, "tip = mined + 3 - 1 is spendable for own change")
        assertEquals(3, done.confirmations)
    }

    @Test
    fun unknown_tip_or_unmined_transaction_has_no_progress() {
        assertNull(confirmationProgress(networkHeight = null, minedHeight = mined, trusted = false))
        assertNull(confirmationProgress(networkHeight = 3475146L, minedHeight = null, trusted = false))
        assertNull(confirmationProgress(networkHeight = null, minedHeight = null, trusted = true))
    }

    @Test
    fun confirmations_are_clamped_to_zero_and_required() {
        // A stale tip (behind the mined height, e.g. right after a reorg) must not go negative.
        val stale = confirmationProgress(networkHeight = mined - 5, minedHeight = mined, trusted = false)
        assertEquals(0, stale?.confirmations)
        assertEquals(10, stale?.remainingBlocks)
        assertFalse(stale!!.isComplete)
        assertEquals(900L, stale.etaSeconds)

        // A tip far past the note must not overshoot the requirement.
        val old = confirmationProgress(networkHeight = mined + 500, minedHeight = mined, trusted = false)
        assertEquals(10, old?.confirmations)
        assertTrue(old!!.isComplete)

        // Direct construction is clamped the same way, so no caller can render "12/10" or "-3/10".
        assertEquals(0, ConfirmationProgress(confirmations = 12, required = 10).remainingBlocks)
        assertTrue(ConfirmationProgress(confirmations = 12, required = 10).isComplete)
        assertEquals(10, ConfirmationProgress(confirmations = -3, required = 10).remainingBlocks)
        assertEquals(0L, ConfirmationProgress(confirmations = 12, required = 10).etaSeconds)
    }

    @Test
    fun label_reads_progress_and_rounded_up_eta() {
        assertEquals(
            "4/10 confirmations, ~9 min",
            confirmationProgress(networkHeight = 3475146L, minedHeight = mined, trusted = false)?.label,
        )
        // 2 blocks × 90 s = 180 s = 3 min; 1 block = 90 s rounds UP to ~2 min — never "~0 min" while blocks remain.
        assertEquals(
            "1/3 confirmations, ~3 min",
            confirmationProgress(networkHeight = mined, minedHeight = mined, trusted = true)?.label,
        )
        assertEquals("9/10 confirmations, ~2 min", ConfirmationProgress(confirmations = 9, required = 10).label)
        assertEquals("0/10 confirmations, ~15 min", ConfirmationProgress(confirmations = 0, required = 10).label)
    }

    @Test
    fun label_reads_complete_when_done() {
        assertEquals(
            "10/10 confirmations, complete",
            confirmationProgress(networkHeight = 3475152L, minedHeight = mined, trusted = false)?.label,
        )
        assertEquals("10/10 confirmations, complete", ConfirmationProgress(confirmations = 12, required = 10).label)
        assertEquals("3/3 confirmations, complete", ConfirmationProgress(confirmations = 3, required = 3).label)
    }

    @Test
    fun pending_receipt_trust_picks_the_required_count() {
        val tip = mined
        val ownChange = PendingReceipt(minedHeight = mined, isOwnChange = true, isTrusted = false)
        assertEquals(3, ownChange.progressAt(tip)?.required)
        val trusted = PendingReceipt(minedHeight = mined, isOwnChange = false, isTrusted = true)
        assertEquals(3, trusted.progressAt(tip)?.required)
        val thirdParty = PendingReceipt(minedHeight = mined, isOwnChange = false, isTrusted = false)
        assertEquals(10, thirdParty.progressAt(tip)?.required)
        assertNull(PendingReceipt(minedHeight = null, isOwnChange = false, isTrusted = false).progressAt(tip))
    }

    @Test
    fun selector_returns_the_receipt_that_unblocks_soonest() {
        val tip = 3475146L
        val thirdParty = PendingReceipt(minedHeight = mined, isOwnChange = false, isTrusted = false) // 4/10, 6 to go
        val ownChange = PendingReceipt(minedHeight = tip, isOwnChange = true, isTrusted = false) // 1/3, 2 to go
        val alreadySpendable = PendingReceipt(minedHeight = tip - 20, isOwnChange = false, isTrusted = false) // 10/10
        val unmined = PendingReceipt(minedHeight = null, isOwnChange = false, isTrusted = false)

        val soonest = soonestPendingReceipt(tip, listOf(thirdParty, alreadySpendable, ownChange, unmined))
        assertSame(ownChange, soonest, "fewest remaining blocks wins, regardless of list order")

        val progress = soonestConfirmationProgress(tip, listOf(thirdParty, alreadySpendable, ownChange, unmined))
        assertEquals("1/3 confirmations, ~3 min", progress?.label)

        // Without the own-change note the third-party deposit is the one to wait for.
        assertSame(thirdParty, soonestPendingReceipt(tip, listOf(alreadySpendable, thirdParty, unmined)))
        val withoutOwnChange = soonestConfirmationProgress(tip, listOf(alreadySpendable, thirdParty))
        assertEquals("4/10 confirmations, ~9 min", withoutOwnChange?.label)
    }

    @Test
    fun selector_has_nothing_to_report_when_nothing_is_pending() {
        val tip = 3475152L
        val spendable = PendingReceipt(minedHeight = mined, isOwnChange = false, isTrusted = false) // 10/10 at this tip
        val unmined = PendingReceipt(minedHeight = null, isOwnChange = true, isTrusted = true)

        assertNull(soonestPendingReceipt(tip, emptyList()))
        assertNull(soonestPendingReceipt(tip, listOf(spendable, unmined)), "complete and unmined receipts are skipped")
        assertNull(soonestConfirmationProgress(tip, listOf(spendable, unmined)))
        assertNull(soonestPendingReceipt(null, listOf(spendable)), "no tip → no progress to show")
    }

    // ------------------------------------------------------------ review round: the SDK-flag projection
    // The riskiest logic was an untested private ViewModel extension. It is now a pure function whose
    // inputs are the SDK's own flags, so a refactor that flips a third-party deposit to "3 confirmations"
    // fails here instead of lying on the card.

    @Test
    fun peer_receipt_projects_as_untrusted_ten() {
        val r =
            pendingReceiptFrom(
                minedHeight = 3_475_143L,
                isSentTransaction = false,
                isChange = false,
                isShielding = false,
                isTrusted = false,
                receivedNoteCount = 1,
                totalReceivedZat = 1_095_000L,
                landsInIronwood = true,
            )
        assertNotNull(r)
        assertFalse(r.isOwnChange)
        assertEquals(UNTRUSTED_CONFIRMATIONS, r.requiredConfirmations)
    }

    @Test
    fun sent_with_change_and_shielding_project_as_own_change_three() {
        val change =
            pendingReceiptFrom(
                minedHeight = 3_475_155L,
                isSentTransaction = true,
                isChange = true,
                isShielding = false,
                isTrusted = false,
                receivedNoteCount = 0,
                totalReceivedZat = 0L,
                landsInIronwood = true,
            )
        val shield =
            pendingReceiptFrom(
                minedHeight = 3_475_155L,
                isSentTransaction = false,
                isChange = false,
                isShielding = true,
                isTrusted = false,
                receivedNoteCount = 0,
                totalReceivedZat = 0L,
                landsInIronwood = true,
            )
        assertEquals(TRUSTED_CONFIRMATIONS, assertNotNull(change).requiredConfirmations)
        assertEquals(TRUSTED_CONFIRMATIONS, assertNotNull(shield).requiredConfirmations)
    }

    @Test
    fun a_plain_send_without_change_and_an_unmined_tx_are_not_receipts() {
        assertNull(
            pendingReceiptFrom(
                minedHeight = 3_475_155L,
                isSentTransaction = true,
                isChange = false,
                isShielding = false,
                isTrusted = false,
                receivedNoteCount = 0,
                totalReceivedZat = 0L,
                landsInIronwood = true,
            ),
        )
        assertNull(
            pendingReceiptFrom(
                minedHeight = null,
                isSentTransaction = false,
                isChange = false,
                isShielding = false,
                isTrusted = false,
                receivedNoteCount = 1,
                totalReceivedZat = 5_000L,
                landsInIronwood = true,
            ),
        )
    }

    /**
     * Only Ironwood can unblock an Ironwood-stranded wallet: a Sapling or transparent receipt must not
     * drive the countdown.
     */
    @Test
    fun a_receipt_that_does_not_land_in_ironwood_is_ignored_but_unknown_pool_falls_back_to_the_heuristic() {
        val base = { pool: Boolean? ->
            pendingReceiptFrom(
                minedHeight = 3_475_143L,
                isSentTransaction = false,
                isChange = false,
                isShielding = false,
                isTrusted = false,
                receivedNoteCount = 1,
                totalReceivedZat = 20_000L,
                landsInIronwood = pool,
            )
        }
        assertNull(base(false), "a non-Ironwood receipt cannot be the note that unblocks messaging")
        assertNotNull(base(null), "when the pool is unknown the receipt still counts (pre-existing heuristic)")
        assertNotNull(base(true))
    }

    /** The window bound keeps only receipts that could still be pending under the 10-confirmation rule. */
    @Test
    fun receipts_older_than_ten_blocks_below_the_newest_are_dropped() {
        val old = PendingReceipt(minedHeight = 3_475_100L, isOwnChange = false, isTrusted = false)
        val recent = PendingReceipt(minedHeight = 3_475_143L, isOwnChange = false, isTrusted = false)
        assertEquals(listOf(recent), recentPendingReceipts(listOf(old, recent)))
        assertEquals(emptyList<PendingReceipt>(), recentPendingReceipts(emptyList()))
    }

    /** Owner-approved wording, verbatim: "N/10 confirmations, ~X min" — a comma, not a typographic dot. */
    @Test
    fun label_uses_the_approved_wording() {
        assertEquals("4/10 confirmations, ~9 min", ConfirmationProgress(confirmations = 4, required = 10).label)
        assertEquals("10/10 confirmations, complete", ConfirmationProgress(confirmations = 10, required = 10).label)
    }

    /**
     * Mainnet spacing is 75–100 s and the refill measured 87.7 s/block, so a 75 s ETA under-promises by up
     * to a third ("~8 min" that is really ~10). 90 s keeps the '~' honest.
     */
    @Test
    fun eta_uses_ninety_seconds_per_block() {
        assertEquals(90L, SECONDS_PER_BLOCK)
        assertEquals(6 * 90L, ConfirmationProgress(confirmations = 4, required = 10).etaSeconds)
    }
}
