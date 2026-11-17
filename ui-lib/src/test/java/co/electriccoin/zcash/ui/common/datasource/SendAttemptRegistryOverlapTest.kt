package co.electriccoin.zcash.ui.common.datasource

import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * B5, GPT Q1 / M4-3.2 / review question 18 (CREATE-WINDOW-STALE-EXCLUSIVE): the create window must never lend one
 * send's transaction to another.
 *
 * [SendAttemptRegistry] makes a send's read-create-read its own by holding one lock. A send that cannot take the
 * lock in time still runs — without it — and is told its window is shared. The send that HOLDS the lock was told
 * "exclusive" when it went in, and nothing ever told it otherwise. So:
 *
 *  1. A holds the window and its create runs long. B gives up waiting, runs unlocked and stores T. A then fails
 *     before storing anything, sees T as new in "its" exclusive window, and is marked sent with B's transaction:
 *     a message that never went out shows as sent and is reconciled against B's payment.
 *  2. A leaves while B (unlocked) is still creating. C takes the lock and is told "exclusive" although B is still
 *     inside. B stores T; C fails and claims T the same way.
 *
 * Required: whether a window was the attempt's alone is decided when the failure is settled, and any overlap with
 * an unlocked send — one that went in during the window, or was already inside when it started — makes it shared,
 * so no id is claimed. The waits here are short ([LOCK_WAIT_MS]) and run on virtual time.
 */
class SendAttemptRegistryOverlapTest {
    @Test
    fun a_holder_whose_waiter_went_in_unlocked_does_not_claim_the_waiter_s_transaction() =
        runTest {
            val wallet = Wallet()
            val attempts = SendAttemptRegistry(lockWaitMs = LOCK_WAIT_MS, pollMs = POLL_MS)
            val aInside = CompletableDeferred<Unit>()
            val bStored = CompletableDeferred<Unit>()

            // A takes the window; its create outlasts B's wait and fails after B stored its transaction.
            val a =
                async {
                    settleSubmitFlow(ownSpends = { wallet.ownSpendIds() }, attempts = attempts) {
                        aInside.complete(Unit)
                        bStored.await()
                        throw IllegalStateException(A_CREATE_FAILURE)
                    }
                }
            aInside.await()

            // B cannot get the window in time, goes in without it, and stores ITS transaction.
            val b =
                async {
                    settleSubmitFlow(ownSpends = { wallet.ownSpendIds() }, attempts = attempts) {
                        wallet.store(TX_B)
                        bStored.complete(Unit)
                        throw IllegalStateException(B_PLAN_FAILURE)
                    }
                }

            val resultA = a.await()
            b.await()

            assertEquals(
                listOf(TX_B),
                wallet.ownSpendIds() - OLDER_SPENDS.toSet(),
                "fixture: the only new transaction in the wallet is B's",
            )
            assertEquals(
                SubmitResult.SentUnconfirmed(txIds = emptyList(), description = A_CREATE_FAILURE),
                resultA,
                "A held the lock, but B ran inside A's window without it: A must claim no id rather than B's",
            )
        }

    @Test
    fun a_window_that_starts_while_an_unlocked_send_is_still_inside_is_shared() =
        runTest {
            val wallet = Wallet()
            val attempts = SendAttemptRegistry(lockWaitMs = LOCK_WAIT_MS, pollMs = POLL_MS)
            val aInside = CompletableDeferred<Unit>()
            val aMayLeave = CompletableDeferred<Unit>()
            val bInside = CompletableDeferred<Unit>()
            val bMayStore = CompletableDeferred<Unit>()
            val bStored = CompletableDeferred<Unit>()

            val a =
                async {
                    runCatching {
                        settleSubmitFlow(ownSpends = { wallet.ownSpendIds() }, attempts = attempts) {
                            aInside.complete(Unit)
                            aMayLeave.await()
                            throw IllegalStateException(A_CREATE_FAILURE)
                        }
                    }
                }
            aInside.await()

            // B times out waiting for A and goes in unlocked; it is still creating when A leaves.
            val b =
                async {
                    runCatching {
                        settleSubmitFlow(ownSpends = { wallet.ownSpendIds() }, attempts = attempts) {
                            bInside.complete(Unit)
                            bMayStore.await()
                            wallet.store(TX_B)
                            bStored.complete(Unit)
                            throw IllegalStateException(B_PLAN_FAILURE)
                        }
                    }
                }
            bInside.await()
            aMayLeave.complete(Unit)
            assertFailsWith<NothingBroadcastException>("fixture: A stored nothing") { a.await().getOrThrow() }

            // C gets the free lock while B is still inside, and fails after B stored its transaction.
            val resultC =
                settleSubmitFlow(ownSpends = { wallet.ownSpendIds() }, attempts = attempts) {
                    bMayStore.complete(Unit)
                    bStored.await()
                    throw IllegalStateException(C_CREATE_FAILURE)
                }
            b.await()

            assertEquals(
                SubmitResult.SentUnconfirmed(txIds = emptyList(), description = C_CREATE_FAILURE),
                resultC,
                "C took the lock while B was still creating without it: C must claim no id rather than B's",
            )
        }

