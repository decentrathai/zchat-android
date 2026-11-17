package co.electriccoin.zcash.ui.common.sendattempt

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * ZC-114 (fix-all 2.13.0, train T5b; high-items SPECS §1.5 R1): a row whose transactions' expiry is not known is judged
 * by the largest expiry delta the shipped SDK can use at the row's height, from one height table. Before NU7 (and while
 * the table holds no NU7 height, as for SDK 3.0.0) that is 40, so the row is "not sent" at anchor + 52 as today; from
 * NU7 on (ZIP 218: 120 blocks) at anchor + 156: ZC-1554 keeps the tip lead and the reorg margin in time from NU7 on
 * (ZC-688 H1/H3: 6 and 30 blocks of 25 s), so 120 + 6 + 30. A 2.12.4 row keeps its delta of 40 (2.12.4 built every
 * transaction before NU7) and gets the post-NU7 lead and margin: 76.
 *
 * At RED the window is the constant 52 at every height and there is no height table. [N] is a made-up activation
 * height; every txid and address is synthetic.
 */
class NoIdSendWaitsForTheExpiryDeltaOfItsHeightTest {
    private companion object {
        const val PEER = "usyntheticzc114peer0000000001"
        const val OTHER = "usyntheticzc114other000000001"
        const val N = 4_000_000L
        const val AMOUNT = 50_000L
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

    private fun tx(
        id: String,
        mined: Long? = null,
        expiry: Long?,
        recipients: List<String>? = listOf(PEER),
    ) = OwnTransaction(id, true, mined, expiry, AMOUNT + 10_000L, recipients, null, null)

    private fun attempt(
        anchor: Long,
        txIds: List<String> = emptyList(),
        expiries: Map<String, Long> = emptyMap(),
        legacy: Boolean = false,
    ) = SendAttempt(
        peerAddress = PEER,
        text = "synthetic zc114 hello",
        amountZatoshi = AMOUNT,
        rowTimeMillis = 1_000_000L,
        markerMillis = if (legacy) null else 1_001_000L,
        attemptHeight = if (legacy) null else anchor,
        txIds = txIds,
        expiries = expiries,
        legacy = legacy,
        anchorHeight = anchor,
        otherProcessSameAccount = false,
    )

    /** The resolver over [txs] at scanned tip [tip], with NU7 active from [nu7] on. */
    private fun resolveAt(
        attempt: SendAttempt,
        tip: Long,
        nu7: Long?,
        vararg txs: OwnTransaction,
    ) = SendAttemptResolver.resolve(
        attempt,
        ChainTimingApi.chainView(tip, txs.toList(), emptySet(), ChainTimingApi.heights(nu7)),
    )

    @Test
    fun `a no-id row anchored after NU7 waits for the delta of 120 before it is not sent`() {
        val anchor = N + 100
        val a = attempt(anchor)
        assertEquals(
            SendAttemptVerdict.Waiting(null, anchor + 52),
            resolveAt(a, anchor + 52, N),
            "a send made after NU7 can be mined until 120 blocks past its height, yet it is 'not sent' at anchor + 52",
        )
        assertEquals(SendAttemptVerdict.Waiting(null, anchor + 155), resolveAt(a, anchor + 155, N))
        assertEquals(SendAttemptVerdict.NotSent, resolveAt(a, anchor + 156, N))
    }

    @Test
    fun `a no-id row anchored two blocks before NU7 may target NU7 and waits for 120`() {
        val anchor = N - 2
        val a = attempt(anchor)
        assertEquals(
            SendAttemptVerdict.Waiting(null, anchor + 151),
            resolveAt(a, anchor + 151, N),
            "the SDK's tip may lead the anchor by 2, so the transaction may target NU7's first block",
        )
        // Lead 2 (read before NU7), delta 120, margin 30 (its expiry is after NU7): 152.
        assertEquals(SendAttemptVerdict.NotSent, resolveAt(a, anchor + 152, N))
        // One block earlier the target is before NU7 at most: 40; its expiry falls after NU7, so the margin is 30: 72.
        assertEquals(SendAttemptVerdict.Waiting(null, N - 3 + 71), resolveAt(attempt(N - 3), N - 3 + 71, N))
        assertEquals(SendAttemptVerdict.NotSent, resolveAt(attempt(N - 3), N - 3 + 72, N))
    }

    @Test
    fun `a recorded id missing from the wallet with no recorded expiry waits for 120 after NU7`() {
        val anchor = N + 100
        val a = attempt(anchor, txIds = listOf("t1"))
        val someTx = tx("other", expiry = anchor + 40, recipients = listOf(OTHER))
        assertEquals(SendAttemptVerdict.Waiting(null, anchor + 52), resolveAt(a, anchor + 52, N, someTx))
        assertEquals(SendAttemptVerdict.Waiting(null, anchor + 155), resolveAt(a, anchor + 155, N, someTx))
        assertEquals(SendAttemptVerdict.NotSent, resolveAt(a, anchor + 156, N, someTx))
    }

    @Test
    fun `a chain view built without a table is judged by ChainTiming's current table`() {
        ChainTimingApi.current = ChainTimingApi.heights(N)
        val anchor = N + 100
        assertEquals(
            SendAttemptVerdict.Waiting(null, anchor + 52),
            SendAttemptResolver.resolve(attempt(anchor), ChainView(anchor + 52, emptyList())),
            "the callers build ChainView(tip, transactions[, claimed]); it must take the process's height table",
        )
    }

    // ── controls ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `control - a no-id row anchored long before NU7 is not sent at anchor plus 52 as today`() {
        val anchor = N - 500
        val a = attempt(anchor)
        assertEquals(SendAttemptVerdict.Waiting(null, anchor + 51), resolveAt(a, anchor + 51, N))
        assertEquals(SendAttemptVerdict.NotSent, resolveAt(a, anchor + 52, N))
    }

