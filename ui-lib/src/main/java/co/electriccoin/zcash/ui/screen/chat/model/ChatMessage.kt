package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import java.time.Instant

/**
 * Privacy pool type for the user's funds.
 */
enum class PoolType {
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
    val saplingBalance: Zatoshi = Zatoshi(0),
    val transparentBalance: Zatoshi = Zatoshi(0),
    val isFullyShielded: Boolean = false
) {
    companion object {
        // Approximate anonymity set size for Orchard pool
        const val ORCHARD_ANONYMITY_SET = "~2.5M notes"
        const val SAPLING_ANONYMITY_SET = "~4M notes"

        val DEFAULT = PrivacyStatus(
            poolType = PoolType.ORCHARD,
            isFullyShielded = true
        )
    }

    val anonymitySetEstimate: String
        get() = when (poolType) {
            PoolType.ORCHARD -> ORCHARD_ANONYMITY_SET
            PoolType.SAPLING -> SAPLING_ANONYMITY_SET
            PoolType.MIXED -> if (orchardBalance > Zatoshi(0)) ORCHARD_ANONYMITY_SET else SAPLING_ANONYMITY_SET
            PoolType.TRANSPARENT -> "None"
        }

    val poolDisplayName: String
        get() = when (poolType) {
            PoolType.ORCHARD -> "Orchard (Recommended)"
            PoolType.SAPLING -> "Sapling"
            PoolType.TRANSPARENT -> "Transparent (Not Private)"
            PoolType.MIXED -> "Mixed Pools"
        }

    val needsShielding: Boolean
        get() = transparentBalance > Zatoshi(0) || (poolType == PoolType.SAPLING && saplingBalance > Zatoshi(0))
}

/**
 * Message delivery status for outgoing messages.
 */
enum class MessageStatus {
    SENDING,    // Transaction being created/submitted (clock icon)
    SENT,       // Transaction submitted to mempool (single checkmark)
    CONFIRMED,  // Transaction confirmed on blockchain (double checkmark)
    READ,       // Recipient sent read receipt (blue double checkmark)
    FAILED      // Transaction failed (error icon)
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
) {
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
            // Call-log rows carry their text in callLog (icon + label), not [text], so the chat-list
            // preview would otherwise render blank. Surface "📞 Outgoing call · 2:13" etc.
            callLog != null ->
                copy(text = "${callLog.icon} ${callLog.label}", replyToPreview = cleanReply)

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

    val label: String
        get() {
            val kind = when (type) {
                CallLogType.OUTGOING -> "Outgoing ${if (isVideo) "video " else ""}call"
                CallLogType.INCOMING -> "Incoming ${if (isVideo) "video " else ""}call"
                CallLogType.MISSED -> "Missed ${if (isVideo) "video " else ""}call"
                CallLogType.DECLINED -> "${if (isVideo) "Video c" else "C"}all declined"
            }
            val dur = durationSec?.let { formatCallDuration(it) }
            return if (dur != null) "$kind · $dur" else kind
        }

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
                    "Pay ${String.format("%.5f", zec)} ZEC to reveal"
                } ?: "Payment required"
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
    val isPaid: Boolean = false,
    val paidTxId: String? = null
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
    val isMuted: Boolean = false,  // Whether notifications are muted for this conversation
    // True once we hold the peer's NOSTR pubkey (learned from a verified ZBOOT or KEX/KEXACK).
    // A call routes purely over that NOSTR identity (startCall → placeCall(peerNostrPubkey)) on the
    // free relay — it never spends on-chain — so calls are placeable whenever this is true, EVEN in a
    // VAULT message conversation. Gating call buttons on the message mode alone wrongly hid them here.
    val hasNostrCallChannel: Boolean = false
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
    val scanningRange: String? = null // e.g., "Blocks 2,500,000 - 2,847,000"
)

sealed interface ChatListState {
    data object Loading : ChatListState
    data class Success(
        val conversations: List<Conversation>,
        val groups: List<GroupInfo> = emptyList(),
        val currentUserAddress: String,
        val balance: Zatoshi = Zatoshi(0),
        val lastSyncTime: Instant? = null,
        val isRefreshing: Boolean = false,
        val secondsUntilNextSync: Int = 0,
        val blockHeight: Long? = null,
        val zecPriceUsd: Double? = null,
        val privacyStatus: PrivacyStatus = PrivacyStatus.DEFAULT,
        val syncStatus: WalletSyncStatus = WalletSyncStatus()
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
        val zecPriceUsd: Double? = null,
        val privacyStatus: PrivacyStatus = PrivacyStatus.DEFAULT
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
