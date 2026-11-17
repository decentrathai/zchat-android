package co.electriccoin.zcash.ui.screen.chat.model

import androidx.annotation.StringRes
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import java.time.Instant

/**
 * Privacy pool type for the user's funds.
 */
enum class PoolType {
    /**
     * The Ironwood pool, added by NU6.3 (mainnet 2026-07-28). A second value pool of the same
     * Orchard protocol, and where value now lives: an Orchard Action must set
     * `enableCrossAddress = 0` (ZIP 258), so it can only pay its own address — anything sent to a
     * peer is an Ironwood output.
     */
    IRONWOOD,
    ORCHARD,      // Newest, most private pool (recommended)
    SAPLING,      // Older shielded pool
    TRANSPARENT,  // Not private, visible on blockchain
    MIXED         // Funds in multiple pools
}

/**
 * Privacy status information for the dashboard.
 */
data class PrivacyStatus(
    val poolType: PoolType,
    val orchardBalance: Zatoshi = Zatoshi(0),
    val ironwoodBalance: Zatoshi = Zatoshi(0),
    val saplingBalance: Zatoshi = Zatoshi(0),
    val transparentBalance: Zatoshi = Zatoshi(0),
    val isFullyShielded: Boolean = false,
    /** Spendable (confirmed) value per Orchard-protocol pool; the totals above include pending value. */
    val orchardAvailable: Zatoshi = orchardBalance,
    val ironwoodAvailable: Zatoshi = ironwoodBalance,
    /**
     * How far the soonest-to-confirm pending note has come, while [isIronwoodConfirming]; null when
     * nothing is confirming or the chain tip / mined height is not known yet. Display only — the
     * SDK's `available` value above is what actually gates the send.
     */
    val confirmationProgress: ConfirmationProgress? = null,
    /**
     * Round 8 D7 (sweep #10) — the part of [ironwoodBalance] that is our own change still confirming (a send's
     * change, a shielding, a crossing), as the SDK reports it (`changePending`). A deposit from someone else is
     * `valuePending` and is not counted here.
     */
    val ironwoodChangePending: Zatoshi = Zatoshi(0),
    /**
     * ZC-1271 — how far the soonest-to-confirm note has come, whatever the pools hold ([confirmationProgress] is only for a
     * stranded wallet). Display only: what a first message waiting for its funds shows on its row.
     */
    val pendingFundsProgress: ConfirmationProgress? = null,
) {
    companion object {
        val DEFAULT = PrivacyStatus(
            poolType = PoolType.IRONWOOD,
            isFullyShielded = true
        )
    }

    /**
     * ZC-1482: the privacy card's anonymity-set line, as a string resource (it was an English literal). Deliberately
     * qualitative: a hardcoded note count goes stale the moment the network moves, and NU6.3 moved it hard (Orchard was
     * sealed on 2026-07-28 and most of its value has since crossed the turnstile).
     */
    @get:androidx.annotation.StringRes
    val anonymitySetRes: Int
        get() =
            when (poolType) {
                PoolType.IRONWOOD -> co.electriccoin.zcash.ui.R.string.chat_privacy_anonymity_ironwood
                PoolType.ORCHARD -> co.electriccoin.zcash.ui.R.string.chat_privacy_anonymity_orchard
                PoolType.SAPLING -> co.electriccoin.zcash.ui.R.string.chat_privacy_anonymity_sapling
                PoolType.MIXED ->
                    when {
                        ironwoodBalance > Zatoshi(0) -> co.electriccoin.zcash.ui.R.string.chat_privacy_anonymity_ironwood
                        orchardBalance > Zatoshi(0) -> co.electriccoin.zcash.ui.R.string.chat_privacy_anonymity_orchard
                        else -> co.electriccoin.zcash.ui.R.string.chat_privacy_anonymity_sapling
                    }
                PoolType.TRANSPARENT -> co.electriccoin.zcash.ui.R.string.chat_privacy_anonymity_none
            }

    /** ZC-1482: the privacy card's pool name, as a string resource (it was an English literal). */
    @get:androidx.annotation.StringRes
    val poolDisplayNameRes: Int
        get() =
            when (poolType) {
                PoolType.IRONWOOD -> co.electriccoin.zcash.ui.R.string.chat_privacy_pool_ironwood
                PoolType.ORCHARD -> co.electriccoin.zcash.ui.R.string.chat_privacy_pool_orchard
                PoolType.SAPLING -> co.electriccoin.zcash.ui.R.string.chat_privacy_pool_sapling
                PoolType.TRANSPARENT -> co.electriccoin.zcash.ui.R.string.chat_privacy_pool_transparent
                PoolType.MIXED -> co.electriccoin.zcash.ui.R.string.chat_privacy_pool_mixed
            }

    val needsShielding: Boolean
        get() = transparentBalance > Zatoshi(0) || (poolType == PoolType.SAPLING && saplingBalance > Zatoshi(0))

    /**
     * Value is in Orchard and NONE is in Ironwood. Since NU6.3 an Orchard Action may only pay its
     * own address (ZIP 258: enableCrossAddress = 0), so this wallet cannot reach a peer on-chain at
     * all, and the SDK would fund the attempt as a turnstile crossing whose amount is public
     * (ZIP 318). It is shielded, and it is stuck. Neither [isFullyShielded] nor [needsShielding]
     * can express that, so the send gate and the privacy card read this flag directly.
     */
    val isOrchardStranded: Boolean
        get() = ironwoodAvailable == Zatoshi(0) && orchardAvailable > Zatoshi(0)

    /**
     * Stranded, but the way out is already under way: Ironwood value has landed and is confirming.
     * The right instruction is "wait", not "migrate".
     */
    val isIronwoodConfirming: Boolean
        get() = isOrchardStranded && ironwoodBalance > Zatoshi(0)

    /**
     * Round 8 D7 (sweep #10, #12) — stranded only because our own change is confirming: a wallet that still holds
     * Orchard (not moved yet, or dust too small to move) whose only Ironwood note is locked by its own send. That
     * resolves in a few blocks without the user doing anything, so a send waits for the next block instead of
     * being refused with the Stranded funds dialog. The send chokepoint still refuses every attempt until Ironwood is
     * spendable, so nothing is ever paid from Orchard.
     */
    val isWaitingForOwnChange: Boolean
        get() = isOrchardStranded && ironwoodChangePending > Zatoshi(0)
}