    // Pin: holds before and after the fix — once no unlocked send is inside any more, the window is exclusive
    // again and a send that stored its own transaction is still tracked by its id.
    @Test
    fun control_after_the_unlocked_send_has_left_the_next_window_is_exclusive_again() =
        runTest {
            val wallet = Wallet()
            val attempts = SendAttemptRegistry(lockWaitMs = LOCK_WAIT_MS, pollMs = POLL_MS)
            val aInside = CompletableDeferred<Unit>()
            val aMayLeave = CompletableDeferred<Unit>()

            val a =
                async {
                    runCatching {
                        settleSubmitFlow(ownSpends = { wallet.ownSpendIds() }, attempts = attempts) {
                            aInside.complete(Unit)
                            aMayLeave.await()
                            throw IllegalStateException(A_CREATE_FAILURE)
                        }
                    }
                }
            aInside.await()
            // B times out, goes in unlocked, stores nothing and leaves; then A leaves.
            assertFailsWith<NothingBroadcastException> {
                settleSubmitFlow(ownSpends = { wallet.ownSpendIds() }, attempts = attempts) {
                    throw IllegalStateException(B_PLAN_FAILURE)
                }
            }
            aMayLeave.complete(Unit)
            a.await()

            val resultD =
                settleSubmitFlow(ownSpends = { wallet.ownSpendIds() }, attempts = attempts) {
                    wallet.store(TX_D)
                    throw IllegalStateException(D_PLAN_FAILURE)
                }

            assertEquals(
                SubmitResult.SentUnconfirmed(txIds = listOf(TX_D), description = D_PLAN_FAILURE),
                resultD,
                "nobody else was inside D's window: its own transaction must still be tracked by its id",
            )
        }

    // B5, at the registry itself: the holder asks its question when it settles, and hears "shared".
    @Test
    fun the_holder_asked_at_settle_time_hears_that_a_waiter_went_in_unlocked() =
        runTest {
            val attempts = SendAttemptRegistry(lockWaitMs = LOCK_WAIT_MS, pollMs = POLL_MS)
            val aInside = CompletableDeferred<Unit>()
            val bInside = CompletableDeferred<Unit>()

            val a =
                async {
                    attempts.inCreateWindow { exclusive ->
                        val atStart = exclusive()
                        aInside.complete(Unit)
                        bInside.await()
                        atStart to exclusive()
                    }
                }
            aInside.await()
            val b =
                async {
                    attempts.inCreateWindow { exclusive ->
                        bInside.complete(Unit)
                        exclusive()
                    }
                }

            val (aAtStart, aAtSettle) = a.await()
            assertEquals(true, aAtStart, "fixture: A took the lock with nobody else inside")
            assertEquals(false, aAtSettle, "B went into A's window without the lock: A must be told its window is shared")
            assertEquals(false, b.await(), "B went in without the lock")
        }

    @Test
    fun a_lock_taken_while_an_unlocked_send_is_inside_is_shared_from_its_start() =
        runTest {
            val attempts = SendAttemptRegistry(lockWaitMs = LOCK_WAIT_MS, pollMs = POLL_MS)
            val aInside = CompletableDeferred<Unit>()
            val aMayLeave = CompletableDeferred<Unit>()
            val bInside = CompletableDeferred<Unit>()
            val bMayLeave = CompletableDeferred<Unit>()

            val a = async { attempts.inCreateWindow { aInside.complete(Unit).also { aMayLeave.await() } } }
            aInside.await()
            val b = async { attempts.inCreateWindow { bInside.complete(Unit).also { bMayLeave.await() } } }
            bInside.await()
            aMayLeave.complete(Unit)
            a.await()

            val cExclusive = attempts.inCreateWindow { exclusive -> exclusive() }
            bMayLeave.complete(Unit)
            b.await()
            val dExclusive = attempts.inCreateWindow { exclusive -> exclusive() }

            assertEquals(false, cExclusive, "C took the free lock while B was still creating without it")
            assertEquals(true, dExclusive, "nobody else is inside any more: D's window is its own")
        }

    /** The wallet's own local transaction list, shared by the attempts. */
    private class Wallet {
        private val spends = Collections.synchronizedList(mutableListOf<String>().also { it += OLDER_SPENDS })

        fun store(id: String) {
            spends += id
        }

        fun ownSpendIds(): List<String> = spends.toList()
    }

    private companion object {
        /** Much shorter than the 60 s of the app, so the time-out is reached on virtual time. */
        const val LOCK_WAIT_MS = 50L
        const val POLL_MS = 10L

        const val A_CREATE_FAILURE = "synthetic proving failure of A before anything was stored"
        const val B_PLAN_FAILURE = "synthetic submit-plan write failure of B"
        const val C_CREATE_FAILURE = "synthetic proving failure of C before anything was stored"
        const val D_PLAN_FAILURE = "synthetic submit-plan write failure of D after storage"

        val OLDER_SPENDS = listOf("f".repeat(64), "e".repeat(64))

        /** The transaction the unlocked send B stores. */
        val TX_B = "0b".repeat(32)

        /** D's own stored transaction. */
        val TX_D = "0d".repeat(32)
    }
}
