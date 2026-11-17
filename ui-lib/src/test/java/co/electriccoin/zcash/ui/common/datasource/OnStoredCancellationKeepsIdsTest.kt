package co.electriccoin.zcash.ui.common.datasource

import co.electriccoin.zcash.ui.common.fake.RecordingBroadcaster
import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.coroutines.coroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * F4 (fix-all 2.13.0, train T4; T2 review G-F2-2; SPECS §1.4 step 4): a record step that times out keeps the ids.
 *
 * createStoreAndSubmit rethrows every CancellationException out of `onStored`. A TimeoutCancellationException (a
 * `withTimeout` inside the record step) is one, although the send's own job is still active: at HEAD it escapes the
 * submit path as a cancellation, so the caller never gets the ids of the transactions the SDK already stored, and a
 * stored send is left with no record of them. Wanted: while the caller's job is still active, a cancellation out of
 * `onStored` counts as a record that failed: nothing is submitted and the send is SentUnconfirmed with every id. A real
 * cancellation of the caller is still rethrown (the control).
 */
class OnStoredCancellationKeepsIdsTest {
    private val leg1 = RecordingBroadcaster.leg(seed = 0x61, expiryHeight = 3_200_040)
    private val leg2 = RecordingBroadcaster.leg(seed = 0x62, expiryHeight = 3_200_041)

    @Test
    fun `a record step that times out submits nothing and keeps every id`() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(listOf(leg1, leg2))

            val result =
                F2SubmitPath.createStoreAndSubmit(
                    broadcaster,
                    onStored = { withTimeout(1) { delay(60_000) } },
                )

            assertEquals(emptyList(), broadcaster.submitted(), "an unrecorded send must not be submitted")
            assertIs<SubmitResult.SentUnconfirmed>(result, "a stored send must stay maybe-sent with its ids: $result")
            assertEquals(listOf(leg1.txIdString(), leg2.txIdString()), result.txIds)
        }

    @Test
    fun `control - a real cancellation of the caller is still rethrown`() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(listOf(leg1))
            val send =
                async {
                    F2SubmitPath.createStoreAndSubmit(
                        broadcaster,
                        onStored = {
                            coroutineContext.job.cancel()
                            yield()
                        },
                    )
                }
            assertFailsWith<CancellationException> { send.await() }
            assertEquals(emptyList(), broadcaster.submitted(), "a cancelled send must not be submitted")
        }
}
