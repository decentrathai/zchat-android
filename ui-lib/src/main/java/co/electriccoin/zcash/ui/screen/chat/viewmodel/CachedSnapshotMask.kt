package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.DisappearPolicy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.StateOrigin

/**
 * ZC-946 part 2 (PERF-SPEC revision 2 section 5): the last finished pass as a new screen may show it before its own pass
 * ends. An ALLOWLIST: only what is listed here is copied, every other field keeps its default.
 *
 * - Message: id, contact, direction, time and the text of a plain row; the tick of a mined row (minedHeight with
 *   CONFIRMED or READ). A row not mined says "Checking" ([ChatMessage.statusChecking]); a lock, payment request, file,
 *   call, AI row or a send with an amount shows a "Checking" body ([ChatMessage.bodyChecking]) and no text. Every row
 *   is [ChatMessage.optimistic]: no menu, no Retry, no Pay, no download.
 * - Dropped: system notes, rows hidden or tombstoned since, rows past their disappearing time, any row whose expiry
 *   would need the stored unlock anchor (a time-locked row in a chat with a timer), then chats left empty.
 * - Chat: the contact; name and draft read live; messages and the last message from the masked rows; no unread count,
 *   status, mute, call channel or E2E marks.
 * - List: no groups, sync, price or privacy figures; our address from the tag; the balance from the live account.
 * Pure: reads nothing but its arguments, writes nothing.
 */
internal object CachedSnapshotMask {
    class Live(
        val hiddenIds: Set<String>,
        val expiredIds: Set<String>,
        val nowMillis: Long,
        val ttlOf: (String) -> ZchatPreferences.DisappearingTtl?,
        val contactNameOf: (String) -> String?,
        val drafts: Map<String, String>,
        val balance: Zatoshi,
        val peerSendableBalance: Zatoshi,
    )

    fun mask(
        snapshot: ChatListState.Success,
        userAddress: String,
        live: Live,
    ): ChatListState.Success {
        val conversations =
            snapshot.conversations
                .mapNotNull { chat -> conversation(chat, live) }
                .sortedByDescending { it.lastMessage?.timestamp }
        return ChatListState.Success(
            conversations = conversations,
            currentUserAddress = userAddress,
            balance = live.balance,
            peerSendableBalance = live.peerSendableBalance,
            origin = StateOrigin.CACHED,
        )
    }

    private fun conversation(
        chat: Conversation,
        live: Live,
    ): Conversation? {
        val ttl = live.ttlOf(chat.peerAddress)
        val rows =
            chat.messages
                .filterNot { it.isSystemNote || it.id in live.hiddenIds || it.id in live.expiredIds }
                .filterNot { expired(it, ttl, live.nowMillis) }
                .map(::message)
        if (rows.isEmpty()) return null
        return Conversation(
            peerAddress = chat.peerAddress,
            messages = rows,
            lastMessage = rows.maxByOrNull { it.timestamp },
            contactName = live.contactNameOf(chat.peerAddress),
            draft = live.drafts[chat.peerAddress],
        )
    }

    private fun expired(
        m: ChatMessage,
        ttl: ZchatPreferences.DisappearingTtl?,
        nowMillis: Long,
    ): Boolean {
        if (ttl == null || ttl.ttlSeconds <= 0L) return false
        // Its expiry would need the stored unlock anchor (written by the pass): dropped, never written here.
        if (m.timeLock != null) return true
        val expireAt =
            DisappearPolicy.expireAtMillis(
                m.timestamp.toEpochMilli(),
                nowMillis,
                ttl.ttlSeconds,
                ttl.effectiveSinceMillis,
                m.isPending,
                m.status == MessageStatus.FAILED,
                m.isSystemNote,
                m.callLog != null,
                null,
                null,
                null,
            ) ?: return false
        return expireAt <= nowMillis
    }

    private fun message(m: ChatMessage): ChatMessage {
        val mined = m.minedHeight != null && (m.status == MessageStatus.CONFIRMED || m.status == MessageStatus.READ)
        val special =
            m.timeLock != null ||
                m.paymentRequest != null ||
                m.fileHash != null ||
                m.fileZfileContent != null ||
                m.fileType != null ||
                m.callLog != null ||
                m.isAiMessage ||
                m.sendAmountZatoshi != null
        return ChatMessage(
            id = m.id,
            txId = null,
            text = if (special) "" else m.text,
            timestamp = m.timestamp,
            isOutgoing = m.isOutgoing,
            peerAddress = m.peerAddress,
            status = if (mined) m.status else MessageStatus.SENT,
            minedHeight = if (mined) m.minedHeight else null,
            optimistic = true,
            statusChecking = !mined,
            bodyChecking = special,
        )
    }
}