/**
 * Pure classification of a wallet's pools into a [PrivacyStatus]. Extracted from the ViewModel so
 * every balance combination can be unit-tested; the ViewModel only supplies the four numbers.
 */
@Suppress("ReturnCount")
internal fun derivePrivacyStatus(
    orchardBalance: Zatoshi,
    ironwoodBalance: Zatoshi,
    saplingBalance: Zatoshi,
    transparentBalance: Zatoshi,
    orchardAvailable: Zatoshi = orchardBalance,
    ironwoodAvailable: Zatoshi = ironwoodBalance,
    ironwoodChangePending: Zatoshi = Zatoshi(0),
): PrivacyStatus {
    val total = orchardBalance.value + ironwoodBalance.value + saplingBalance.value + transparentBalance.value
    val onlyIn = { pool: Zatoshi -> pool > Zatoshi(0) && total == pool.value }

    // Ironwood is checked before Orchard: after NU6.3 it is the pool a wallet actually spends from.
    val poolType = when {
        onlyIn(transparentBalance) -> PoolType.TRANSPARENT
        onlyIn(saplingBalance) -> PoolType.SAPLING
        onlyIn(ironwoodBalance) -> PoolType.IRONWOOD
        onlyIn(orchardBalance) -> PoolType.ORCHARD
        total > 0 -> PoolType.MIXED
        else -> PoolType.IRONWOOD // No funds yet: incoming value lands in Ironwood
    }

    // "Fully shielded" means no transparent value — nothing more. Deriving it from poolType made a
    // MIXED wallet read as unshielded even when every pool it held was shielded, and after NU6.3 an
    // Orchard+Ironwood split is the normal state of a wallet mid-crossing. With transparent == 0
    // the poolType can never be TRANSPARENT, and the empty wallet stays "shielded" as before.
    val isFullyShielded = transparentBalance == Zatoshi(0)

    return PrivacyStatus(
        poolType = poolType,
        orchardBalance = orchardBalance,
        ironwoodBalance = ironwoodBalance,
        saplingBalance = saplingBalance,
        transparentBalance = transparentBalance,
        isFullyShielded = isFullyShielded,
        orchardAvailable = orchardAvailable,
        ironwoodAvailable = ironwoodAvailable,
        ironwoodChangePending = ironwoodChangePending,
    )
}

/**
 * Message delivery status for outgoing messages.
 */
enum class MessageStatus {
    SENDING,    // Transaction being created/submitted (clock icon)
    SENT,       // Transaction submitted to mempool (single checkmark)
    CONFIRMED,  // Transaction confirmed on blockchain (double checkmark)
    READ,       // Recipient sent read receipt (blue double checkmark)
    FAILED, // Transaction failed (error icon)

    // ZC-1464: a relay message the contact never saw: they declined our chat request. Not FAILED, so it offers no Retry.
    DECLINED,
}

