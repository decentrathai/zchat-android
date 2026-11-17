package co.electriccoin.zcash.ui.common.datasource

import co.electriccoin.zcash.ui.common.fake.RecordingBroadcaster
import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ZC-149 (T7B-HANDSHAKE-SPEC rev 2, section 2.17) — `createStoreAndSubmit` whose create step returns no transaction at
 * all ended as `GrpcFailure(txIds = [])` ("not delivered yet, may be rebroadcast"), because the classifier reads an
 * empty list of legs as all resubmittable, so a handshake marker stayed as if something was on its way.
 *
 * ZC-149-R1 (review of T7bH round 2, 20 Oct 2026, finding 3): the first fix answered `Failure(txIds = [])` (nothing
 * stored) without looking at the wallet. An empty answer from the SDK is not proof that nothing was stored, so it is
 * settled exactly as a create that throws: the wallet's own spends are read before and after; no new spend proves
 * nothing was stored (NothingBroadcastException, retryable); a new spend is this attempt's stored payment (maybe sent,
 * tracked by its id, never paid again on Retry); a list that cannot be read stays "maybe sent".
 *
 * Pin: a create that returns a transaction still submits it and answers as before. Every id is synthetic.
 */
class AnEmptyCreateResultIsNothingSentTest {
    @Test
    fun `an empty create with no new spend in the wallet proves nothing was stored and submits nothing`() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(emptyList())

            assertFailsWith<NothingBroadcastException>("an empty create with no new spend must read as nothing stored") {
                F2SubmitPath.createStoreAndSubmit(broadcaster, ownSpends = { listOf(EARLIER) }, create = { emptyList() })
            }
            assertEquals(emptyList(), broadcaster.submitted(), "a create with no transaction submitted something")
        }

    @Test
    fun `an empty create while a new spend appeared in the wallet is maybe sent with that id, never unsent`() =
        runBlocking<Unit> {
            val broadcaster = RecordingBroadcaster(emptyList())
            val reads = AtomicInteger(0)

            val result =
                F2SubmitPath.createStoreAndSubmit(
                    broadcaster,
                    ownSpends = { if (reads.getAndIncrement() == 0) listOf(EARLIER) else listOf(EARLIER, STORED) },
                    create = { emptyList() },
                )

            assertIs<SubmitResult.SentUnconfirmed>(result, "a stored transaction looked unsent after an empty create: $result")
            assertEquals(listOf(STORED), result.txIds, "the new spend is this attempt's stored payment")
        }

    @Test
    fun `an empty create with no wallet reader stays maybe sent with no ids`() =
        runBlocking<Unit> {
            val result = F2SubmitPath.createStoreAndSubmit(RecordingBroadcaster(emptyList()), create = { emptyList() })

            assertIs<SubmitResult.SentUnconfirmed>(result, "without a reader nothing proves the empty create stored nothing: $result")
            assertTrue(result.txIds.isEmpty(), "no id can be claimed without a reader")
        }

    @Test
    fun `pin - a create that returns a transaction submits it as before`() =
        runBlocking<Unit> {
            val leg = RecordingBroadcaster.leg(seed = 0x49, expiryHeight = 3_200_149)
            val broadcaster = RecordingBroadcaster(listOf(leg))

            val result = F2SubmitPath.createStoreAndSubmit(broadcaster)

            assertEquals(1, broadcaster.submitted().size, "the one created transaction was not submitted")
            assertIs<SubmitResult.Success>(result, "a submitted transaction did not answer as before: $result")
        }

    private companion object {
        const val EARLIER = "synthetic-zc149-earlier-spend"
        const val STORED = "synthetic-zc149-stored-spend"
    }
}
