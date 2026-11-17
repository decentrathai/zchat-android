package co.electriccoin.zcash.ui.common.sendattempt

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-1554 (critical, 6 Nov 2026): NU7 activated on mainnet at block 3,543,405 (ZIP 259; branch 0x77190ad9) and the
 * release candidate on the official SDK 3.0.0 can no longer send: 3.0.0 signs every transaction for NU6.3. The fix
 * ships our own build of the official MIT SDK on the stable NU7 crates (owner W-130) and fills ChainTiming from it.
 *
 * Pinned here:
 * - the SDK really on the classpath is that build, never the official 3.0.0 (a build that silently resolves 3.0.0
 *   passes every other test and still cannot send: Fable's plan critique, finding 2);
 * - both NU7 rows hold the heights of that SDK's crates (zcash_protocol 0.11.0);
 * - from NU7 on a transaction expires 120 blocks after its target (zcash_primitives 0.31.0 NU7_DEFAULT_TX_EXPIRY_DELTA),
 *   40 before;
 * - the double-payment margin keeps its time, not its block count (ZC-688 H1, decided 6 Oct): 10 blocks of 75 s before
 *   NU7, 30 blocks of 25 s from NU7 on, read at the transaction's expiry height. With a fixed 10 a post-NU7 send would
 *   count as "Not sent" 4 minutes after its expiry, and Retry could pay again a third as late as it does today.
 *
 * At RED the classpath holds SDK 3.0.0, both NU7 rows are null and every margin is 10 blocks.
 */
class Nu7MainnetIsLiveTest {
    private val nu7 = 3_543_405L

    @After
    fun restore() {
        ChainTiming.current = ChainTiming.MAINNET
    }

    @Test
    fun `the SDK on the classpath is our NU7 build of the official SDK`() {
        val onClasspath =
            Class.forName("cash.z.ecc.android.sdk.BuildConfig").getField("LIBRARY_VERSION").get(null) as String
        assertEquals("3.0.0-nu7-zchat.3", onClasspath)
    }

    @Test
    fun `the NU7 rows hold the heights of the shipped crates`() {
        assertEquals(nu7, ChainTimingApi.nu7Of(ChainTimingApi.row("MAINNET")))
        assertEquals(4_465_026L, ChainTimingApi.nu7Of(ChainTimingApi.row("TESTNET")))
    }

    @Test
    fun `a transaction targeted from NU7 on expires 120 blocks later, one before it 40`() {
        assertEquals(40L, ChainTiming.maxExpiryDelta(nu7 - 1, ChainTiming.MAINNET))
        assertEquals(120L, ChainTiming.maxExpiryDelta(nu7, ChainTiming.MAINNET))
        assertEquals(25L, ChainTiming.blockSeconds(nu7, ChainTiming.MAINNET))
        assertEquals(75L, ChainTiming.blockSeconds(nu7 - 1, ChainTiming.MAINNET))
    }

    @Test
    fun `a post-NU7 transaction is dead 30 blocks past its expiry, not 10`() {
        ChainTiming.current = ChainTiming.MAINNET
        val expiry = nu7 + 1_000
        assertFalse(SendAttemptRules.isDead(expiry, expiry + 10))
        assertFalse(SendAttemptRules.isDead(expiry, expiry + 29))
        assertTrue(SendAttemptRules.isDead(expiry, expiry + 30))
        assertEquals(expiry + 30, SendAttemptRules.deadBlock(expiry))
    }

    @Test
    fun `a pre-NU7 transaction keeps its 10-block margin`() {
        ChainTiming.current = ChainTiming.MAINNET
        val expiry = nu7 - 200
        assertTrue(SendAttemptRules.isDead(expiry, expiry + 10))
        assertEquals(expiry + 10, SendAttemptRules.deadBlock(expiry))
    }

    @Test
    fun `the margin is read at the expiry height when the wait straddles the activation`() {
        ChainTiming.current = ChainTiming.MAINNET
        // Expiry just before NU7: its margin is the pre-NU7 10 blocks, although those blocks come at 25 s.
        assertEquals(nu7 - 1 + 10, SendAttemptRules.deadBlock(nu7 - 1))
        // Expiry at the activation block: the post-NU7 30.
        assertEquals(nu7 + 30, SendAttemptRules.deadBlock(nu7))
    }

    @Test
    fun `nothing a post-NU7 attempt made can be mined later than its whole window`() {
        // 120 (expiry delta) + 6 (tip lead kept in time) + 30 (margin kept in time) = 156 blocks = 65 min, as before NU7
        // (40 + 2 + 10 = 52 blocks of 75 s).
        assertEquals(156L, SendAttemptRules.window(nu7 + 1_000, legacy = false, upgrades = ChainTiming.MAINNET))
        assertEquals(52L, SendAttemptRules.window(nu7 - 1_000, legacy = false, upgrades = ChainTiming.MAINNET))
        // A 2.12.4 row keeps its pre-NU6.3 delta at any height.
        assertEquals(40L + 6L + 30L, SendAttemptRules.window(nu7 + 1_000, legacy = true, upgrades = ChainTiming.MAINNET))
    }

    @Test
    fun `the minutes until dead of a post-NU7 send count 30 blocks of 25 s past its expiry`() {
        ChainTiming.current = ChainTiming.MAINNET
        val expiry = nu7 + 1_000
        // From the expiry block itself: 30 blocks * 25 s = 750 s = 12.5 min, rounded up.
        assertEquals(13, SendAttemptRules.minutesUntilDead(expiry, expiry, ChainTiming.MAINNET))
    }
}