/**
 * Represents a chat message derived from a Zcash transaction memo.
 */
data class ChatMessage(
    val id: String,
    val txId: TransactionId?, // Nullable for pending messages that don't have a tx yet
    val text: String,
    val timestamp: Instant,
    val isOutgoing: Boolean,
    val peerAddress: String,
    val isPending: Boolean = false,
    val status: MessageStatus = MessageStatus.SENT, // Delivery status for outgoing messages
    val unknownReason: UnknownReason? = null,
    // Block height for accurate ordering (null for pending messages)
    val minedHeight: Long? = null,
    // Transaction index within block for same-block ordering (null for pending messages)
    val txIndex: Int? = null,
    // Reply feature
    val replyToId: String? = null,           // Transaction ID this message is replying to
    val replyToPreview: String? = null,      // Preview text of the quoted message
    // Reactions
    val reactions: List<MessageReaction> = emptyList(),
    // Read receipts
    val isRead: Boolean = false,             // Whether recipient has read this message
    val readAt: Instant? = null,             // When the message was read
    // Time-lock feature
    val timeLock: TimeLockInfo? = null,      // Time-lock information if this is a locked message
    // Payment request feature
    val paymentRequest: PaymentRequestInfo? = null,  // Payment request if this is a request message
    // File sharing (Phase 2): SHA-256 hash key for FileDownloadCache lookup
    val fileHash: String? = null,
    // File sharing: full serialized ZFILE| content (needed for download URL + wrappedKey)
    val fileZfileContent: String? = null,
    // File sharing: Blurhash for low-res placeholder while download is in progress
    val fileBlurhash: String? = null,
    // File sharing: resolved ZFILEType so the renderer can branch image vs audio vs document
    // without re-parsing fileZfileContent (which is null on outgoing optimistic bubbles).
    val fileType: ZFILEType? = null,
    // Voice messages: original recording length in milliseconds (used for the audio bubble
    // label "0:23" while the file is still downloading).
    val fileDurationMs: Long? = null,
    // View-once: file deletes itself after the recipient has rendered it once
    // (image fullscreen close) or listened to it to completion (audio playback).
    val fileViewOnce: Boolean = false,
    // Has this device already consumed the view-once file? Persisted via ZchatPreferences.
    // When true, the bubble collapses to a "Viewed" placeholder and the cache file is gone.
    val fileViewed: Boolean = false,
    // AI: local-only message inserted by /ai slash command (never on-chain).
    // When true, the bubble renders with distinct "AI" styling + "external" badge.
    val isAiMessage: Boolean = false,
    // Call log: local-only system entry inserted when a voice/video call ends. Never on-chain,
    // never ratcheted. Rendered as a centered pill, not a sender bubble.
    val callLog: CallLogInfo? = null,
    // System note: local-only informational pill (e.g. "contact rotated their key"). Never on-chain,
    // never counted as unread, rendered centered like a call-log pill (#225).
    val isSystemNote: Boolean = false,
    // D9 "mark, don't reroute": false ONLY for an incoming on-chain body that was NOT a ratcheted
    // E2E1 blob (a plaintext memo anyone can pay to send into this thread). Defaults true so
    // outgoing/local/system/call-log/pending/NOSTR builders are treated as authenticated. The UI
    // shows an "unverified" chip on !authenticated incoming bubbles in an established thread.
    val authenticated: Boolean = true,
    // Round 10b R10B-2 (F-R9C-3): true when this row was mined at or below the chat's session height (its earliest
    // trusted mined KEXACK, MessageAuthentication.sessionHeight), so it was sent before the key exchange completed and
    // could not have been signed with the contact's key: no "Sender not verified" chip. False when unknown. ZC-1910: also
    // true on the contact's plaintext mined before its first ratcheted message (MessageAuthentication.markBeforeContactRatchet).
    val preSession: Boolean = false,
    // The value per output the user chose for an OUTGOING on-chain send (the composer's amount picker), kept
    // so Retry re-sends a FAILED message at that amount rather than the 1000-zatoshi default. Null = default.
    val sendAmountZatoshi: Long? = null,
    // ZC-1273 — the true amount of an on-chain payment row, read per output from the wallet's note tables
    // (PaymentBubble.amountFromOutputs); null when it is not a payment or the amount cannot be read. Display only.
    val paymentAmountZatoshi: Long? = null,
    // P2a — when the on-chain submit of this outgoing message started (null = it never did) and the chain tip
    // then known (null = unknown). A FAILED message with a start time may already be on its way: Retry must
    // first prove it did not go out (SubmitAttemptProof), and the bubble says so.
    val submitStartedAtMillis: Long? = null,
    val submitAttemptHeight: Long? = null,
    val submittedTxIds: List<String> = emptyList(),
    // P2a — an outgoing on-chain pending row written by an older build (no submit tracking, so no IDs). Only
    // a retire-only match can clear it: a later mined outgoing transaction to the same contact with the same text.
    val legacyUntrackedSubmit: Boolean = false,
    // R2-7 (c) — why an outgoing message FAILED, in the words the user was shown; the bubble shows it above
    // Retry (ChatSendCopy.failureReasonFor). Null = none recorded (older rows, or a path that records none).
    val failureReason: String? = null,
    // ZC-1271 — an accepted first message waiting for its funds to confirm: its row shows how far they are.
    val waitsForFunds: Boolean = false,
    // F4 — the transaction ids of earlier attempts that a proven expiry replaced (a late landing of one is noticed).
    val supersededTxIds: List<String> = emptyList(),
    // F4 — the scanned height at which the history pass proved this send never reached the chain (null: not proven).
    val notSentProvenAtHeight: Long? = null,
    // F4 — what the chat's history pass last read about this send's attempt; never persisted (null: no pass judged it).
    val attemptVerdict: co.electriccoin.zcash.ui.common.sendattempt.SendAttemptVerdict? = null,
    // ZC-946 part 1 — a send's own row drawn before a finished pass has it (PendingOverlay), in memory only: it says
    // "sending" and its bubble offers no action until the pass's row takes over.
    val optimistic: Boolean = false,
    // ZC-946 part 2 — a row of the last finished pass shown before this screen's own pass (CachedSnapshotMask), in
    // memory only: its status is not known yet ("Checking…"), and its body is not shown (a lock, a request, a file,
    // a call, an AI row, an amount).
    val statusChecking: Boolean = false,
    val bodyChecking: Boolean = false,
) {
    /** P2a — a FAILED outgoing message whose submit started: it may already have been sent. */
    val sendMayHaveGoneOut: Boolean
        get() = isOutgoing && status == MessageStatus.FAILED && submitStartedAtMillis != null

    /**
     * Computed status based on message state.
     * Priority: FAILED > SENDING > READ > CONFIRMED > SENT
     */
    val effectiveStatus: MessageStatus
        get() = when {
            status == MessageStatus.FAILED -> MessageStatus.FAILED
            isPending -> MessageStatus.SENDING
            isRead -> MessageStatus.READ
            txId != null -> MessageStatus.CONFIRMED
            else -> status
        }

    /**
     * Recover the raw on-chain payload to re-send for a FAILED message (Bug 8b retry).
     *
     * File messages keep their serialized "ZFILE|…" in [fileZfileContent]; a plain text message is
     * its own raw payload. Returns null when the raw memo is not recoverable from this bubble alone —
     * locked / payment-request / ZBOOT bubbles only retain their decoded placeholder text, and a file
     * bubble missing its serialized memo cannot be rebuilt. A null result means "not retryable".
     */
    fun recoverRawSendPayload(): String? = when {
        fileZfileContent != null -> fileZfileContent
        fileHash != null -> null // file bubble lost its serialized ZFILE memo
        isLocked || isPaymentRequest -> null // raw memo not retained for these
        // Precise ZBOOT placeholder match (raw memo not retained) — must NOT swallow a user
        // message that merely starts with the 🔐 emoji, which would make Retry a silent no-op.
        text.startsWith("🔐 Secure connection request") -> null
        text.isBlank() -> null
        else -> text
    }

    /**
     * Check if this message is currently locked
     */
    val isLocked: Boolean
        get() = timeLock != null && !timeLock.isUnlocked

    /**
     * Check if this is a payment request
     */
    val isPaymentRequest: Boolean
        get() = paymentRequest != null

    /** True if this is a local call-log entry (incoming / outgoing / missed call). */
    val isCallLog: Boolean
        get() = callLog != null

    /**
     * Get the display text (hidden if locked, formatted if request)
     */
    val displayText: String
        get() = when {
            isLocked -> "🔒 ${timeLock?.lockDescription ?: "Locked message"}"
            isPaymentRequest -> paymentRequest?.reason?.ifEmpty { "Payment requested" } ?: text
            else -> text
        }

    /**
     * Normalize a message whose [text] still holds a raw protocol payload — e.g. an older file
     * message persisted before the file UI existed, or a load path that didn't convert it. Parses
     * the raw "ZFILE|…"/"ZBOOT|…" string so the message renders as the rich file bubble (thumbnail /
     * voice player / "📎 Image · 149 KB") or a friendly note, and is never shown as the raw protocol
     * string to either side. No-op for already-recognized file messages (fileHash set) or plain text.
     */
    fun forDisplay(): ChatMessage {
        val cleanReply =
            if (replyToPreview != null && ZFILEMessage.isFileMessage(replyToPreview)) {
                ZFILEMessage.parse(replyToPreview)?.let { "📎 ${it.displayText}" } ?: replyToPreview
            } else {
                replyToPreview
            }
        val fileMsg = if (fileHash == null && ZFILEMessage.isFileMessage(text)) ZFILEMessage.parse(text) else null
        return when {
            // Call-log rows carry their text in callLog (icon + labelRes), not [text]. ZC-1577: the label is a
            // resource, so the screens that show it (the pill, the chat-list preview) resolve it themselves.
            callLog != null -> if (cleanReply != replyToPreview) copy(replyToPreview = cleanReply) else this

            fileMsg != null ->
                copy(
                    text = "📎 ${fileMsg.displayText}",
                    fileHash = fileMsg.hash,
                    fileZfileContent = text,
                    fileBlurhash = fileMsg.blurhash.takeIf { it.isNotEmpty() },
                    fileType = fileMsg.type,
                    fileViewOnce = fileMsg.viewOnce,
                    replyToPreview = cleanReply,
                )

            fileHash == null && ZBootMessage.isBootMessage(text) && ZBootMessage.parse(text) != null ->
                copy(
                    text = if (isOutgoing) "🔐 Secure connection request sent" else "🔐 Secure connection request",
                    replyToPreview = cleanReply,
                )

            cleanReply != replyToPreview -> copy(replyToPreview = cleanReply)
            else -> this
        }
    }
}

