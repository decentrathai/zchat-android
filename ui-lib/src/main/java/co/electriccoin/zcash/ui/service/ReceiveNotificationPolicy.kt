package co.electriccoin.zcash.ui.service

import java.time.Instant
import java.util.Collections

/**
 * Which received transactions get a chat notification. Pure (no Android, no SDK), so the decision can be tested
 * on the JVM; [SyncForegroundService] feeds it every wallet transaction list it observes.
 */
internal object ReceiveNotificationPolicy {
    /** One received transaction as the policy sees it. */
    data class Receipt(
        val txId: String,
        val minedHeight: Long?,
        val timestamp: Instant?,
    )

    /** What the service remembers between transaction lists (in memory, one per service instance). */
    class State {
        val seen: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        /**
         * The chain tip when this wallet's monitoring started, or null until it is known. Every receipt mined at
         * or below it was already in the chain when the wallet (re)started scanning: history, never notified.
         */
        @Volatile var historyTip: Long? = null

        /** A different wallet (a restore, a wallet switch) starts its own catch-up: anchor it again. */
        fun resetAnchor() {
            historyTip = null
        }
    }

    /**
     * The receipts in [receipts] that should be notified now, oldest first. The caller marks each one seen
     * ([State.seen]) once its notification is posted. [chainTip] is the wallet's current network height, or null
     * while it is unknown.
     *
     * R2-5 (d): the first tip seen anchors the history. A receipt mined at or below the anchor is absorbed into
     * [State.seen] silently, so a restore (whose transaction list starts empty and fills during catch-up) or a
     * rescan never notifies old messages again. A receipt mined above it, or not mined yet, is new and notifies,
     * also while the catch-up is still running. Until a tip is known nothing is notified or absorbed.
     */
    fun receiptsToNotify(
        state: State,
        receipts: List<Receipt>,
        chainTip: Long?,
    ): List<Receipt> {
        val anchor = state.historyTip ?: chainTip?.also { state.historyTip = it } ?: return emptyList()
        val (history, fresh) =
            receipts
                .filter { it.txId !in state.seen }
                .partition { it.minedHeight != null && it.minedHeight <= anchor }
        state.seen.addAll(history.map { it.txId })
        return fresh.sortedBy { it.timestamp ?: Instant.EPOCH }
    }
}
