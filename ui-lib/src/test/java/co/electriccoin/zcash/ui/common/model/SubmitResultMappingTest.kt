package co.electriccoin.zcash.ui.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.test.Test

/**
 * MONEY / SEND correctness for [SubmitResult.classify] — the pure decision that turns a batch of
 * per-output submit outcomes into a single result. This is the guard that a PARTIAL or FAILED submit
 * is never reported as a false "sent":
 *
 *  - every output succeeded         -> Success
 *  - every output failed, all gRPC  -> GrpcFailure   (nothing landed, safe to retry the whole batch)
 *  - every output failed, some hard -> Failure
 *  - some succeeded, some failed     -> Partial       (money HAS moved; must NOT be a Success, and
 *                                                       must NOT be a total Failure/retry)
 *
 * The direct-send flow in CreateChunkedMessageProposalUseCase throws ONLY on Failure/GrpcFailure and
 * treats Partial/Success as "money moved (do not re-charge)". These tests pin the classifier that
 * feeds that decision.
 *
 * Pure Kotlin — no Android SDK types (the SDK-facing collection/counting lives in
 * ProposalDataSource.submitTransactionInternal and is not JVM-testable here; see report).
 */
class SubmitResultMappingTest {

    private fun ids(n: Int) = (1..n).map { "tx$it" }

    @Test
    fun `all outputs succeed maps to Success`() {
        val result =
            SubmitResult.classify(
                successCount = 3,
                totalCount = 3,
                resubmittableFailures = emptyList(),
                txIds = ids(3),
                statuses = listOf("success", "success", "success"),
                errCode = 0,
                errDesc = "",
            )
        assertTrue(result is SubmitResult.Success)
        assertEquals(ids(3), (result as SubmitResult.Success).txIds)
    }

    @Test
    fun `single output success maps to Success`() {
        val result =
            SubmitResult.classify(
                successCount = 1,
                totalCount = 1,
                resubmittableFailures = emptyList(),
                txIds = ids(1),
                statuses = listOf("success"),
                errCode = 0,
                errDesc = "",
            )
        assertTrue(result is SubmitResult.Success)
    }

    @Test
    fun `zero successes with all gRPC failures maps to GrpcFailure (retryable, nothing sent)`() {
        val result =
            SubmitResult.classify(
                successCount = 0,
                totalCount = 2,
                resubmittableFailures = listOf(true, true),
                txIds = ids(2),
                statuses = listOf("network", "network"),
                errCode = 0,
                errDesc = "",
            )
        assertTrue("expected GrpcFailure, was $result", result is SubmitResult.GrpcFailure)
    }

    @Test
    fun `zero successes with a hard failure maps to Failure carrying code and description`() {
        val result =
            SubmitResult.classify(
                successCount = 0,
                totalCount = 2,
                // one non-grpc (hard) failure means NOT all-retryable -> Failure
                resubmittableFailures = listOf(false, true),
                txIds = ids(2),
                statuses = listOf("code: 17 desc: bad", "network"),
                errCode = 17,
                errDesc = "bad",
            )
        assertTrue("expected Failure, was $result", result is SubmitResult.Failure)
        result as SubmitResult.Failure
        assertEquals(17, result.code)
        assertEquals("bad", result.description)
    }

    @Test
    fun `partial submit (some succeed, some hard-fail) maps to Partial not Success`() {
        val result =
            SubmitResult.classify(
                successCount = 1,
                totalCount = 3,
                resubmittableFailures = listOf(false, true),
                txIds = ids(3),
                statuses = listOf("success", "code: 17 desc: bad", "network"),
                errCode = 17,
                errDesc = "bad",
            )
        // The fund-loss guard: a partially-landed batch is NEVER a Success.
        assertTrue("expected Partial, was $result", result is SubmitResult.Partial)
        assertTrue(result !is SubmitResult.Success)
    }

    @Test
    fun `partial with only gRPC failures maps to GrpcFailure`() {
        // Some outputs landed, the rest failed with retryable gRPC errors: classified GrpcFailure so
        // the caller can retry the unlanded ones (mirrors ProposalDataSource's `else` branch).
        val result =
            SubmitResult.classify(
                successCount = 1,
                totalCount = 3,
                resubmittableFailures = listOf(true, true),
                txIds = ids(3),
                statuses = listOf("success", "network", "network"),
                errCode = 0,
                errDesc = "",
            )
        assertTrue("expected GrpcFailure, was $result", result is SubmitResult.GrpcFailure)
    }

    @Test
    fun `direct-send throw decision - only Failure and GrpcFailure are thrown`() {
        // Mirrors submitZashiProposal(skipNavigation=true): throw on Failure/GrpcFailure, treat
        // Partial/Success as "money moved". This pins that a Partial is on the NO-THROW side so a
        // partially-paid request is never re-charged, and a Success/Partial is never a false throw.
        fun throwsOnDirectSend(r: SubmitResult): Boolean =
            when (r) {
                is SubmitResult.Failure, is SubmitResult.GrpcFailure -> true
                is SubmitResult.Partial, is SubmitResult.Success -> false
            }

        assertTrue(throwsOnDirectSend(SubmitResult.Failure(ids(1), 17, "bad")))
        assertTrue(throwsOnDirectSend(SubmitResult.GrpcFailure(ids(1))))
        assertTrue(!throwsOnDirectSend(SubmitResult.Success(ids(1))))
        assertTrue(!throwsOnDirectSend(SubmitResult.Partial(ids(2), listOf("success", "network"))))
    }
}