/**
 * Time-lock information for a message
 */
enum class CallLogType { OUTGOING, INCOMING, MISSED, DECLINED }

/** Local call-log entry rendered as a centered pill in the conversation. */
data class CallLogInfo(
    val type: CallLogType,
    val isVideo: Boolean = false,
    val durationSec: Long? = null, // null for missed / declined / no-answer
) {
    val icon: String
        get() = when (type) {
            CallLogType.DECLINED -> "📵"
            // MISSED/INCOMING/OUTGOING all reflect the call medium, so a missed VIDEO call shows 📹 not 📞.
            else -> if (isVideo) "📹" else "📞"
        }

    /**
     * QA2 Q2-76 (ZC-1577): the pill's text as a resource, in the phone's language. An outgoing call that never
     * connected (no duration) says "No answer", so it no longer reads like one that did.
     */
    @get:StringRes
    val labelRes: Int
        get() =
            when (type) {
                CallLogType.OUTGOING ->
                    when {
                        durationSec == null -> R.string.call_log_no_answer
                        isVideo -> R.string.call_log_outgoing_video
                        else -> R.string.call_log_outgoing
                    }
                CallLogType.INCOMING -> if (isVideo) R.string.call_log_incoming_video else R.string.call_log_incoming
                CallLogType.MISSED -> if (isVideo) R.string.call_log_missed_video else R.string.call_log_missed
                CallLogType.DECLINED -> if (isVideo) R.string.call_log_declined_video else R.string.call_log_declined
            }

    /** The call's length as m:ss (h:mm:ss from an hour), or null when it never connected. */
    val durationText: String?
        get() = durationSec?.let { formatCallDuration(it) }

    private fun formatCallDuration(s: Long): String =
        if (s >= 3600) {
            "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        } else {
            "%d:%02d".format(s / 60, s % 60)
        }
}

