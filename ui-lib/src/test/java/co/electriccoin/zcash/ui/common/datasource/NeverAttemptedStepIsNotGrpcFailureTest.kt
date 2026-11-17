package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 8 D3 (sweep #27 / O-1, honesty part): a batch whose later step was never attempted is not
 * "Connection Issue — ZCHAT will retry in the next few minutes".
 *
 * A TEX send is two transactions: leg 1 moves the funds to an ephemeral transparent address, leg 2 pays the
 * TEX recipient from it. When leg 1's submit fails with a gRPC/transport error, SDK 3.0.0 does not attempt leg 2
 * and reports it `TransactionSubmitResult.NotAttempted`. At 68455b60d `settleSubmitFlow` leaves NotAttempted out
 * of `resubmittableFailures`, so `[Failure(grpcError = true), NotAttempted]` gives `[true]`, `all { it }` holds and
 * the batch becomes [SubmitResult.GrpcFailure], whose screen says ZCHAT will retry. Nothing retries leg 2: the SDK
 * resubmits only stored transactions that already have a submit endpoint, and a never-attempted one is still
 * `AwaitingPlan` (`CompactBlockProcessor.resubmitTransaction` skips it). So a never-attempted step must count as
 * not resubmittable: the batch is a [SubmitResult.Failure] that keeps every id.
 *
 * Control: when every step failed on the transport, the batch is still a [SubmitResult.GrpcFailure].
 * Transaction ids are synthetic bytes.
 */
class NeverAttemptedStepIsNotGrpcFailureTest {
    @Test
    fun `a transport failure followed by a never-attempted step is a Failure, not GrpcFailure`() =
        runBlocking {
            val result =
                settleSubmitFlow {
                    flowOf(
                        TransactionSubmitResult.Failure(LEG_1, true, GRPC_UNAVAILABLE, "synthetic transport failure"),
                        TransactionSubmitResult.NotAttempted(LEG_2),
                    )
                }

            assertTrue(
                result !is SubmitResult.GrpcFailure,
                "a batch with a step that was never attempted is reported as 'will retry': $result",
            )
            assertTrue(result is SubmitResult.Failure, "expected a Failure, got $result")
            assertEquals(listOf(LEG_1.id(), LEG_2.id()), result.txIds, "both stored ids are kept")
        }

    @Test
    fun `a batch that was never attempted at all is not GrpcFailure either`() =
        runBlocking {
            val result = settleSubmitFlow { flowOf(TransactionSubmitResult.NotAttempted(LEG_1)) }

            assertTrue(result is SubmitResult.Failure, "expected a Failure, got $result")
            assertEquals(listOf(LEG_1.id()), result.txIds)
        }

    /** Control, green before and after. */
    @Test
    fun `control - every step failing on the transport is still GrpcFailure`() =
        runBlocking {
            val result =
                settleSubmitFlow {
                    flowOf(
                        TransactionSubmitResult.Failure(LEG_1, true, GRPC_UNAVAILABLE, "synthetic transport failure"),
                        TransactionSubmitResult.Failure(LEG_2, true, GRPC_UNAVAILABLE, "synthetic transport failure"),
                    )
                }

            assertEquals(SubmitResult.GrpcFailure(txIds = listOf(LEG_1.id(), LEG_2.id())), result)
        }

    /** Control, green before and after: a landed step with a never-attempted one stays Partial. */
    @Test
    fun `control - a landed step with a never-attempted one is still Partial`() =
        runBlocking {
            val result =
                settleSubmitFlow {
                    flowOf(TransactionSubmitResult.Success(LEG_1), TransactionSubmitResult.NotAttempted(LEG_2))
                }

            assertTrue(result is SubmitResult.Partial, "expected Partial, got $result")
        }

    private companion object {
        const val GRPC_UNAVAILABLE = 14

        val LEG_1 = FirstClassByteArray(ByteArray(32) { 0x1A })

        val LEG_2 = FirstClassByteArray(ByteArray(32) { 0x2B })

        fun FirstClassByteArray.id(): String = TransactionSubmitResult.Success(this).txIdString()
    }
}