    @Test
    fun `control - with no NU7 height in the table every row keeps 52`() {
        val anchor = N + 100
        assertEquals(SendAttemptVerdict.NotSent, resolveAt(attempt(anchor), anchor + 52, null))
    }

    @Test
    fun `control - a 2124 row keeps its delta of 40 and its mined window of 42 after NU7`() {
        val anchor = N + 100
        val legacy = attempt(anchor, legacy = true)
        // 40 + the post-NU7 lead 6 + margin 30 (ZC-1554).
        assertEquals(SendAttemptVerdict.Waiting(null, anchor + 75), resolveAt(legacy, anchor + 75, N))
        assertEquals(SendAttemptVerdict.NotSent, resolveAt(legacy, anchor + 76, N))
        val minedInWindow = tx("m1", mined = anchor + 42, expiry = anchor + 40)
        assertEquals(SendAttemptVerdict.MinedUnattributed, resolveAt(legacy, anchor + 52, N, minedInWindow))
        val minedAfter = tx("m2", mined = anchor + 43, expiry = anchor + 43)
        assertEquals(SendAttemptVerdict.NotSent, resolveAt(legacy, anchor + 76, N, minedAfter))
    }

    @Test
    fun `control - a recorded or wallet expiry decides by itself after NU7`() {
        val expiry = N + 140
        val withIds = attempt(N + 100, txIds = listOf("t1"))
        // The margin past a post-NU7 expiry is 30 blocks (ZC-1554: 750 s, as 10 blocks before NU7).
        assertEquals(SendAttemptVerdict.Waiting(expiry, expiry + 29), resolveAt(withIds, expiry + 29, N, tx("t1", expiry = expiry)))
        assertEquals(SendAttemptVerdict.NotSent, resolveAt(withIds, expiry + 30, N, tx("t1", expiry = expiry)))
        val recorded = attempt(N + 100, txIds = listOf("t1"), expiries = mapOf("t1" to expiry))
        val someTx = tx("other", expiry = N + 50, recipients = listOf(OTHER))
        assertEquals(SendAttemptVerdict.NotSent, resolveAt(recorded, expiry + 30, N, someTx))
    }

    @Test
    fun `control - the shipped table keeps today's window of 52`() {
        // Reached through the constructor every caller uses, so this holds before and after the fix.
        val anchor = 3_200_000L
        assertEquals(
            SendAttemptVerdict.Waiting(null, anchor + 51),
            SendAttemptResolver.resolve(attempt(anchor), ChainView(anchor + 51, emptyList())),
        )
        assertEquals(SendAttemptVerdict.NotSent, SendAttemptResolver.resolve(attempt(anchor), ChainView(anchor + 52, emptyList())))
    }
}
