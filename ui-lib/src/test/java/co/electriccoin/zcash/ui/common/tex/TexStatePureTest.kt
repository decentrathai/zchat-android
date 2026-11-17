package co.electriccoin.zcash.ui.common.tex

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F3 (PLAN.md §3 F3 TexStatePureTest, with cross-lane correction 2): the state of a TEX send as far as the chain can
 * say, from the wallet's SCANNED tip only. "Not sent" (the double-pay case: "you can send again") needs the scanned
 * tip 10 blocks past the expiry in two non-decreasing passes; the network's tip proves nothing.
 * Fails at 15e2b1c39: there is no TEX checker.
 */
class TexStatePureTest {
    private val expiry = 1_000L

    @Test
    fun leg_two_mined_is_done() {
        assertEquals("DONE", TexApi.derive(leg1Mined = true, leg2Mined = true, expiry = expiry, scannedTip = 1_200))
    }

    @Test
    fun money_case_network_tip_past_expiry_but_scanned_tip_below_is_still_sending() {
        assertEquals(
            "SENDING_STEP1",
            TexApi.derive(
                leg1Mined = false,
                leg2Mined = false,
                expiry = expiry,
                scannedTip = expiry - 1,
                networkTip = expiry + 500,
                leg1NotSentProven = true,
            ),
        )
        assertEquals(
            "SENDING_STEP2",
            TexApi.derive(leg1Mined = true, leg2Mined = false, expiry = expiry, scannedTip = expiry - 1, networkTip = expiry + 500),
        )
    }

    @Test
    fun not_sent_needs_the_margin_and_the_two_pass_proof() {
        val margin = TexRulesView.REORG_MARGIN
        assertEquals("SENDING_STEP1", TexApi.derive(false, false, expiry, scannedTip = expiry + margin))
        assertEquals(
            "SENDING_STEP1",
            TexApi.derive(false, false, expiry, scannedTip = expiry + margin - 1, leg1NotSentProven = true),
        )
        assertEquals("SENDING_STEP1", TexApi.derive(false, false, expiry, scannedTip = null, leg1NotSentProven = true))
        assertEquals("NOT_SENT", TexApi.derive(false, false, expiry, scannedTip = expiry + margin, leg1NotSentProven = true))
    }

    @Test
    fun the_lookup_is_due_two_blocks_past_the_expiry() {
        val check = TexRulesView.CHECK_MARGIN
        assertEquals("SENDING_STEP2", TexApi.derive(true, false, expiry, scannedTip = expiry + check - 1))
        assertEquals("SENDING_STEP2", TexApi.derive(true, false, expiry, scannedTip = null))
        assertEquals("CHECKING", TexApi.derive(true, false, expiry, scannedTip = expiry + check))
    }

    @Test
    fun the_not_sent_proof_counts_two_non_decreasing_passes_past_the_margin() {
        val proof = TexApi.notSentProof()
        val bound = expiry + TexRulesView.REORG_MARGIN

        assertFalse(TexApi.observe(proof, "a", bound, expiry), "one pass is not a proof")
        assertTrue(TexApi.observe(proof, "a", bound, expiry), "two passes at the bound are")
        assertTrue(TexApi.observe(proof, "a", bound + 3, expiry))
    }

    @Test
    fun a_rewind_or_an_unknown_tip_between_passes_starts_the_count_again() {
        val proof = TexApi.notSentProof()
        val bound = expiry + TexRulesView.REORG_MARGIN

        assertFalse(TexApi.observe(proof, "a", bound + 5, expiry))
        assertFalse(TexApi.observe(proof, "a", bound + 1, expiry), "a lower second pass (a rewind) is not a proof")
        assertTrue(TexApi.observe(proof, "a", bound + 1, expiry))

        assertFalse(TexApi.observe(proof, "b", bound, expiry))
        assertFalse(TexApi.observe(proof, "b", null, expiry), "an unknown tip proves nothing")
        assertFalse(TexApi.observe(proof, "b", bound, expiry), "and starts the count again")

        assertFalse(TexApi.observe(proof, "c", bound, expiry))
        assertFalse(TexApi.observe(proof, "c", bound - 1, expiry), "a tip below the bound starts it again")
        assertFalse(TexApi.observe(proof, "c", bound, expiry))
    }

    @Test
    fun the_scanned_tip_is_the_lower_of_the_reads_around_the_pass_and_unknown_when_one_fails() =
        runBlocking {
            val tips = ArrayDeque(listOf(1_010L, 1_004L))
            val read =
                TexApi.callStaticSuspend(
                    "TexStateDeriverKt",
                    "readWithScannedTip",
                    tipReader { tips.removeFirst() },
                    valueReader { "txs" },
                )!!
            assertEquals(1_004L, TexApi.prop(read, "tip"))
            assertEquals("txs", TexApi.prop(read, "value"))

            val missing = ArrayDeque(listOf<Long?>(1_010L, null))
            val unknown =
                TexApi.callStaticSuspend(
                    "TexStateDeriverKt",
                    "readWithScannedTip",
                    tipReader { missing.removeFirst() },
                    valueReader { "txs" },
                )!!
            assertNull(TexApi.prop(unknown, "tip"))

            val throwing =
                TexApi.callStaticSuspend(
                    "TexStateDeriverKt",
                    "readWithScannedTip",
                    tipReader { throw IllegalStateException("fake: the wallet is closed") },
                    valueReader { "txs" },
                )!!
            assertNull(TexApi.prop(throwing, "tip"))
        }

    /** A `suspend () -> Long?` as the product takes it (a Function1 over the continuation). */
    private fun tipReader(read: () -> Long?): Any = SuspendReader(read)

    private fun valueReader(read: () -> Any?): Any = SuspendReader(read)

    private class SuspendReader(
        private val read: () -> Any?,
    ) : (kotlin.coroutines.Continuation<Any?>) -> Any? {
        override fun invoke(continuation: kotlin.coroutines.Continuation<Any?>): Any? = read()
    }
}