data class TimeLockInfo(
    val lockType: TimeLockType,
    val unlockTimestamp: Long? = null,       // For SCHEDULED
    val unlockBlockHeight: Long? = null,     // For BLOCK_HEIGHT
    val requiredPaymentZatoshi: Long? = null, // For PAYMENT
    val hint: String? = null,                // For CONDITIONAL
    val answerHash: String? = null,          // For CONDITIONAL (hashed answer)
    val isUnlocked: Boolean = false,         // Has this been unlocked?
    val unlockedBy: String? = null           // TxId that unlocked this message
) {
    /**
     * Get human-readable lock description
     */
    val lockDescription: String
        get() = when (lockType) {
            TimeLockType.SCHEDULED -> {
                unlockTimestamp?.let {
                    val remaining = it - (System.currentTimeMillis() / 1000)
                    if (remaining <= 0) "Ready to view"
                    else "Unlocks in ${formatDuration(remaining)}"
                } ?: "Scheduled message"
            }
            TimeLockType.BLOCK_HEIGHT -> {
                unlockBlockHeight?.let { "Unlocks at block #$it" } ?: "Block-locked"
            }
            TimeLockType.PAYMENT -> {
                requiredPaymentZatoshi?.let {
                    val zec = it / 100_000_000.0
                    "Sender requested ${String.format(java.util.Locale.US, "%.5f", zec)} ZEC"
                } ?: "Payment requested"
            }
            TimeLockType.CONDITIONAL -> {
                hint?.let { "Answer: $it" } ?: "Secret answer required"
            }
        }

    /**
     * Icon for the lock type
     */
    val lockIcon: String
        get() = when (lockType) {
            TimeLockType.SCHEDULED -> "⏰"
            TimeLockType.BLOCK_HEIGHT -> "⛓️"
            TimeLockType.PAYMENT -> "💰"
            TimeLockType.CONDITIONAL -> "❓"
        }

    private fun formatDuration(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
        }
    }
}

