package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Where "provably nothing was sent" ends inside the settlement call (audit fable-20260915, A.3 + A.5).
 *
 * SDK 3.0.0 splits `createProposedTransactions` / `createTransactionFromPczt` in two:
 *
 *  1. an EAGER step (`SdkBroadcaster.createAndSubmitProposedTransactions` ->
 *     `PendingSubmitPlanStore.createAndMarkAwaitingSubmitPlan`) that creates, proves and stores every
 *     transaction and touches nothing on the network, then returns
 *  2. a LAZY flow (`createSubmitResultFlow`) whose map body hands each transaction to lightwalletd and
 *     emits its result only after `try { submit } finally { addSubmitEndpoint }` (an encrypted-prefs
 *     write) and `try { submitTransaction } finally { client.dispose() }` have both run.
 *
 * So the network send happens while ZChat COLLECTS the flow, and a throw from either `finally` arrives
 * after lightwalletd accepted the transaction but before its result was emitted. Two defects follow:
 *
 *  * A.3: a throw during collect with nothing emitted was raised as [NothingBroadcastException], which
 *    the repositories turn into "Send Failed / Try it again" and the chat senders into a re-armed,
 *    re-paid handshake — over money already on the wire. Any throw during collect is ambiguous.
 *  * P2a: the eager step can store transactions BEFORE submit-plan bookkeeping throws. No result ids
 *    and no immediate network send do not prove that a later SDK resubmission cannot send those rows.
 *    Once SDK creation starts, an unclassified failure must not authorize a replacement payment.
 *  * Check, don't guess (replaces P2a's blanket create-step rule): a shortfall is found while the payment
 *    is built, before anything is stored, so it is "not sent". Any other create-step failure is settled
 *    by reading the wallet's own local transaction list at once: a new own spend means "sent", tracked
 *    by its id; none means "not sent". Only when that list cannot be read does "maybe sent" remain.
 *  * A.5: a throw during collect after an earlier transaction had emitted was rethrown bare, so the ids
 *    already collected were dropped and "Sent, not confirmed" could never offer "View transaction" or
 *    report a swap deposit hash. The ambiguity is RETURNED as [SubmitResult.SentUnconfirmed] carrying
 *    the collected ids — not carried on a custom exception, which coroutine stack-trace recovery copies
 *    and would strip of the field.
 *
 * [FakeBroadcaster] reproduces that eager/lazy shape. Transaction ids are synthetic bytes.
 */
class SubmitFlowBroadcastBoundaryTest {
    @Test
    fun a_throw_after_the_only_transaction_was_sent_is_not_reported_as_nothing_sent() =
        runBlocking {
            val sdk = FakeBroadcaster(listOf(TX_A), failAfterSending = 0)

            val result =
                try {
                    settleSubmitFlow { sdk.createAndSubmit() }
                } catch (e: NothingBroadcastException) {
                    fail("lightwalletd already had ${sdk.sentToNetwork}, yet this was raised as nothing sent: $e")
                }

            assertEquals(listOf(TX_A.id()), sdk.sentToNetwork, "the transaction reached the network first")
            assertEquals(
                SubmitResult.SentUnconfirmed(txIds = emptyList(), description = POST_SEND_FAILURE),
                result,
                "a throw during collect is ambiguous: money may have moved"
            )
        }

    @Test
    fun a_throw_on_the_second_transaction_keeps_the_id_of_the_first() =
        runBlocking {
            val sdk = FakeBroadcaster(listOf(TX_A, TX_B), failAfterSending = 1)

            val result =
                try {
                    settleSubmitFlow { sdk.createAndSubmit() }
                } catch (e: Exception) {
                    fail("the ambiguity must be returned with the ids already collected, not thrown: $e")
                }

            assertEquals(listOf(TX_A.id(), TX_B.id()), sdk.sentToNetwork)
            assertEquals(
                SubmitResult.SentUnconfirmed(txIds = listOf(TX_A.id()), description = POST_SEND_FAILURE),
                result,
                "the first transaction's id was already in hand; dropping it hides View transaction"
            )
        }

