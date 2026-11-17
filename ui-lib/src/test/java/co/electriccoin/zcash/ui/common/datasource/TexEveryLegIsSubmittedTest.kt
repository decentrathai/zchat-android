package co.electriccoin.zcash.ui.common.datasource

import co.electriccoin.zcash.ui.common.fake.RecordingBroadcaster
import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * F2 (fix-all 2.13.0, train T2): every leg of a send is handed to the server, and a leg that fails does not strand the
 * next one (TEX leg 2 never sent).
 *
 * A TEX send is two transactions: leg 1 moves the funds to an ephemeral transparent address, leg 2 pays the exchange
 * from it. ZChat sends through `SdkSynchronizer.createProposedTransactions`, whose submit flow stops at the first leg
 * that fails: leg 2 is reported NotAttempted and never reaches `Broadcaster.submit`, so it never gets a submit
 * endpoint, stays AwaitingPlan, and the SDK's resubmission on sync skips it. Leg 1 is rebroadcast and can land, and
 * the funds then sit at the ephemeral address while the exchange is never paid. SDK 3.0.0's public [Broadcaster]
 * separates create from submit; Zodl submits every created transaction, catching a throw per leg as a gRPC failure.
 *
 * Pinned here, on the F0 [RecordingBroadcaster]:
 *  - leg 1 fails on the transport and lightwalletd then refuses leg 2 for its missing input: BOTH legs were submitted,
 *    so both have a submit plan the SDK keeps rebroadcasting, and the send is "not delivered yet", never a Failure;
 *  - leg 1's submit throws (an IOException): leg 2 is still submitted and the send stays "maybe sent" with BOTH ids;
 *  - one transaction whose submit throws is "maybe sent" with its id: the same waiting class as today's
 *    SentUnconfirmed, never a Failure that re-arms a retry;
 *  - a cancellation is not swallowed, and nothing after it is submitted;
 *  - controls: a clean one- and two-leg send is a Success; a leg the server itself rejected is still a Failure.
 *
 * Every txid is synthetic.
 */
class TexEveryLegIsSubmittedTest {
    private val leg1 = RecordingBroadcaster.leg(seed = 0x31, expiryHeight = 3_100_040)
    private val leg2 = RecordingBroadcaster.leg(seed = 0x32, expiryHeight = 3_100_040)
    private val id1 = leg1.txIdString()
    private val id2 = leg2.txIdString()

    @Test
    fun `leg 2 is submitted after leg 1 fails on the transport, and the send is not delivered yet`() =
        runBlocking {
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))
            broadcaster.answerSubmit(id1) { RecordingBroadcaster.grpcFailure(it) }
            // lightwalletd refuses leg 2 while leg 1 is not in its mempool: leg 2 spends leg 1's output.
            broadcaster.answerSubmit(id2) { F2SubmitPath.missingInputs(it) }

            val result = F2SubmitPath.createStoreAndSubmit(broadcaster)

            assertEquals(
                listOf(id1, id2),
                broadcaster.submitted(),
                "every leg must reach the server once, so the SDK keeps a submit plan for each and rebroadcasts both",
            )
            assertEquals(
                SubmitResult.GrpcFailure(txIds = listOf(id1, id2)),
                result,
                "leg 2 was refused only because leg 1 is not there yet; both are rebroadcast, so this is " +
                    "'not delivered yet', never 'Send Failed'",
            )
        }

    @Test
    fun `leg 1 throwing still submits leg 2, and the send stays maybe-sent with both ids`() =
        runBlocking {
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))
            broadcaster.answerSubmit(id1) { throw IOException("synthetic socket closed") }
            broadcaster.answerSubmit(id2) { F2SubmitPath.missingInputs(it) }

            val result = F2SubmitPath.createStoreAndSubmit(broadcaster)

            assertEquals(listOf(id1, id2), broadcaster.submitted(), "a throw on leg 1 must not strand leg 2")
            assertIs<SubmitResult.SentUnconfirmed>(result, "a submit that threw may have reached the server: $result")
            assertEquals(listOf(id1, id2), result.txIds, "every created id is kept")
        }

    @Test
    fun `one transaction whose submit throws is maybe-sent with its id, never a Failure`() =
        runBlocking {
            val broadcaster = RecordingBroadcaster(listOf(leg1))
            broadcaster.answerSubmit(id1) { throw IOException("synthetic client dispose failure after the send") }

            val result = F2SubmitPath.createStoreAndSubmit(broadcaster)

            assertTrue(result !is SubmitResult.Failure, "a throw is not proof that nothing was sent: $result")
            assertIs<SubmitResult.SentUnconfirmed>(result, "same waiting class as today's collect-throw: $result")
            assertEquals(listOf(id1), result.txIds, "the id is kept, so the attempt can be tracked")
        }

    @Test
    fun `a cancellation during a submit propagates, and nothing after it is submitted`() =
        runBlocking {
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))
            broadcaster.answerSubmit(id1) { throw CancellationException("synthetic cancel") }

            assertFailsWith<CancellationException> { F2SubmitPath.createStoreAndSubmit(broadcaster) }
            assertEquals(listOf(id1), broadcaster.submitted())
        }

    /** Control, green once the path exists: a clean single send. */
    @Test
    fun `control - one transaction accepted is a Success`() =
        runBlocking {
            val broadcaster = RecordingBroadcaster(listOf(leg1))

            val result = F2SubmitPath.createStoreAndSubmit(broadcaster)

            assertEquals(SubmitResult.Success(txIds = listOf(id1)), result)
            assertEquals(listOf("create", "submit:$id1"), broadcaster.events.toList())
        }

    /** Control: both legs accepted. */
    @Test
    fun `control - both legs accepted is a Success`() =
        runBlocking {
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))

            val result = F2SubmitPath.createStoreAndSubmit(broadcaster)

            assertEquals(SubmitResult.Success(txIds = listOf(id1, id2)), result)
        }

    /** Control: a leg the server itself rejected is still a Failure that keeps every id, as today. */
    @Test
    fun `control - a leg the server rejected is still a Failure`() =
        runBlocking {
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))
            broadcaster.answerSubmit(id1) { F2SubmitPath.rejected(it) }
            broadcaster.answerSubmit(id2) { F2SubmitPath.missingInputs(it) }

            val result = F2SubmitPath.createStoreAndSubmit(broadcaster)

            assertEquals(listOf(id1, id2), broadcaster.submitted())
            assertIs<SubmitResult.Failure>(result, "the server answered leg 1 with a rejection: $result")
            assertEquals(listOf(id1, id2), result.txIds)
            assertEquals(F2SubmitPath.REJECTED, result.code, "the rejection of leg 1 is the reason, not leg 2's echo")
        }
}