/**
 * Payment request information for a message
 */
data class PaymentRequestInfo(
    val amountZatoshi: Long,
    val reason: String,
    /** Pay is hidden: this phone paid it (or started to), or, on our own request, the contact's payment is mined. */
    val isPaid: Boolean = false,
    val paidTxId: String? = null,
    /**
     * F5 (fix-all 2.13.0, train T5): with [isPaid], our payment of this request is not mined yet (or the wallet no
     * longer shows it), so the bubble says "Payment pending", not "Paid". Computed by every history pass.
     */
    val paymentPending: Boolean = false,
) {
    /**
     * Get amount in ZEC
     */
    val amountZec: Double
        get() = amountZatoshi / 100_000_000.0

    /**
     * Get formatted ZEC amount
     */
    fun getFormattedAmount(): String {
        return String.format("%.8f", amountZec).trimEnd('0').trimEnd('.')
    }

    /**
     * Get USD equivalent
     */
    fun getAmountUsd(zecPriceUsd: Double?): Double? {
        return zecPriceUsd?.let { amountZec * it }
    }

    /**
     * Get display string for the request
     */
    fun getDisplayString(zecPriceUsd: Double? = null): String {
        val zecStr = "${getFormattedAmount()} ZEC"
        val usdStr = getAmountUsd(zecPriceUsd)?.let { " (≈$${String.format("%.2f", it)})" } ?: ""
        return "$zecStr$usdStr"
    }
}

/**
 * Represents a reaction to a message
 */
data class MessageReaction(
    val emoji: String,
    val senderAddress: String?,
    val timestamp: Instant
)

/**
 * State for reply mode in chat
 */
data class ReplyState(
    val isReplying: Boolean = false,
    val replyToMessage: ChatMessage? = null
)

/**
 * Represents a user's status (visible to contacts).
 */
data class UserStatus(
    val text: String,
    val updatedAt: Instant = Instant.now()
) {
    companion object {
        val DEFAULT = UserStatus("")

        // Common status presets
        val PRESETS = listOf(
            "Available",
            "Busy",
            "At work",
            "In a meeting",
            "Do not disturb",
            "On vacation",
            "Be right back"
        )
    }
}

/**
 * Represents a conversation with a peer.
 */
