package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.AttachmentIds
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.StateOrigin

/**
 * ZC-946 part 1 (PERF-SPEC revision 2 section 3): what the chat screens DRAW while a history pass runs.
 *
 * A send writes its outgoing row into `pendingMessages` before it goes out, but `_chatListState` (the last finished
 * pass, which every money, key and trust reader keeps reading) has it only once the next pass ends, seconds later. The
 * display shows such a row at once, as a copy marked [ChatMessage.optimistic] (the bubble offers no action) until a
 * finished pass has it: a row still in flight says "sending" ([MessageStatus.SENDING], pending), a row the relays took
 * says sent. A row that FAILED is never drawn early: its "not sent" and Retry come from the pass (F4) only (review
 * 19 Oct, finding 1). Only the views read the result.
 *
 * A row is overlaid only when all hold: it is outgoing and no system note; its id is a send of the user's (`pending_`,
 * `nostr-out-`, `nmsg-`, an attachment upload; never `tunnel-wait-`, `calllog-`, `ai-` or anything else); the pass
 * that produced [ChatListState.Success] neither received it ([ChatListState.Success.renderedPendingIds]) nor shows it;
 * its contact already has a chat in the pass (a first message is not overlaid); and that chat holds no row with the
 * same time (ms) and text (the Open re-key renames a row the pass already shows). With no such row the base itself is
 * returned.
 */
internal object PendingOverlay {
    private val SEND_PREFIXES = listOf("pending_", "nostr-out-", "nmsg-")

    fun isSendId(id: String): Boolean = SEND_PREFIXES.any { id.startsWith(it) } || AttachmentIds.isUpload(id)

    fun apply(
        base: ChatListState,
        pending: List<ChatMessage>,
    ): ChatListState = if (base is ChatListState.Success) apply(base, pending) else base

    fun apply(
        base: ChatListState.Success,
        pending: List<ChatMessage>,
    ): ChatListState.Success {
        if (pending.isEmpty()) return base
        // ZC-946 part 2: nothing is drawn early on a kept pass (no action is possible there anyway).
        if (base.origin == StateOrigin.CACHED) return base
        val byPeer = base.conversations.associateBy { it.peerAddress }
        val shownIds by lazy(LazyThreadSafetyMode.NONE) {
            base.conversations.flatMapTo(HashSet()) { c -> c.messages.map { it.id } }
        }
        val early = LinkedHashMap<String, MutableList<ChatMessage>>()
        for (row in pending) {
            if (!row.isOutgoing || row.isSystemNote || !isSendId(row.id)) continue
            if (row.status == MessageStatus.FAILED) continue
            if (row.id in base.renderedPendingIds) continue
            val chat = byPeer[row.peerAddress] ?: continue
            if (row.id in shownIds) continue
            val ms = row.timestamp.toEpochMilli()
            if (chat.messages.any { it.timestamp.toEpochMilli() == ms && it.text == row.text }) continue
            val inFlight = row.isPending || row.status == MessageStatus.SENDING
            early.getOrPut(row.peerAddress) { mutableListOf() } +=
                if (inFlight) {
                    row.copy(status = MessageStatus.SENDING, isPending = true, optimistic = true)
                } else {
                    row.copy(optimistic = true)
                }
        }
        if (early.isEmpty()) return base
        val conversations =
            base.conversations
                .map { chat ->
                    val rows = early[chat.peerAddress] ?: return@map chat
                    val newest = rows.maxBy { it.timestamp }
                    val last = chat.lastMessage
                    chat.copy(
                        messages = chat.messages + rows.sortedBy { it.timestamp },
                        lastMessage = if (last == null || newest.timestamp >= last.timestamp) newest else last,
                        // HONOR 20 Oct: the pass's draft is the text just sent until the next pass; never show both.
                        draft = chat.draft?.takeUnless { draft -> rows.any { it.text.trim() == draft.trim() } },
                    )
                }.sortedByDescending { it.lastMessage?.timestamp }
        return base.copy(conversations = conversations)
    }
}