    @Test
    fun an_unclassified_throw_out_of_sdk_creation_is_conservatively_unconfirmed() =
        runBlocking {
            val sdk = FakeBroadcaster(listOf(TX_A), createFailure = IllegalStateException(CREATE_FAILURE))

            // This fake fails before storage, but the settlement boundary cannot infer that stage from
            // the exception. Truly pre-SDK failures (key/synchronizer acquisition) belong outside it.
            val result = settleSubmitFlow { sdk.createAndSubmit() }

            assertEquals(SubmitResult.SentUnconfirmed(txIds = emptyList(), description = CREATE_FAILURE), result)
            assertTrue(sdk.sentToNetwork.isEmpty(), "the create step never touches the network")
            assertTrue(sdk.storedTransactions.isEmpty(), "fixture sanity: this failure precedes storage")
            assertEquals(1, sdk.createCalls, "an ambiguous attempt must not be recreated automatically")
        }

    @Test
    fun a_create_failure_after_storage_does_not_authorize_a_replacement_payment() =
        runBlocking {
            val sdk =
                FakeBroadcaster(
                    listOf(TX_A, TX_B),
                    createFailure = IllegalStateException(STORED_PLAN_FAILURE),
                    storeBeforeCreateFailure = true,
                )

            val result =
                try {
                    settleSubmitFlow { sdk.createAndSubmit() }
                } catch (e: NothingBroadcastException) {
                    fail("stored transactions can be resubmitted later, yet the caller was told nothing sent: $e")
                }

            assertEquals(listOf(TX_A.id(), TX_B.id()), sdk.storedTransactions, "storage happened before plan write failed")
            assertTrue(sdk.sentToNetwork.isEmpty(), "no immediate broadcast does not rule out SDK resubmission")
            assertEquals(
                SubmitResult.SentUnconfirmed(txIds = emptyList(), description = STORED_PLAN_FAILURE),
                result,
                "return ambiguity, not the Failure/NothingBroadcast signal that re-arms paid callers",
            )
            assertEquals(1, sdk.createCalls, "settlement must not rebuild and repay an ambiguous stored attempt")
        }

    // ── Check, don't guess (owner-approved 22 Sep): a create-step failure is classified from facts ─────

    @Test
    fun a_shortfall_out_of_sdk_creation_is_not_sent_even_when_the_check_cannot_run() =
        runBlocking {
            // The SDK wraps the backend's refusal; the shortfall text is on the cause, as on a device.
            val refusal =
                IllegalStateException(
                    "Failed to create transaction",
                    RuntimeException("Error while creating transactions: $SHORTFALL"),
                )
            val sdk = FakeBroadcaster(listOf(TX_A), createFailure = refusal)

            val e =
                assertFailsWith<NothingBroadcastException>(
                    "a shortfall is found while the transaction is being built, before anything is stored",
                ) {
                    settleSubmitFlow(ownSpends = { throw IllegalStateException("synthetic wallet db locked") }) {
                        sdk.createAndSubmit()
                    }
                }

            assertTrue(sdk.storedTransactions.isEmpty(), "fixture sanity: nothing was stored")
            assertTrue(e.message.orEmpty().contains("Failed to create transaction"), "the reason is kept: ${e.message}")
        }

    @Test
    fun a_build_failure_that_left_no_new_own_transaction_is_not_sent() =
        runBlocking {
            val sdk = FakeBroadcaster(listOf(TX_A), createFailure = IllegalStateException(CREATE_FAILURE))
            var reads = 0

            val e =
                assertFailsWith<NothingBroadcastException>("the wallet's own list proves nothing was stored") {
                    settleSubmitFlow(
                        ownSpends = {
                            reads++
                            OLDER_SPENDS + sdk.storedTransactions
                        },
                    ) { sdk.createAndSubmit() }
                }

            assertEquals(CREATE_FAILURE, e.message)
            assertEquals(2, reads, "the list is read before the attempt and again at once after the failure")
            assertEquals(1, sdk.createCalls, "settlement never rebuilds the payment itself")
        }

    @Test
    fun a_failure_after_storage_is_sent_and_tracked_by_the_new_transaction_ids() =
        runBlocking {
            val sdk =
                FakeBroadcaster(
                    listOf(TX_A, TX_B),
                    createFailure = IllegalStateException(STORED_PLAN_FAILURE),
                    storeBeforeCreateFailure = true,
                )

            val result =
                try {
                    settleSubmitFlow(ownSpends = { OLDER_SPENDS + sdk.storedTransactions }) { sdk.createAndSubmit() }
                } catch (e: NothingBroadcastException) {
                    fail("the wallet holds this attempt's transactions, yet it was reported as not sent: $e")
                }

            assertEquals(
                SubmitResult.SentUnconfirmed(txIds = listOf(TX_A.id(), TX_B.id()), description = STORED_PLAN_FAILURE),
                result,
                "found in the wallet's own list: sent, and tracked by exactly this attempt's new ids",
            )
        }