data class Conversation(
    val peerAddress: String,
    val messages: List<ChatMessage>,
    val lastMessage: ChatMessage?,
    val unreadCount: Int = 0,
    val peerStatus: UserStatus? = null,  // Status from the contact
    val contactName: String? = null,  // Name from contact book, if contact exists
    val draft: String? = null,  // Unsent draft message for this conversation
    val e2eEnabled: Boolean = false,  // Whether E2E encryption is enabled
    val e2eKeyExchangeComplete: Boolean = false,  // Whether key exchange is complete
    // #257 tri-state honesty: default true/false so un-hydrated Conversations degrade to the OLD semantics.
    val e2eAckSettled: Boolean = true,   // responder's KEXACK delivered (or initiator/legacy — E2EAckSettlement)
    val e2eKexInFlight: Boolean = false, // initiator window: our KEX sent (isOwnBootSent) but peer key not held yet
    val isMuted: Boolean = false,  // Whether notifications are muted for this conversation
    // True once we hold the peer's NOSTR pubkey (learned from a verified ZBOOT or KEX/KEXACK).
    // A call routes purely over that NOSTR identity (startCall → placeCall(peerNostrPubkey)) on the
    // free relay — it never spends on-chain — so calls are placeable whenever this is true, EVEN in a
    // VAULT message conversation. Gating call buttons on the message mode alone wrongly hid them here.
    val hasNostrCallChannel: Boolean = false,
    // Round 8 D9 (DECISIONS_NIGHT §3): a Shielded chat whose contact has not answered the key exchange yet, so its
    // messages go out without ZChat's own end-to-end layer (ChatViewModel.appLayerE2EPending, the predicate the send
    // path uses). The ⋮ menu says so instead of "always on".
    val appLayerE2EPending: Boolean = false,
    // W-72 (SPEC_F R8): this chat's key carries a pre-update mark (stored before 2.13 or rebuilt by a restore, not proven
    // since). The chat list marks the row; the chat shows "Not confirmed".
    val keyNotConfirmed: Boolean = false
) {
    /**
     * Whether this conversation has a draft.
     */
    val hasDraft: Boolean
        get() = !draft.isNullOrBlank()

    /**
     * Whether E2E encryption is ready (enabled and keys exchanged).
     */
    val isE2EReady: Boolean
        get() = e2eEnabled && e2eKeyExchangeComplete

    /** Honest handshake state for the ⋮-menu row (#257). ON only when we hold both keys AND our KEXACK is
     *  settled; FINISHING covers the responder's un-delivered-ack window and the initiator's KEX→KEXACK
     *  window (incl. the silent bootstrap KEX); OFF when there's no key material or intent. */
    val e2eHandshakeState: E2EHandshakeState
        get() = when {
            e2eKeyExchangeComplete && e2eAckSettled -> E2EHandshakeState.ON
            e2eKeyExchangeComplete -> E2EHandshakeState.FINISHING
            e2eEnabled || e2eKexInFlight -> E2EHandshakeState.FINISHING
            else -> E2EHandshakeState.OFF
        }
    /**
     * Display name for this conversation.
     * Uses contact name if available, otherwise truncated address.
     */
    val displayName: String
        get() = contactName ?: truncateAddress(peerAddress)

    /**
     * Whether this conversation has a contact name (for UI display).
     */
    val hasContactName: Boolean
        get() = contactName != null

    companion object {
        fun truncateAddress(address: String, prefixLen: Int = 8, suffixLen: Int = 6): String {
            if (address.length <= prefixLen + suffixLen + 3) return address
            return "${address.take(prefixLen)}...${address.takeLast(suffixLen)}"
        }
    }
}

/**
 * State for the chat list screen.
 */
/**
 * Wallet sync status for displaying progress during restore/sync.
 */
data class WalletSyncStatus(
    val isRestoring: Boolean = false,
    val isInitiating: Boolean = false,
    val isSyncing: Boolean = false,
    val progress: Float = 0f, // 0-100%
    val statusMessage: String = "",
    val scanningRange: String? = null, // e.g., "Blocks 2,500,000 - 2,847,000"
    // SY-2: the SDK's own status (null before a synchronizer exists), for the chat list's status line.
    val sdkStatus: Synchronizer.Status? = null,
    // SY-2: when the wallet last became fully synced in this process (WalletSyncClock), or null.
    val syncedAtMillis: Long? = null
)

/** ZC-946 part 2 — where a shown state comes from: this view model's own finished pass, or the last one kept (masked). */
enum class StateOrigin { LIVE, CACHED }

sealed interface ChatListState {
    data object Loading : ChatListState
    data class Success(
        val conversations: List<Conversation>,
        val groups: List<GroupInfo> = emptyList(),
        // Round 8 D4: the ids of [groups] whose invite came from a new address and waits for Join.
        val groupsAwaitingJoin: Set<String> = emptySet(),
        val currentUserAddress: String,
        val balance: Zatoshi = Zatoshi(0),
        // C-5: the Ironwood that can pay another person. [balance] stays the displayed total.
        val peerSendableBalance: Zatoshi = Zatoshi(0),
        val lastSyncTime: Instant? = null,
        val isRefreshing: Boolean = false,
        val secondsUntilNextSync: Int = 0,
        val blockHeight: Long? = null,
        val zecPriceUsd: Double? = null,
        val privacyStatus: PrivacyStatus = PrivacyStatus.DEFAULT,
        val syncStatus: WalletSyncStatus = WalletSyncStatus(),
        // ZC-946 part 1: the ids of the pending rows the pass that produced [conversations] received (shown or not),
        // so the display never draws such a row early again (PendingOverlay).
        val renderedPendingIds: Set<String> = emptySet(),
        // ZC-946 part 2: CACHED = the last finished pass of this wallet, masked, shown while this view model's own pass
        // runs (the display flow only; _chatListState is never CACHED).
        val origin: StateOrigin = StateOrigin.LIVE
    ) : ChatListState
    data class Error(val message: String) : ChatListState
}

/**
 * State for the chat detail screen.
 */
