package co.electriccoin.zcash.ui.common.sendattempt

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F4 (fix-all 2.13.0, train T4; SPECS §1.2): the rules a send attempt of a 1:1 chat row is judged by, as a table.
 * Every txid and address is synthetic.
 */
class SendAttemptResolverTest {
    private companion object {
        const val PEER = "usyntheticresolverpeer000001"
        const val OTHER = "usyntheticresolverother00001"
        const val H = 3_200_000L
        const val E = H + 40
        const val AMOUNT = 50_000L
    }

    private fun tx(
        id: String,
        mined: Long? = null,
        expiry: Long? = E,
        recipients: List<String>? = listOf(PEER),
        value: Long = AMOUNT + 10_000L,
        text: String? = null,
        blockTimeMillis: Long? = null,
        isSent: Boolean = true,
    ) = OwnTransaction(id, isSent, mined, expiry, value, recipients, text, blockTimeMillis)

    private fun attempt(
        txIds: List<String> = emptyList(),
        expiries: Map<String, Long> = emptyMap(),
        legacy: Boolean = false,
        anchor: Long? = H,
        otherProcessSameAccount: Boolean = false,
        attemptHeight: Long? = H,
        rowTimeMillis: Long = 1_000_000L,
        text: String = "synthetic resolver hello",
    ) = SendAttempt(
        peerAddress = PEER,
        text = text,
        amountZatoshi = AMOUNT,
        rowTimeMillis = rowTimeMillis,
        markerMillis = if (legacy) null else rowTimeMillis + 1_000L,
        attemptHeight = if (legacy) null else attemptHeight,
        txIds = txIds,
        expiries = expiries,
        legacy = legacy,
        anchorHeight = anchor,
        otherProcessSameAccount = otherProcessSameAccount,
    )

    private fun resolve(
        attempt: SendAttempt,
        tip: Long?,
        vararg txs: OwnTransaction,
        claimed: Set<String> = emptySet(),
    ) = SendAttemptResolver.resolve(attempt, ChainView(tip, txs.toList(), claimed))

