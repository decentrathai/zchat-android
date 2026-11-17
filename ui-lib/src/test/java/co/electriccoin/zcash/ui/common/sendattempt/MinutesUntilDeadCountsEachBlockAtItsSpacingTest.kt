package co.electriccoin.zcash.ui.common.sendattempt

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * ZC-114 (fix-all 2.13.0, train T5b; high-items SPECS §1.5 R2): "(about M min)" above a refused Retry, on the Wallet
 * send's progress screen and in its detail counts each block still owed at its own spacing: 75 s before NU7, 25 s
 * from NU7's first block on (ZIP 218). Block h arrives blockSeconds(h) after block h - 1. Before NU7, and while the
 * table holds no NU7 height, the numbers are today's (SendAttemptResolverTest: 63, 2, 2).
 *
 * At RED every block counts 75 s at every height. [N] is a made-up activation height.
 */
class MinutesUntilDeadCountsEachBlockAtItsSpacingTest {
    private companion object {
        const val N = 4_000_000L
    }

    private lateinit var saved: ChainTimingApi.Saved

    @Before
    fun setUp() {
        saved = ChainTimingApi.Saved.take()
    }

    @After
    fun tearDown() {
        saved.restore()
    }

    private fun minutes(
        expiry: Long,
        tip: Long,
        nu7: Long?,
    ): Int = ChainTimingApi.minutesUntilDead(expiry, tip, ChainTimingApi.heights(nu7))

    @Test
    fun `after NU7 each block owed counts 25 seconds`() {
        // 150 blocks (expiry + 30 - tip; ZC-1554 keeps the margin's 750 s) at 25 s = 3,750 s: 63 min, rounded up.
        assertEquals(63, minutes(expiry = N + 220, tip = N + 100, nu7 = N))
        // One block owed, or a tip already past: at least one block, 25 s, 1 min.
        assertEquals(1, minutes(expiry = N + 220, tip = N + 249, nu7 = N))
        assertEquals(1, minutes(expiry = N + 220, tip = N + 260, nu7 = N))
    }

    @Test
    fun `a range across NU7 counts each side at its own spacing`() {
        // Blocks N-9 .. N-1 at 75 s (675 s) and N .. N+80 at 25 s (2,025 s; margin 30 past the post-NU7 expiry):
        // 2,700 s, 45 min.
        assertEquals(45, minutes(expiry = N + 50, tip = N - 10, nu7 = N))
    }

    @Test
    fun `the two-argument form takes ChainTiming's current table`() {
        ChainTimingApi.current = ChainTimingApi.heights(N)
        assertEquals(
            63,
            SendAttemptRules.minutesUntilDead(expiry = N + 220, tip = N + 100),
            "the screens call minutesUntilDead(expiry, tip); it must count by the process's height table",
        )
    }

    // ── controls ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `control - before NU7 the minutes are today's`() {
        val e = N - 10_000
        assertEquals(63, minutes(expiry = e, tip = e - 40, nu7 = N))
        assertEquals(2, minutes(expiry = e, tip = e + 9, nu7 = N))
        assertEquals(2, minutes(expiry = e, tip = e + 20, nu7 = N))
    }

    @Test
    fun `control - with no NU7 height the minutes are today's at any height`() {
        assertEquals(163, minutes(expiry = N + 220, tip = N + 100, nu7 = null))
        // The two-argument form with the shipped table: holds before and after the fix.
        val e = 3_200_040L
        assertEquals(63, SendAttemptRules.minutesUntilDead(expiry = e, tip = e - 40))
        assertEquals(2, SendAttemptRules.minutesUntilDead(expiry = e, tip = e + 20))
    }
}