sealed interface ChatDetailState {
    data object Loading : ChatDetailState
    data class Success(
        val conversation: Conversation,
        val currentUserAddress: String,
        val balance: Zatoshi = Zatoshi(0),
        // C-5: the in-chat Payment dialog's budget (Ironwood available); [balance] stays the displayed total.
        val peerSendableBalance: Zatoshi = Zatoshi(0),
        val zecPriceUsd: Double? = null,
        val privacyStatus: PrivacyStatus = PrivacyStatus.DEFAULT,
        // ZC-946 part 2: copied from the list state it was built from.
        val origin: StateOrigin = StateOrigin.LIVE
    ) : ChatDetailState
    data class Error(val message: String) : ChatDetailState
}

/**
 * State for the payment dialog.
 */
data class PaymentDialogState(
    val isVisible: Boolean = false,
    val amountZec: String = "",
    val memo: String = "",
    val splitCount: Int = 1,
    val isSending: Boolean = false,
    val error: String? = null
) {
    val amountZecDouble: Double
        get() = amountZec.toDoubleOrNull() ?: 0.0

    val perPersonAmount: Double
        get() = if (splitCount > 0) amountZecDouble / splitCount else amountZecDouble

    fun getAmountUsd(zecPriceUsd: Double?): Double? =
        zecPriceUsd?.let { amountZecDouble * it }

    fun getPerPersonUsd(zecPriceUsd: Double?): Double? =
        zecPriceUsd?.let { perPersonAmount * it }

    val isValidAmount: Boolean
        get() = amountZecDouble > 0
}

/**
 * Memo template for quick payments with pre-defined amounts and messages.
 * Amount can be in USD (converted to ZEC at send time) or ZEC.
 */
data class MemoTemplate(
    val id: String,
    val name: String,              // Short name shown in picker (e.g., "Coffee")
    val emoji: String,             // Emoji icon for quick recognition
    val memo: String,              // Message to send with payment
    val amountUsd: Double?,        // Amount in USD (converted to ZEC)
    val amountZec: Double?,        // Amount in ZEC (used if amountUsd is null)
    val isBuiltIn: Boolean = false // Whether this is a system template
) {
    /**
     * Get the ZEC amount, converting from USD if needed
     */
    fun getZecAmount(zecPriceUsd: Double?): Double {
        return amountZec ?: (amountUsd?.let { usd ->
            zecPriceUsd?.let { price -> usd / price } ?: 0.0
        } ?: 0.0)
    }

    /**
     * Get display amount string
     */
    fun getDisplayAmount(): String {
        return when {
            amountUsd != null -> "$${String.format("%.2f", amountUsd)}"
            amountZec != null -> "${String.format("%.4f", amountZec)} ZEC"
            else -> "Custom"
        }
    }

    companion object {
        /**
         * Built-in templates for common transactions
         */
        val BUILT_IN_TEMPLATES = listOf(
            MemoTemplate(
                id = "coffee",
                name = "Coffee",
                emoji = "☕",
                memo = "Thanks for the coffee!",
                amountUsd = 5.0,
                amountZec = null,
                isBuiltIn = true
            ),
            MemoTemplate(
                id = "lunch",
                name = "Lunch",
                emoji = "🍔",
                memo = "Lunch is on me!",
                amountUsd = 15.0,
                amountZec = null,
                isBuiltIn = true
            ),
            MemoTemplate(
                id = "dinner",
                name = "Dinner",
                emoji = "🍽️",
                memo = "Thanks for dinner!",
                amountUsd = 30.0,
                amountZec = null,
                isBuiltIn = true
            ),
            MemoTemplate(
                id = "birthday",
                name = "Birthday",
                emoji = "🎂",
                memo = "Happy Birthday! 🎉",
                amountUsd = 25.0,
                amountZec = null,
                isBuiltIn = true
            ),
            MemoTemplate(
                id = "thanks",
                name = "Thanks",
                emoji = "🙏",
                memo = "Thank you so much!",
                amountUsd = 10.0,
                amountZec = null,
                isBuiltIn = true
            ),
            MemoTemplate(
                id = "tip",
                name = "Tip",
                emoji = "💰",
                memo = "Here's a tip for you!",
                amountUsd = 5.0,
                amountZec = null,
                isBuiltIn = true
            ),
            MemoTemplate(
                id = "beer",
                name = "Beer",
                emoji = "🍺",
                memo = "Grab a beer on me!",
                amountUsd = 8.0,
                amountZec = null,
                isBuiltIn = true
            ),
            MemoTemplate(
                id = "gas",
                name = "Gas",
                emoji = "⛽",
                memo = "Thanks for the ride!",
                amountUsd = 20.0,
                amountZec = null,
                isBuiltIn = true
            )
        )

        /**
         * Generate a unique ID for custom templates
         */
        fun generateId(): String = "custom_${System.currentTimeMillis()}"
    }
}
