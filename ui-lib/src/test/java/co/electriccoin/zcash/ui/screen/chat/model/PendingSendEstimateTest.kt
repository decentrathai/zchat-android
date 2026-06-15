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

    @Test
    fun `label shows full block estimate at start`() {
        assertEquals(
            "Sending after confirmation (~75s)",
            PendingSendEstimate.label(0L),
        )
    }

    @Test
    fun `label counts down remaining seconds`() {
        assertEquals(
            "Sending after confirmation (~45s left)",
            PendingSendEstimate.label(30L),
        )
    }

    @Test
    fun `label never shows zero or negative countdown`() {
        assertEquals(
            "Sending after confirmation (any moment…)",
            PendingSendEstimate.label(120L),
        )
        // Exactly one block elapsed -> remaining is 0 -> generic phrase, not "~0s left".
        assertEquals(
            "Sending after confirmation (any moment…)",
            PendingSendEstimate.label(PendingSendEstimate.AVERAGE_BLOCK_SECONDS),
        )
    }
}
