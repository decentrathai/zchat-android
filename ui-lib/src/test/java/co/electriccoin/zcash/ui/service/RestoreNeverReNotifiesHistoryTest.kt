package co.electriccoin.zcash.ui.service

import co.electriccoin.zcash.ui.service.ReceiveNotificationPolicy.Receipt
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * R2-5 (d) — a restore, or an upgrade that rescans, must not notify the user of every old message again.
 *
 * At 69f69acde SyncForegroundService keeps the notified set in memory and seeds it from the FIRST transaction
 * list it sees. During a restore that first list is empty (nothing scanned yet), so every historical receipt the
 * catch-up scan then finds is "new" and gets a notification: one per old message, often hundreds, all at once.
 *
 * Wanted: receipts already in the chain when the wallet's catch-up started are history and are absorbed
 * silently. A message mined after that point still notifies, also while the catch-up is still running. The
 * decision is [ReceiveNotificationPolicy.receiptsToNotify]; the service marks each posted one seen, as here.
 */
class RestoreNeverReNotifiesHistoryTest {
    private companion object {
        const val TIP_AT_START = 3_000_000L
    }

    private fun receipt(
        n: Int,
        height: Long?,
    ) = Receipt(txId = "synthetic-receipt-$n", minedHeight = height, timestamp = height?.let { Instant.ofEpochSecond(it) })

    /** One transaction-list emission, as the service handles it: notify, then mark each notified receipt seen. */
    private fun emit(
        state: ReceiveNotificationPolicy.State,
        receipts: List<Receipt>,
        tip: Long?,
    ): List<String> =
        ReceiveNotificationPolicy.receiptsToNotify(state, receipts, tip).map { it.txId }.onEach { state.seen += it }

    @Test
    fun `a restore whose list starts empty and fills with old receipts notifies nothing`() {
        val state = ReceiveNotificationPolicy.State()
        val history = (1..40).map { receipt(it, 2_000_000L + it * 1_000) }

        val notified =
            emit(state, emptyList(), TIP_AT_START) +
                emit(state, history.take(10), TIP_AT_START) +
                emit(state, history.take(25), TIP_AT_START + 3) +
                emit(state, history, TIP_AT_START + 7)

        assertEquals(emptyList(), notified, "every historical message was notified again after the restore")
    }

    @Test
    fun `a restore whose chain tip is not known yet notifies nothing when it becomes known`() {
        val state = ReceiveNotificationPolicy.State()
        val history = (1..5).map { receipt(it, 2_500_000L + it) }

        val notified =
            emit(state, emptyList(), null) +
                emit(state, history.take(2), null) +
                emit(state, history, TIP_AT_START)

        assertEquals(emptyList(), notified)
    }

    @Test
    fun `a message mined after the catch-up started still notifies, also during the catch-up`() {
        val state = ReceiveNotificationPolicy.State()
        val history = (1..10).map { receipt(it, 2_000_000L + it) }
        val fresh = receipt(99, TIP_AT_START + 2)

        val notified =
            emit(state, emptyList(), TIP_AT_START) +
                emit(state, history.take(4), TIP_AT_START + 1) +
                emit(state, history.take(4) + fresh, TIP_AT_START + 2) +
                emit(state, history + fresh, TIP_AT_START + 3)

        assertEquals(listOf(fresh.txId), notified)
    }

    @Test
    fun `control - a running wallet notifies each new message exactly once`() {
        val state = ReceiveNotificationPolicy.State()
        val history = (1..3).map { receipt(it, 2_000_000L + it) }
        val first = receipt(50, TIP_AT_START + 1)
        val second = receipt(51, TIP_AT_START + 5)

        val notified =
            emit(state, history, TIP_AT_START) +
                emit(state, history + first, TIP_AT_START + 1) +
                emit(state, history + first, TIP_AT_START + 2) +
                emit(state, history + first + second, TIP_AT_START + 5)

        assertEquals(listOf(first.txId, second.txId), notified)
    }
}