    // ── rows with ids ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown scanned tip decides nothing`() {
        assertEquals(SendAttemptVerdict.Unknown, resolve(attempt(listOf("t1")), null, tx("t1")))
    }

    @Test
    fun `a mined transaction is sent`() {
        assertEquals(SendAttemptVerdict.Sent, resolve(attempt(listOf("t1")), E + 100, tx("t1", mined = H + 3)))
    }

    @Test
    fun `an unmined transaction is dead only at its expiry plus 10`() {
        assertEquals(SendAttemptVerdict.Waiting(E, E + 9), resolve(attempt(listOf("t1")), E + 9, tx("t1")))
        assertEquals(SendAttemptVerdict.NotSent, resolve(attempt(listOf("t1")), E + 10, tx("t1")))
    }

    @Test
    fun `an expiry of 0 is never dead`() {
        assertEquals(SendAttemptVerdict.Waiting(null, E + 10_000), resolve(attempt(listOf("t1")), E + 10_000, tx("t1", expiry = 0)))
    }

    @Test
    fun `a missing transaction with its recorded expiry is dead only when the wallet holds others`() {
        val a = attempt(listOf("t1"), expiries = mapOf("t1" to E))
        assertEquals(SendAttemptVerdict.NotSent, resolve(a, E + 10, tx("other", recipients = listOf(OTHER))))
        assertEquals(SendAttemptVerdict.Waiting(E, E + 10), resolve(a, E + 10))
    }

    @Test
    fun `a missing transaction with no recorded expiry waits for the anchor plus 52`() {
        val a = attempt(listOf("t1"), anchor = H)
        val someTx = tx("other", recipients = listOf(OTHER))
        assertEquals(SendAttemptVerdict.Waiting(null, H + 51), resolve(a, H + 51, someTx))
        assertEquals(SendAttemptVerdict.NotSent, resolve(a, H + 52, someTx))
        assertEquals(SendAttemptVerdict.Waiting(null, H + 5_000), resolve(attempt(listOf("t1"), anchor = null), H + 5_000, someTx))
    }

    @Test
    fun `mined and dead legs are partial, and a live leg waits`() {
        val a = attempt(listOf("t1", "t2"))
        assertEquals(SendAttemptVerdict.Partial, resolve(a, E + 10, tx("t1", mined = H + 2), tx("t2")))
        assertEquals(SendAttemptVerdict.Waiting(E, E + 9), resolve(a, E + 9, tx("t1", mined = H + 2), tx("t2")))
    }

    @Test
    fun `the SDK's own expiry in the wallet wins over the recorded one`() {
        val a = attempt(listOf("t1"), expiries = mapOf("t1" to E - 20))
        assertEquals(SendAttemptVerdict.Waiting(E, E - 5), resolve(a, E - 5, tx("t1")))
    }

    // ── rows with no id ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a live candidate has no upper bound`() {
        val far = tx("c", expiry = H + 5_000)
        assertEquals(SendAttemptVerdict.Waiting(H + 5_000, H + 4_000), resolve(attempt(), H + 4_000, far))
        assertEquals(SendAttemptVerdict.Waiting(H + 5_000, H + 5_009), resolve(attempt(), H + 5_009, far))
        assertEquals(SendAttemptVerdict.NotSent, resolve(attempt(), H + 5_010, far))
    }

    @Test
    fun `a mined candidate is never sent again`() {
        assertEquals(SendAttemptVerdict.MinedUnattributed, resolve(attempt(), H + 500, tx("c", mined = H + 5)))
    }

    @Test
    fun `what is not a candidate`() {
        val a = attempt()
        val tip = H + 52
        // Another contact, a smaller amount, mined before the attempt's height - 100, claimed by another row, received,
        // or a readably different message: none of them blocks, so the anchor rule decides.
        assertEquals(SendAttemptVerdict.NotSent, resolve(a, tip, tx("c", mined = H + 1, recipients = listOf(OTHER))))
        assertEquals(SendAttemptVerdict.NotSent, resolve(a, tip, tx("c", mined = H + 1, value = AMOUNT - 1)))
        assertEquals(SendAttemptVerdict.NotSent, resolve(a, tip, tx("c", mined = H - 101)))
        assertEquals(SendAttemptVerdict.NotSent, resolve(a, tip, tx("c", mined = H + 1), claimed = setOf("c")))
        assertEquals(SendAttemptVerdict.NotSent, resolve(a, tip, tx("c", mined = H + 1, isSent = false)))
        assertEquals(SendAttemptVerdict.NotSent, resolve(a, tip, tx("c", mined = H + 1, text = "synthetic another message")))
    }

    @Test
    fun `any doubt is a candidate`() {
        val a = attempt()
        val tip = H + 52
        // An unreadable recipient, a text that only differs in spacing or is cut, a mined height 100 before.
        assertEquals(SendAttemptVerdict.MinedUnattributed, resolve(a, tip, tx("c", mined = H + 1, recipients = null)))
        assertEquals(SendAttemptVerdict.MinedUnattributed, resolve(a, tip, tx("c", mined = H + 1, text = " synthetic  resolver hello ")))
        assertEquals(SendAttemptVerdict.MinedUnattributed, resolve(a, tip, tx("c", mined = H + 1, text = "synthetic resolver")))
        assertEquals(SendAttemptVerdict.MinedUnattributed, resolve(a, tip, tx("c", mined = H - 100)))
    }

    @Test
    fun `no candidate waits for the release anchor plus 52`() {
        assertEquals(SendAttemptVerdict.Waiting(null, H + 51), resolve(attempt(anchor = H), H + 51, tx("x", recipients = listOf(OTHER))))
        assertEquals(SendAttemptVerdict.NotSent, resolve(attempt(anchor = H), H + 52, tx("x", recipients = listOf(OTHER))))
        assertEquals(SendAttemptVerdict.Waiting(null, H + 10_000), resolve(attempt(anchor = null), H + 10_000))
    }

    // ── K1 (review point 3) ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `K1 - another process of the same account with a non-empty wallet and no candidate is not sent`() {
        val unrelated = tx("x", mined = H - 500, recipients = listOf(OTHER))
        assertEquals(SendAttemptVerdict.NotSent, resolve(attempt(otherProcessSameAccount = true), H + 1, unrelated))
    }

    @Test
    fun `K1 - an empty wallet or another account waits for the anchor plus 52`() {
        assertEquals(SendAttemptVerdict.Waiting(null, H + 1), resolve(attempt(otherProcessSameAccount = true), H + 1))
        val unrelated = tx("x", mined = H - 500, recipients = listOf(OTHER))
        assertEquals(SendAttemptVerdict.Waiting(null, H + 1), resolve(attempt(otherProcessSameAccount = false), H + 1, unrelated))
        assertEquals(SendAttemptVerdict.NotSent, resolve(attempt(otherProcessSameAccount = false), H + 52, unrelated))
    }

    @Test
    fun `K1 - a candidate still blocks`() {
        assertEquals(SendAttemptVerdict.Waiting(E, H + 1), resolve(attempt(otherProcessSameAccount = true), H + 1, tx("c")))
    }

    // ── 2.12.4 rows (review point 5) ─────────────────────────────────────────────────────────────────

    @Test
    fun `a legacy row's mined candidate counts only after the row and up to the cutover plus 42`() {
        val row = 10_000_000L
        val a = attempt(legacy = true, anchor = H, rowTimeMillis = row)
        val tip = H + 52
        val allowance = SendAttemptRules.CLOCK_ALLOWANCE_MS
        assertEquals(SendAttemptVerdict.MinedUnattributed, resolve(a, tip, tx("c", mined = H + 42, blockTimeMillis = row - allowance)))
        assertEquals(SendAttemptVerdict.NotSent, resolve(a, tip, tx("c", mined = H + 43, blockTimeMillis = row)))
        assertEquals(SendAttemptVerdict.NotSent, resolve(a, tip, tx("c", mined = H + 10, blockTimeMillis = row - allowance - 1)))
    }

    @Test
    fun `a legacy row never takes the K1 fast path and waits for the cutover plus 52`() {
        val a = attempt(legacy = true, anchor = H, otherProcessSameAccount = true)
        val unrelated = tx("x", mined = H - 500, recipients = listOf(OTHER))
        assertEquals(SendAttemptVerdict.Waiting(null, H + 51), resolve(a, H + 51, unrelated))
        assertEquals(SendAttemptVerdict.NotSent, resolve(a, H + 52, unrelated))
    }

    // ── the proof and the text ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the proof needs a second sighting at a strictly higher tip`() {
        val key = SendAttemptProof.key("synthetic-proof-row-" + System.nanoTime(), 1L)
        assertFalse(SendAttemptProof.observe(key, SendAttemptVerdict.NotSent, E + 10))
        assertFalse(SendAttemptProof.observe(key, SendAttemptVerdict.NotSent, E + 10))
        assertFalse(SendAttemptProof.observe(key, SendAttemptVerdict.NotSent, E + 9))
        assertTrue(SendAttemptProof.observe(key, SendAttemptVerdict.NotSent, E + 11))
        // Any other verdict starts it again.
        assertFalse(SendAttemptProof.observe(key, SendAttemptVerdict.Waiting(E, E + 12), E + 12))
        assertFalse(SendAttemptProof.observe(key, SendAttemptVerdict.NotSent, E + 13))
        assertFalse(SendAttemptProof.observe(key, SendAttemptVerdict.Partial, E + 14))
        assertTrue(SendAttemptProof.observe(key, SendAttemptVerdict.Partial, E + 15))
        assertFalse(SendAttemptProof.observe(key, SendAttemptVerdict.Unknown, E + 16))
        assertFalse(SendAttemptProof.observe(key, SendAttemptVerdict.NotSent, null))
    }

    @Test
    fun `the minutes until a transaction counts as dead`() {
        assertEquals(63, SendAttemptRules.minutesUntilDead(expiry = E, tip = E - 40))
        assertEquals(2, SendAttemptRules.minutesUntilDead(expiry = E, tip = E + 9))
        assertEquals(2, SendAttemptRules.minutesUntilDead(expiry = E, tip = E + 20))
    }
}
