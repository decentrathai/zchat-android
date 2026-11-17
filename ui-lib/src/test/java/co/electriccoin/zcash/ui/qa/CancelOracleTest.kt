package co.electriccoin.zcash.ui.qa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM cancel-oracle unit tests (PaymentDiag event-stream shape).
 *
 * No Android runtime, no phones, no Gradle product mutation beyond compiling this
 * test source. Negative control deliberately emits two submissions and MUST FAIL.
 */
class CancelOracleTest {
    private val userPid = "3:9f2c41aa:10000:Regular"
    private val lpk = "10000"

    private fun cancelHappy(): List<CancelOracle.Event> =
        listOf(
            CancelOracle.Event("proposal_created", pid = userPid, logicalSendId = lpk, ts = 10.0),
            CancelOracle.Event("proposal_displayed", pid = userPid, ts = 10.5),
            CancelOracle.Event("biometric_shown", ts = 11.0),
            CancelOracle.Event("biometric_result", ok = false, reason = "cancelled", ts = 15.0),
        )

    @Test
    fun `cancel before submit happy path passes`() {
        val results = CancelOracle.evaluateCancelBeforeSubmit(cancelHappy())
        assertEquals(CancelOracle.Verdict.PASSED, CancelOracle.combine(results))
    }

    @Test
    fun `unexpected submit on cancel path fails`() {
        val events = cancelHappy() + CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 16.0)
        val results = CancelOracle.evaluateCancelBeforeSubmit(events)
        assertEquals(CancelOracle.Verdict.FAILED, CancelOracle.combine(results))
        assertTrue(results.any { it.id == "co_no_submit" && it.verdict == CancelOracle.Verdict.FAILED })
    }

    @Test
    fun `cancel before create stays empty`() {
        val results = CancelOracle.evaluateCancelBeforeCreate(emptyList())
        assertEquals(CancelOracle.Verdict.PASSED, CancelOracle.combine(results))
    }

    @Test
    fun `cancel before create fails if a submit appears`() {
        val events = listOf(CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 1.0))
        val results = CancelOracle.evaluateCancelBeforeCreate(events)
        assertEquals(CancelOracle.Verdict.FAILED, CancelOracle.combine(results))
    }

    @Test
    fun `soft-fail missing bio result is INFRA not FAILED when not required`() {
        val events =
            listOf(
                CancelOracle.Event("proposal_created", pid = userPid, ts = 10.0),
                CancelOracle.Event("proposal_displayed", pid = userPid, ts = 10.5),
                CancelOracle.Event("biometric_shown", ts = 11.0),
            )
        val results = CancelOracle.evaluateCancelBeforeSubmit(events, requireBioResult = false)
        assertEquals(CancelOracle.Verdict.INFRA, CancelOracle.combine(results))
        assertTrue(results.none { it.id == "co_no_submit" && it.verdict == CancelOracle.Verdict.FAILED })
    }

    @Test
    fun `retry after cancel with single submit passes`() {
        val events =
            cancelHappy() +
                listOf(
                    CancelOracle.Event("biometric_shown", ts = 20.0),
                    CancelOracle.Event("biometric_result", ok = true, ts = 21.0),
                    CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 22.0),
                )
        val results = CancelOracle.evaluateRetryAfterCancel(events, logicalSendId = lpk)
        assertEquals(CancelOracle.Verdict.PASSED, CancelOracle.combine(results))
    }

    @Test
    fun `NEGATIVE CONTROL - two submissions for one logical send must FAIL`() {
        val events =
            listOf(
                CancelOracle.Event("proposal_created", pid = userPid, logicalSendId = lpk, ts = 10.0),
                CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 12.0),
                CancelOracle.Event("proposal_submitted", pid = "4:9f2c41aa:10000:Regular", logicalSendId = lpk, ts = 13.0),
            )
        val results = CancelOracle.evaluateNegativeDoubleSubmit(events, logicalSendId = lpk)
        assertEquals(
            "negative control: oracle must detect double-submit",
            CancelOracle.Verdict.FAILED,
            CancelOracle.combine(results),
        )
        assertNotEquals(CancelOracle.Verdict.PASSED, CancelOracle.combine(results))
    }

    @Test
    fun `retry after cancel with two post-cancel submits fails rebroadcast`() {
        val events =
            cancelHappy() +
                listOf(
                    CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 22.0),
                    CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 23.0),
                )
        val results = CancelOracle.evaluateRetryAfterCancel(events, logicalSendId = lpk)
        assertEquals(CancelOracle.Verdict.FAILED, CancelOracle.combine(results))
        assertTrue(results.any { it.id == "co_retry_at_most_one" && it.verdict == CancelOracle.Verdict.FAILED })
    }

    // Round 7 (GPT Q5, Grok Q5, H5 — CANCEL-ORACLE-ASSURANCE): the at-most-one-submit oracle must not certify
    // what it could not see. A blind capture that shows one submit cannot exclude a second, unobserved one; and
    // submits that carry no correlation to the logical send cannot be counted in or out, so neither may PASS.

    @Test
    fun `blind capture with one submit is INFRA, never PASSED`() {
        val events = listOf(CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 12.0))
        val results = CancelOracle.evaluateAtMostOneSubmit(events, logicalSendId = lpk, captureHealthy = false)
        assertEquals(
            "a blind capture cannot exclude a second, unobserved submit: $results",
            CancelOracle.Verdict.INFRA,
            CancelOracle.combine(results),
        )
    }

    @Test
    fun `two uncorrelated submits for a correlated logical send are INFRA, never PASSED`() {
        val events =
            listOf(
                CancelOracle.Event("proposal_submitted", ts = 12.0),
                CancelOracle.Event("proposal_submitted", pid = CancelOracle.UNKNOWN_PID, ts = 13.0),
            )
        val results = CancelOracle.evaluateAtMostOneSubmit(events, logicalSendId = "L")
        assertEquals(
            "two submits that name no logical send may both belong to L, so at-most-one cannot be certified: $results",
            CancelOracle.Verdict.INFRA,
            CancelOracle.combine(results),
        )
    }

    @Test
    fun `control - two correlated submits stay FAILED, also when the capture is blind`() {
        val events =
            listOf(
                CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 12.0),
                CancelOracle.Event("proposal_submitted", pid = "4:9f2c41aa:10000:Regular", logicalSendId = lpk, ts = 13.0),
            )
        for (healthy in listOf(true, false)) {
            val results = CancelOracle.evaluateAtMostOneSubmit(events, logicalSendId = lpk, captureHealthy = healthy)
            assertEquals(
                "captureHealthy=$healthy: two observed submits are proof",
                CancelOracle.Verdict.FAILED,
                CancelOracle.combine(results),
            )
        }
    }

    @Test
    fun `control - one correlated submit on a healthy capture still passes, and another send's submit is not counted`() {
        val events =
            listOf(
                CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 12.0),
                CancelOracle.Event("proposal_submitted", pid = "5:77aa01bb:20000:Regular", logicalSendId = "20000", ts = 13.0),
            )
        val results = CancelOracle.evaluateAtMostOneSubmit(events, logicalSendId = lpk)
        assertEquals(CancelOracle.Verdict.PASSED, CancelOracle.combine(results))
    }

    @Test
    fun `approved biometric on cancel path fails co_bio_cancelled`() {
        val events =
            listOf(
                CancelOracle.Event("proposal_created", pid = userPid, ts = 10.0),
                CancelOracle.Event("proposal_displayed", pid = userPid, ts = 10.5),
                CancelOracle.Event("biometric_shown", ts = 11.0),
                CancelOracle.Event("biometric_result", ok = true, ts = 15.0),
            )
        val results = CancelOracle.evaluateCancelBeforeSubmit(events)
        assertEquals(CancelOracle.Verdict.FAILED, CancelOracle.combine(results))
        assertTrue(results.any { it.id == "co_bio_cancelled" && it.verdict == CancelOracle.Verdict.FAILED })
    }

    // Round 7c (R6-4 review finding 20): a pid names the logical send only by one of its ':'-separated fields
    // (seq:dest_fp:amountZat:kind). A field that merely CONTAINS the id is another send: amount 100000 is not
    // amount 10000, and 'xab1' is not 'ab'. The substring match over-counted such a submit as this send's.

    @Test
    fun `a submit whose pid field only contains the id is not correlated`() {
        val events =
            listOf(
                CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 12.0),
                // Untagged, amount field 100000: it names no logical send of amount 10000.
                CancelOracle.Event("proposal_submitted", pid = "7:9f2c41aa:100000:Regular", ts = 13.0),
            )
        val results = CancelOracle.evaluateAtMostOneSubmit(events, logicalSendId = lpk)
        assertEquals(
            "one correlated submit plus one that names no logical send is INFRA, not a proven double submit",
            CancelOracle.Verdict.INFRA,
            CancelOracle.combine(results),
        )
    }

    @Test
    fun `an id inside a longer pid field is not correlated`() {
        val events =
            listOf(
                CancelOracle.Event("proposal_submitted", pid = "1:ff00ff00:ab:Zip321", logicalSendId = "ab", ts = 12.0),
                CancelOracle.Event("proposal_submitted", pid = "2:ff00ff00:xab1:Zip321", ts = 13.0),
            )
        val results = CancelOracle.evaluateAtMostOneSubmit(events, logicalSendId = "ab")
        assertEquals(CancelOracle.Verdict.INFRA, CancelOracle.combine(results))
    }

    @Test
    fun `another send's submit whose pid only contains the id is not counted`() {
        val events =
            listOf(
                CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 12.0),
                CancelOracle.Event(
                    "proposal_submitted",
                    pid = "5:77aa01bb:100000:Regular",
                    logicalSendId = "100000",
                    ts = 13.0,
                ),
            )
        val results = CancelOracle.evaluateAtMostOneSubmit(events, logicalSendId = lpk)
        assertEquals(CancelOracle.Verdict.PASSED, CancelOracle.combine(results))
    }

    @Test
    fun `retry after cancel does not count a post-cancel submit whose pid only contains the id`() {
        val events =
            cancelHappy() +
                listOf(
                    CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 22.0),
                    CancelOracle.Event("proposal_submitted", pid = "8:9f2c41aa:100000:Regular", ts = 23.0),
                )
        val results = CancelOracle.evaluateRetryAfterCancel(events, logicalSendId = lpk)
        assertTrue(
            "the retry filter must use the same exact-field correlation: $results",
            results.any { it.id == "co_retry_at_most_one" && it.verdict == CancelOracle.Verdict.PASSED },
        )
        assertEquals(CancelOracle.Verdict.INFRA, CancelOracle.combine(results))
    }

    @Test
    fun `control - an untagged submit whose pid field is exactly the id is correlated`() {
        val events =
            listOf(
                CancelOracle.Event("proposal_submitted", pid = userPid, logicalSendId = lpk, ts = 12.0),
                CancelOracle.Event("proposal_submitted", pid = "4:9f2c41aa:10000:Regular", ts = 13.0),
            )
        val results = CancelOracle.evaluateAtMostOneSubmit(events, logicalSendId = lpk)
        assertEquals(CancelOracle.Verdict.FAILED, CancelOracle.combine(results))

        val retry =
            CancelOracle.evaluateRetryAfterCancel(
                cancelHappy() +
                    listOf(
                        CancelOracle.Event("proposal_submitted", pid = "5:9f2c41aa:10000:Regular", ts = 22.0),
                        CancelOracle.Event("proposal_submitted", pid = "6:9f2c41aa:10000:Regular", ts = 23.0),
                    ),
                logicalSendId = lpk,
            )
        assertTrue(retry.any { it.id == "co_retry_at_most_one" && it.verdict == CancelOracle.Verdict.FAILED })
    }
}
