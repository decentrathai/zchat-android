package co.electriccoin.zcash.ui.common.sendattempt

import co.electriccoin.zcash.ui.common.tex.TexRecoveryRecord
import org.junit.Test
import kotlin.test.assertEquals

/**
 * ZC-114 (fix-all 2.13.0, train T5b): ChainTiming's table rules and boundaries. [N] is a made-up NU7 activation
 * height; the shipped rows (ZC-1554) are pinned on their own.
 */
class ChainTimingTest {
    private companion object {
        const val N = 4_000_000L
        val WITH_NU7 = NetworkUpgradeHeights(nu7 = N)
        val NO_NU7 = NetworkUpgradeHeights(nu7 = null)
    }

    @Test
    fun `the shipped rows hold the NU7 heights of the shipped crates (ZC-1554)`() {
        assertEquals(3_543_405L, ChainTiming.MAINNET.nu7)
        assertEquals(4_465_026L, ChainTiming.TESTNET.nu7)
    }

    @Test
    fun `NU7 starts at its activation block`() {
        assertEquals(false, ChainTiming.isNu7(N - 1, WITH_NU7))
        assertEquals(true, ChainTiming.isNu7(N, WITH_NU7))
        assertEquals(false, ChainTiming.isNu7(N + 1_000_000, NO_NU7))
    }

    @Test
    fun `block spacing by height, an unknown height counted at the longer spacing`() {
        assertEquals(75L, ChainTiming.blockSeconds(N - 1, WITH_NU7))
        assertEquals(25L, ChainTiming.blockSeconds(N, WITH_NU7))
        assertEquals(75L, ChainTiming.blockSeconds(null, WITH_NU7))
        assertEquals(75L, ChainTiming.blockSeconds(N + 10, NO_NU7))
    }

    @Test
    fun `seconds between two heights sum each block at its spacing`() {
        assertEquals(0L, ChainTiming.secondsBetween(N, N, WITH_NU7))
        assertEquals(0L, ChainTiming.secondsBetween(N + 5, N, WITH_NU7))
        assertEquals(10 * 75L, ChainTiming.secondsBetween(N - 20, N - 10, WITH_NU7))
        // Block N-1 at 75 s, block N at 25 s.
        assertEquals(75L, ChainTiming.secondsBetween(N - 2, N - 1, WITH_NU7))
        assertEquals(25L, ChainTiming.secondsBetween(N - 1, N, WITH_NU7))
        assertEquals(75L + 25L, ChainTiming.secondsBetween(N - 2, N, WITH_NU7))
        assertEquals(10 * 25L, ChainTiming.secondsBetween(N + 10, N + 20, WITH_NU7))
        assertEquals(30 * 75L, ChainTiming.secondsBetween(N, N + 30, NO_NU7))
    }

    @Test
    fun `minutes round up and are at least one`() {
        assertEquals(1, ChainTiming.minutesBetween(N, N, WITH_NU7))
        assertEquals(1, ChainTiming.minutesBetween(N + 1, N + 3, WITH_NU7)) // 50 s
        assertEquals(2, ChainTiming.minutesBetween(N + 1, N + 4, WITH_NU7)) // 75 s
        assertEquals(2, ChainTiming.minutesBetween(N - 10, N - 9, WITH_NU7)) // 75 s
        assertEquals(50, ChainTiming.minutesBetween(N - 100, N - 60, WITH_NU7)) // 40 x 75 s
    }

    @Test
    fun `the largest expiry delta is 40 before NU7 and with no NU7 height, 120 from NU7 on`() {
        assertEquals(40L, ChainTiming.maxExpiryDelta(N - 1, WITH_NU7))
        assertEquals(120L, ChainTiming.maxExpiryDelta(N, WITH_NU7))
        assertEquals(40L, ChainTiming.maxExpiryDelta(N + 10_000, NO_NU7))
    }

    @Test
    fun `display spacing keeps the 6 to 5 drift allowance`() {
        assertEquals(90L, ChainTiming.displaySecondsPerBlock(N - 1, WITH_NU7))
        assertEquals(30L, ChainTiming.displaySecondsPerBlock(N, WITH_NU7))
        assertEquals(90L, ChainTiming.displaySecondsPerBlock(null, WITH_NU7))
    }

    @Test
    fun `the send attempt window keeps its time across NU7 (ZC-1554)`() {
        // Lead 2 and delta 40 before NU7, but the expiry falls after NU7, so the margin there is 30: 72.
        assertEquals(72L, SendAttemptRules.window(N - 3, legacy = false, upgrades = WITH_NU7))
        assertEquals(52L, SendAttemptRules.window(N - 200, legacy = false, upgrades = WITH_NU7))
        // Lead 2 (pre-NU7), delta 120 (target at N), margin 30: 152; from NU7 on 6 + 120 + 30 = 156.
        assertEquals(152L, SendAttemptRules.window(N - 2, legacy = false, upgrades = WITH_NU7))
        assertEquals(156L, SendAttemptRules.window(N + 100, legacy = false, upgrades = WITH_NU7))
        // A 2.12.4 row keeps its delta of 40 and gets the post-NU7 lead and margin: 76.
        assertEquals(76L, SendAttemptRules.window(N + 100, legacy = true, upgrades = WITH_NU7))
        assertEquals(52L, SendAttemptRules.window(N + 100, legacy = false, upgrades = NO_NU7))
        assertEquals(42L, SendAttemptRules.LEGACY_MINED_WINDOW)
    }

    @Test
    fun `a TEX shield created just before NU7 counts the NU7 delta, as the SDK's tip may lead`() {
        val saved = ChainTiming.current
        ChainTiming.current = WITH_NU7
        try {
            // Target N-2, read TIP_LEAD later: N, so 120 (never a bound too short). Three blocks earlier: 40, as today.
            val nearNu7 = N - 3
            assertEquals(nearNu7 + 130, TexRecoveryRecord(key = "synthetic-zc114", createStartTip = nearNu7).shieldBound)
            val before = N - 4
            assertEquals(before + 50, TexRecoveryRecord(key = "synthetic-zc114", createStartTip = before).shieldBound)
        } finally {
            ChainTiming.current = saved
        }
    }
}
