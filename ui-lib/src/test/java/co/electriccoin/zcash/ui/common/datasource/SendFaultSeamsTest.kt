package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.fake.RecordingBroadcaster
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * F2 (fix-all 2.13.0, train T2): the three send fault switches F0 made armable are read by the one submit path
 * (train-T1-2026/REPORT.md, F0 notes: "F2 must wire 3"). Debug builds only; DebugFaultSeamsReleaseAbsenceTest pins
 * the release side.
 *
 *  - SUBMIT_LEG1_GRPC_FAILURE: the first submit of leg 1 does not reach the network in a readable form — the server
 *    is handed a cut copy of its bytes, which it cannot parse, so the SDK still records the leg's submit endpoint and
 *    rebroadcasts the real, stored bytes on its next sync — and the attempt reads it as a gRPC failure. Every later leg
 *    is submitted as usual. This is the phone's "leg 1 failed, then both legs mined" check.
 *  - KEEP_LEG2_AWAITING_PLAN: every leg after the first is never submitted (the pre-F2 stranding, kept for F3's checks).
 *  - KILL_AFTER_CREATE_BEFORE_ON_STORED: the process ends after create, before the record and before any submit.
 *
 * Control: with nothing armed, create, the record and both submits happen. Every txid is synthetic.
 */
class SendFaultSeamsTest {
    private val leg1 = RecordingBroadcaster.leg(seed = 0x51, expiryHeight = 3_300_040)
    private val leg2 = RecordingBroadcaster.leg(seed = 0x52, expiryHeight = 3_300_040)
    private val id1 = leg1.txIdString()
    private val id2 = leg2.txIdString()

    private val realKiller = FaultSeams.killer

    @Before
    fun clean() = FaultSeams.disarmAll()

    @After
    fun restore() {
        FaultSeams.disarmAll()
        FaultSeams.killer = realKiller
    }

    @Test
    fun `SUBMIT_LEG1_GRPC_FAILURE - leg 1's first submit is unreadable and reads as gRPC, leg 2 still goes`() =
        runBlocking {
            FaultSeams.arm(FaultSeam.SUBMIT_LEG1_GRPC_FAILURE, 1)
            val broadcaster = RawRecordingBroadcaster(RecordingBroadcaster(listOf(leg1, leg2)))
            // With leg 1 not in its mempool, the server refuses leg 2 for its missing input.
            broadcaster.inner.answerSubmit(id2) { F2SubmitPath.missingInputs(it) }

            val result = F2SubmitPath.createStoreAndSubmit(broadcaster)

            assertEquals(listOf(id1, id2), broadcaster.inner.submitted(), "leg 1 is handed over once, then leg 2")
            assertTrue(
                broadcaster.rawSizes.first() < leg1.raw.byteArray.size,
                "leg 1's first submit must not carry its real bytes (sizes ${broadcaster.rawSizes})",
            )
            assertEquals(leg2.raw.byteArray.size, broadcaster.rawSizes[1], "leg 2 is submitted whole")
            assertEquals(SubmitResult.GrpcFailure(txIds = listOf(id1, id2)), result)
        }

    @Test
    fun `KEEP_LEG2_AWAITING_PLAN - leg 2 is never submitted and its id is kept`() =
        runBlocking {
            FaultSeams.arm(FaultSeam.KEEP_LEG2_AWAITING_PLAN, 1)
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))

            val result = F2SubmitPath.createStoreAndSubmit(broadcaster)

            assertEquals(listOf(id1), broadcaster.submitted())
            assertIs<SubmitResult.Partial>(result, "leg 1 landed and leg 2 was never attempted: $result")
            assertEquals(listOf(id1, id2), result.txIds)
        }

    @Test
    fun `KILL_AFTER_CREATE_BEFORE_ON_STORED - the process ends after create, before the record and any submit`() =
        runBlocking {
            FaultSeams.arm(FaultSeam.KILL_AFTER_CREATE_BEFORE_ON_STORED, 1)
            FaultSeams.killer = { throw Killed() }
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))
            var recordCalls = 0

            val outcome =
                runCatching { F2SubmitPath.createStoreAndSubmit(broadcaster, onStored = { recordCalls++ }) }

            assertIs<Killed>(outcome.exceptionOrNull(), "the kill switch was not reached: $outcome")
            assertEquals(listOf("create"), broadcaster.events.toList())
            assertEquals(0, recordCalls)
        }

    /** Control: nothing armed. */
    @Test
    fun `control - with nothing armed every step runs`() =
        runBlocking {
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))

            val result = F2SubmitPath.createStoreAndSubmit(broadcaster, onStored = { broadcaster.events += "stored" })

            assertEquals(listOf("create", "stored", "submit:$id1", "submit:$id2"), broadcaster.events.toList())
            assertEquals(SubmitResult.Success(txIds = listOf(id1, id2)), result)
        }

    /** Stands in for the halt: an Error, so no `catch (e: Exception)` on the way can swallow it. */
    private class Killed : Error("synthetic kill")

    /** Records the size of the raw bytes each submit carries, then hands the call to [inner]. */
    private class RawRecordingBroadcaster(
        val inner: RecordingBroadcaster,
    ) : Broadcaster by inner {
        val rawSizes: MutableList<Int> = Collections.synchronizedList(mutableListOf())

        override suspend fun submit(
            transaction: CreatedTransaction,
            endpoint: LightWalletEndpoint,
        ): TransactionSubmitResult {
            rawSizes += transaction.raw.byteArray.size
            return inner.submit(transaction, endpoint)
        }
    }
}
