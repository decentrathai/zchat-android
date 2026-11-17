package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus

/**
 * ZC-946 part 4 (PERF-SPEC revision 2 section 4): which stored rows a view model takes from another screen, and how
 * they merge into its pending rows (ChatViewModel.followStoredSends). Pure.
 */
internal object StoredRelaySends {
    private const val RELAY_ROW_PREFIX = "nmsg-"

    /**
     * The user's own relay send whose send has ended: outgoing, an `nmsg-` row no longer pending, SENT or FAILED, no
     * payment request. A row still in flight (`nostr-out-`, `pending_`, any pending row, a legacy row with no status) is
     * never taken: its sender is the view model that wrote it.
     */
    fun isSettledOwnRelaySend(row: ZchatPreferences.PendingMessageData): Boolean =
        row.isOutgoing &&
            row.id.startsWith(RELAY_ROW_PREFIX) &&
            !row.isPending &&
            (row.status == MessageStatus.SENT.name || row.status == MessageStatus.FAILED.name) &&
            row.paymentRequestAmountZatoshi == null

    class Merge(
        val rows: List<ChatMessage>,
        val taken: Set<String>,
    )

    /**
     * [current] with the stored rows [stored] (by id, already built): a stored id not held is added (and taken); a held
     * row this view model took ([taken]) is replaced by the store's version when it differs, and removed when the store
     * dropped it (or it was hidden); every other held row is kept as it is.
     */
    fun merge(
        current: List<ChatMessage>,
        taken: Set<String>,
        stored: Map<String, ChatMessage>,
    ): Merge {
        val held = current.mapTo(HashSet()) { it.id }
        val nowTaken = HashSet<String>()
        val rows =
            current.mapNotNull { row ->
                if (row.id !in taken) return@mapNotNull row
                val fresh = stored[row.id] ?: return@mapNotNull null
                nowTaken += row.id
                if (fresh == row) row else fresh
            }
        val added =
            stored.values
                .filter { it.id !in held }
                .sortedBy { it.timestamp }
        added.forEach { nowTaken += it.id }
        return if (added.isEmpty() && nowTaken == taken && rows.size == current.size && rows.indices.all { rows[it] === current[it] }) {
            Merge(current, taken)
        } else {
            Merge(rows + added, nowTaken)
        }
    }
}
