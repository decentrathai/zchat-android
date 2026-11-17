package co.electriccoin.zcash.ui.common.datasource

import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * R4-3 (1), round 3 report 4 item 4: TWO SENDS AT THE SAME MOMENT.
 *
 * A create-step failure is settled by comparing the wallet's own transaction list with a read taken right
 * before the attempt (`settleCreateFailure`, "check, don't guess"). That comparison is over a TIME window,
 * so a transaction another send stores inside it is indistinguishable from this attempt's own. Crediting
 * this attempt with it reports a message that never went out as sent, hands the chat a transaction id that
 * belongs to a different message, and leaves two rows reconciling against one transaction.
 *
 * The rule pinned here: an id is claimed only when this attempt provably owns the window it appeared in
 * (match by attempt, not by time). When the window had to be shared, the attempt claims NO id and stays
 * the honest "maybe sent" — never another send's transaction.
 */
class SimultaneousSendAttributionTest {
    @Test
    fun a_failed_send_is_not_credited_with_a_simultaneous_send_s_transaction() =
        runBlocking {
            val wallet = Wallet()
            // The other send holds the create window; this attempt cannot wait for it and must share.
            val attempts = SendAttemptRegistry(lockWaitMs = 0L)
            val otherHasWindow = CompletableDeferred<Unit>()
            val otherMayStore = CompletableDeferred<Unit>()
            val otherStored = CompletableDeferred<Unit>()
            val otherMayLeave = CompletableDeferred<Unit>()

            val other =
                launch(Dispatchers.Default) {
                    attempts.inCreateWindow {
                        otherHasWindow.complete(Unit)
                        otherMayStore.await()
                        wallet.store(TX_OTHER)
                        otherStored.complete(Unit)
                        otherMayLeave.await()
                    }
                }
            otherHasWindow.await()

            val result =
                settleSubmitFlow(ownSpends = { wallet.ownSpendIds() }, attempts = attempts) {
                    // The other send stores ITS transaction while this one is being created.
                    otherMayStore.complete(Unit)
                    otherStored.await()
                    throw IllegalStateException(CREATE_FAILURE)
                }
            otherMayLeave.complete(Unit)
            other.join()

            assertEquals(
                listOf(TX_OTHER),
                wallet.ownSpendIds() - OLDER_SPENDS.toSet(),
                "fixture: the only new transaction in the wallet is the OTHER send's",
            )
            assertEquals(
                SubmitResult.SentUnconfirmed(txIds = emptyList(), description = CREATE_FAILURE),
                result,
                "a shared window cannot say whose transaction that is: claim no id rather than another send's",
            )
        }

    @Test
    fun control_a_send_that_had_the_window_to_itself_still_tracks_its_own_transaction() =
        runBlocking {
            val wallet = Wallet()
            val attempts = SendAttemptRegistry()

            val result =
                settleSubmitFlow(ownSpends = { wallet.ownSpendIds() }, attempts = attempts) {
                    wallet.store(TX_MINE)
                    throw IllegalStateException(STORED_PLAN_FAILURE)
                }

            assertEquals(
                SubmitResult.SentUnconfirmed(txIds = listOf(TX_MINE), description = STORED_PLAN_FAILURE),
                result,
                "nobody else was creating: the new transaction is this attempt's and it is tracked by its id",
            )
        }

    @Test
    fun control_a_shared_window_that_stored_nothing_at_all_is_still_retryable() =
        runBlocking {
            val wallet = Wallet()
            val attempts = SendAttemptRegistry(lockWaitMs = 0L)
            val otherHasWindow = CompletableDeferred<Unit>()
            val otherMayLeave = CompletableDeferred<Unit>()

            val other =
                launch(Dispatchers.Default) {
                    attempts.inCreateWindow {
                        otherHasWindow.complete(Unit)
                        otherMayLeave.await()
                    }
                }
            otherHasWindow.await()

            val e =
                assertFailsWith<NothingBroadcastException>(
                    "no new transaction at all proves this attempt stored nothing, shared window or not",
                ) {
                    settleSubmitFlow(ownSpends = { wallet.ownSpendIds() }, attempts = attempts) {
                        throw IllegalStateException(CREATE_FAILURE)
                    }
                }
            otherMayLeave.complete(Unit)
            other.join()

            assertEquals(CREATE_FAILURE, e.message)
        }

    @Test
    fun control_the_create_window_is_never_shared_while_a_send_can_wait_for_it() =
        runBlocking {
            val attempts = SendAttemptRegistry()
            val order = Collections.synchronizedList(mutableListOf<String>())
            val firstInside = CompletableDeferred<Unit>()
            val firstMayLeave = CompletableDeferred<Unit>()

            val first =
                launch(Dispatchers.Default) {
                    attempts.inCreateWindow { exclusive ->
                        order += "first in (exclusive=${exclusive()})"
                        firstInside.complete(Unit)
                        firstMayLeave.await()
                        order += "first out"
                    }
                }
            firstInside.await()

            val second =
                async(Dispatchers.Default) {
                    attempts.inCreateWindow { exclusive ->
                        order += "second in (exclusive=${exclusive()})"
                        exclusive()
                    }
                }
            delay(WAIT_PROOF_MS)
            assertEquals(
                listOf("first in (exclusive=true)"),
                order.toList(),
                "the second attempt waits OUTSIDE the first attempt's window",
            )

            firstMayLeave.complete(Unit)
            assertTrue(second.await(), "once the first left, the second has the window to itself")
            first.join()
            assertEquals(
                listOf("first in (exclusive=true)", "first out", "second in (exclusive=true)"),
                order.toList(),
            )
        }

    /** The wallet's own local transaction list, shared by the two attempts. */
    private class Wallet {
        private val spends = Collections.synchronizedList(mutableListOf<String>().also { it += OLDER_SPENDS })

        fun store(id: String) {
            spends += id
        }

        fun ownSpendIds(): List<String> = spends.toList()
    }

    private companion object {
        const val CREATE_FAILURE = "synthetic proving failure before the send"

        const val STORED_PLAN_FAILURE = "synthetic submit-plan write failure after storage"

        /** Long enough to show the second attempt is held outside, short enough not to slow the suite. */
        const val WAIT_PROOF_MS = 150L

        val OLDER_SPENDS = listOf("f".repeat(64), "e".repeat(64))

        /** The transaction the OTHER send stores while this one is failing. */
        val TX_OTHER = "0a".repeat(32)

        /** This attempt's own stored transaction. */
        val TX_MINE = "0b".repeat(32)
    }
}
