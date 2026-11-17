package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.sendattempt.ChainTimingApi
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * ZC-114 (fix-all 2.13.0, train T5b; high-items SPECS §1.5 R4): an automatic TEX shield whose expiry was not recorded
 * (the process ended between its create and its record) counts as unminable only past the last height it could be
 * mined at: the tip read before the create, plus 1 (its target), plus the largest expiry delta the shipped SDK can use
 * there, plus today's 9 blocks of room. Before NU7 that is createStartTip + 50 as today; from NU7 on (ZIP 218: 120)
 * createStartTip + 130. A bound that is too short builds a second shield over the same output early (a false
 * "expired"; both spend one output, so no double payment, but a conflicting transaction).
 *
 * At RED the bound is createStartTip + 50 at every height. [N] is a made-up activation height.
 */
class TexShieldBoundFollowsTheExpiryDeltaOfItsHeightTest {
    private companion object {
        const val N = 4_000_000L
        const val KEY = "synthetic-zc114-tex-key"
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

    @Test
    fun `a shield created after NU7 with no recorded expiry may be mined until its tip plus 130`() {
        ChainTimingApi.current = ChainTimingApi.heights(N)
        val s = N + 500
        assertEquals(
            s + 1 + 120 + 9,
            TexRecoveryRecord(key = KEY, attempt = 1, createStartTip = s).shieldBound,
            "a shield made after NU7 can be mined until 120 blocks past its target, yet it counts as expired at +50",
        )
    }

    @Test
    fun `a shield created one block before NU7 targets NU7 and may be mined until its tip plus 130`() {
        ChainTimingApi.current = ChainTimingApi.heights(N)
        val s = N - 1
        assertEquals(s + 130, TexRecoveryRecord(key = KEY, attempt = 1, createStartTip = s).shieldBound)
    }

    // ── controls ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `control - a shield created well before NU7 keeps tip plus 50`() {
        ChainTimingApi.current = ChainTimingApi.heights(N)
        val s = N - 1_000
        assertEquals(s + TexRulesView.UNKNOWN_EXPIRY_BOUND, TexRecoveryRecord(key = KEY, attempt = 1, createStartTip = s).shieldBound)
    }

    @Test
    fun `control - a recorded expiry decides by itself after NU7`() {
        ChainTimingApi.current = ChainTimingApi.heights(N)
        val record = TexRecoveryRecord(key = KEY, attempt = 1, createStartTip = N + 500, shieldExpiry = N + 541)
        assertEquals(N + 541, record.shieldBound)
    }

    @Test
    fun `control - the shipped table keeps tip plus 50`() {
        // ChainTiming's current table as shipped (no NU7 height): holds before and after the fix.
        val s = 3_200_000L
        assertEquals(s + TexRulesView.UNKNOWN_EXPIRY_BOUND, TexRecoveryRecord(key = KEY, attempt = 1, createStartTip = s).shieldBound)
        assertEquals(null, TexRecoveryRecord(key = KEY).shieldBound)
    }
}