    @Test
    fun when_the_list_cannot_be_read_after_a_create_failure_the_attempt_stays_maybe_sent() =
        runBlocking {
            val sdk = FakeBroadcaster(listOf(TX_A), createFailure = IllegalStateException(CREATE_FAILURE))
            var reads = 0

            val result =
                settleSubmitFlow(
                    ownSpends = {
                        reads++
                        if (reads > 1) throw IllegalStateException("synthetic wallet db locked")
                        OLDER_SPENDS
                    },
                ) { sdk.createAndSubmit() }

            assertEquals(SubmitResult.SentUnconfirmed(txIds = emptyList(), description = CREATE_FAILURE), result)
        }

    @Test
    fun when_the_list_cannot_be_read_before_the_attempt_a_create_failure_stays_maybe_sent() =
        runBlocking {
            val sdk = FakeBroadcaster(listOf(TX_A), createFailure = IllegalStateException(CREATE_FAILURE))

            val result =
                settleSubmitFlow(ownSpends = { throw IllegalStateException("synthetic wallet db locked") }) {
                    sdk.createAndSubmit()
                }

            assertEquals(SubmitResult.SentUnconfirmed(txIds = emptyList(), description = CREATE_FAILURE), result)
            assertEquals(1, sdk.createCalls, "a failed read never stops or repeats the user's send")
        }

    @Test
    fun control_a_clean_batch_is_still_the_sdk_verdict() =
        runBlocking {
            val sdk = FakeBroadcaster(listOf(TX_A, TX_B))

            assertEquals(
                SubmitResult.Success(txIds = listOf(TX_A.id(), TX_B.id())),
                settleSubmitFlow { sdk.createAndSubmit() }
            )
        }

    @Test
    fun control_a_cancellation_during_collect_still_propagates() =
        runBlocking {
            val sdk =
                FakeBroadcaster(listOf(TX_A), failAfterSending = 0, failure = CancellationException("synthetic cancel"))

            assertFailsWith<CancellationException> { settleSubmitFlow { sdk.createAndSubmit() } }
            Unit
        }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    /**
     * SDK 3.0.0's broadcaster shape: [createAndSubmit] is the eager create/prove/store step (it throws
     * [createFailure] before anything is sent, optionally AFTER storage) and returns a lazy flow that "sends" each transaction and
     * then, for the transaction at [failAfterSending], throws [failure] from the post-send bookkeeping
     * before that transaction's result is emitted.
     */
    private class FakeBroadcaster(
        private val transactions: List<FirstClassByteArray>,
        private val failAfterSending: Int? = null,
        private val failure: Exception = IllegalStateException(POST_SEND_FAILURE),
        private val createFailure: Exception? = null,
        private val storeBeforeCreateFailure: Boolean = false,
    ) {
        val sentToNetwork = mutableListOf<String>()
        val storedTransactions = mutableListOf<String>()
        var createCalls = 0
            private set

        suspend fun createAndSubmit(): Flow<TransactionSubmitResult> {
            createCalls++
            if (!storeBeforeCreateFailure) createFailure?.let { throw it }
            storedTransactions += transactions.map { it.id() }
            createFailure?.let { throw it }
            return transactions.withIndex().asFlow().map { (index, txId) ->
                val result = TransactionSubmitResult.Success(txId)
                sentToNetwork += result.txIdString()
                if (index == failAfterSending) throw failure
                result
            }
        }
    }

    private companion object {
        const val POST_SEND_FAILURE = "synthetic client dispose failure after the send"

        const val CREATE_FAILURE = "synthetic proving failure before the send"

        const val STORED_PLAN_FAILURE = "synthetic submit-plan write failure after storage"

        // The backend's own wording (rust `create_proposed_transactions` via the JNI "Error while creating
        // transactions: {e}" wrapper); every such error is raised before `store_transactions_to_be_sent`.
        const val SHORTFALL = "Insufficient balance (have 1000, need 12000 including fee)"

        // Spends the wallet already held before the attempt; they must never be taken for this attempt's.
        val OLDER_SPENDS = listOf("f".repeat(64), "e".repeat(64))

        val TX_A = FirstClassByteArray(ByteArray(32) { 0x0A })

        val TX_B = FirstClassByteArray(ByteArray(32) { 0x0B })

        fun FirstClassByteArray.id(): String = TransactionSubmitResult.Success(this).txIdString()
    }
}
