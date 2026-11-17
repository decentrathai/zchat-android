package co.electriccoin.zcash.ui.screen.chat.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingSendEstimateTest {

    @Test
    fun `zero elapsed gives zero progress`() {
        assertEquals(0f, PendingSendEstimate.progressFor(0L))
    }

    @Test
    fun `progress advances monotonically within one block`() {
        val early = PendingSendEstimate.progressFor(10L)!!
        val mid = PendingSendEstimate.progressFor(40L)!!
        val late = PendingSendEstimate.progressFor(70L)!!
        assertTrue(early < mid, "early=$early mid=$mid")
        assertTrue(mid < late, "mid=$mid late=$late")
    }

    @Test
    fun `progress is clamped below 1 even past one block`() {
        // 10 minutes elapsed — chain just running slow; bar must never read "done".
        val p = PendingSendEstimate.progressFor(600L)!!
        assertEquals(PendingSendEstimate.MAX_PROGRESS, p)
        assertTrue(p < 1f)
    }

    @Test
    fun `negative elapsed returns null for indeterminate fallback`() {
        assertNull(PendingSendEstimate.progressFor(-5L))
    }

    @Test
    fun `epoch-millis overload derives elapsed seconds`() {
        val queuedAt = 1_000_000L
        // 30s later
        val p = PendingSendEstimate.progressFor(queuedAt, queuedAt + 30_000L)!!
        assertEquals(30f / PendingSendEstimate.AVERAGE_BLOCK_SECONDS, p, 0.0001f)
    }

    @Test
    fun `epoch-millis overload returns null when now precedes queued`() {
        assertNull(PendingSendEstimate.progressFor(2_000_000L, 1_000_000L))
    }

    // R2-7 (c): the three label tests here pinned the one-block countdown ("~75s", "~45s left", "any moment…"),
    // which TXP-F14 found wrong for sends that wait minutes for ZEC to confirm. They now pin the replacement.

    @Test
    fun `label names no block countdown at start`() {
        assertEquals(co.electriccoin.zcash.ui.R.string.chat_pending_onchain, PendingSendEstimate.labelRes(0L))
        assertEquals(co.electriccoin.zcash.ui.R.string.chat_pending_onchain, PendingSendEstimate.labelRes(30L))
    }

    @Test
    fun `label says still sending once the wait is long`() {
        assertEquals(
            co.electriccoin.zcash.ui.R.string.chat_pending_onchain_slow,
            PendingSendEstimate.labelRes(PendingSendEstimate.SLOW_AFTER_SECONDS),
        )
        assertEquals(3, PendingSendEstimate.elapsedMinutes(PendingSendEstimate.SLOW_AFTER_SECONDS))
    }

    @Test
    fun `label never counts below zero`() {
        assertEquals(co.electriccoin.zcash.ui.R.string.chat_pending_onchain, PendingSendEstimate.labelRes(-5L))
        assertEquals(0, PendingSendEstimate.elapsedMinutes(-5L))
        assertEquals(
            co.electriccoin.zcash.ui.R.string.chat_pending_onchain_slow,
            PendingSendEstimate.labelRes(10 * PendingSendEstimate.AVERAGE_BLOCK_SECONDS),
        )
    }
}
