package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Special message types for the ZMSG protocol.
 *
 * This file contains handlers for non-standard message types:
 * - Reactions (emoji responses)
 * - Read receipts
 * - Status updates
 * - Time-locked messages
 * - Payment requests
 * - Unlock messages
 *
 * These are extracted from ZMSGProtocol for better organization
 * and single-responsibility adherence.
 */
object ZMSGSpecialMessages {

    // ==========================================
    // PREFIXES (from ZMSGConstants)
    // ==========================================

    private const val REACTION_PREFIX = ZMSGConstants.Prefixes.REACTION
    private const val RECEIPT_PREFIX = ZMSGConstants.Prefixes.RECEIPT
    private const val STATUS_PREFIX = ZMSGConstants.Prefixes.STATUS
    private const val TIMELOCK_PREFIX = ZMSGConstants.Prefixes.TIMELOCK
    private const val UNLOCK_PREFIX = ZMSGConstants.Prefixes.UNLOCK
    private const val REQUEST_PREFIX = ZMSGConstants.Prefixes.REQUEST

    // Time-lock type constants (from ZMSGConstants)
    private const val LOCK_TYPE_SCHEDULED = ZMSGConstants.TimeLockTypes.SCHEDULED
    private const val LOCK_TYPE_BLOCK = ZMSGConstants.TimeLockTypes.BLOCK
    private const val LOCK_TYPE_PAYMENT = ZMSGConstants.TimeLockTypes.PAYMENT
    private const val LOCK_TYPE_CONDITIONAL = ZMSGConstants.TimeLockTypes.CONDITIONAL

    // ==========================================
    // REACTIONS
    // ==========================================

    /**
     * Create a reaction message
     * Format: ZREACT|<target_txid>|<emoji>|<sender_hash>
     */
    fun createReaction(targetTxId: String, emoji: String, senderAddress: String): String {
        val hash = ZMSGProtocol.generateAddressHash(senderAddress)
        return "$REACTION_PREFIX$targetTxId|$emoji|$hash"
    }

    /**
     * Parse a reaction memo
     */
    fun parseReaction(memo: String, addressCache: AddressCache): ParsedReaction? {
        if (!memo.startsWith(REACTION_PREFIX)) return null

        val content = memo.removePrefix(REACTION_PREFIX)
        val parts = content.split("|")

        if (parts.size < 2) return null

        val targetTxId = parts[0]
        val emoji = parts[1]
        val senderHash = parts.getOrNull(2)
        val senderAddress = senderHash?.let { addressCache.getAddress(it) }

        return ParsedReaction(
            targetTxId = targetTxId,
            emoji = emoji,
            senderAddress = senderAddress,
            senderHash = senderHash
        )
    }

    /**
     * Check if a memo is a reaction
     */
    fun isReaction(memo: String): Boolean = memo.startsWith(REACTION_PREFIX)

    // ==========================================
    // READ RECEIPTS
    // ==========================================

    /**
     * Create a read receipt message
     * Format: ZRCPT|<target_txid>|<sender_hash>
     */
    fun createReadReceipt(targetTxId: String, senderAddress: String): String {
        val hash = ZMSGProtocol.generateAddressHash(senderAddress)
        return "$RECEIPT_PREFIX$targetTxId|$hash"
    }

    /**
     * Parse a read receipt memo
     */
    fun parseReadReceipt(memo: String, addressCache: AddressCache): ParsedReadReceipt? {
        if (!memo.startsWith(RECEIPT_PREFIX)) return null

        val content = memo.removePrefix(RECEIPT_PREFIX)
        val parts = content.split("|")

        if (parts.isEmpty()) return null

        val targetTxId = parts[0]
        val senderHash = parts.getOrNull(1)
        val senderAddress = senderHash?.let { addressCache.getAddress(it) }

        return ParsedReadReceipt(
            targetTxId = targetTxId,
            senderAddress = senderAddress,
            senderHash = senderHash
        )
    }

    /**
     * Check if a memo is a read receipt
     */
    fun isReadReceipt(memo: String): Boolean = memo.startsWith(RECEIPT_PREFIX)

    // ==========================================
    // USER STATUS
    // ==========================================

    /**
     * Create a user status message
     * Format: ZSTAT|<status_text>|<sender_hash>
     * Status text is limited to 100 characters
     */
    fun createStatusMessage(statusText: String, senderAddress: String): String {
        val hash = ZMSGProtocol.generateAddressHash(senderAddress)
        val truncatedStatus = statusText.take(100)
        return "$STATUS_PREFIX$truncatedStatus|$hash"
    }

    /**
     * Parse a user status message
     */
    fun parseStatus(memo: String, addressCache: AddressCache): ParsedStatus? {
        if (!memo.startsWith(STATUS_PREFIX)) return null

        val content = memo.removePrefix(STATUS_PREFIX)
        val lastPipe = content.lastIndexOf('|')

        if (lastPipe == -1) return null

        val statusText = content.substring(0, lastPipe)
        val senderHash = content.substring(lastPipe + 1)
        val senderAddress = addressCache.getAddress(senderHash)

        return ParsedStatus(
            statusText = statusText,
            senderAddress = senderAddress,
            senderHash = senderHash
        )
    }

    /**
     * Check if a memo is a status update
     */
    fun isStatus(memo: String): Boolean = memo.startsWith(STATUS_PREFIX)

    // ==========================================
    // TIME-LOCKED MESSAGES
    // ==========================================

    /**
     * Create a scheduled message (unlocks at future timestamp)
     * Format: ZTL|SCH|<unlock_timestamp>|<sender_hash>|<message>
     *
     * @param unlockTimestamp Unix timestamp (seconds) when message becomes visible
     */
    fun createScheduledMessage(
        message: String,
        senderAddress: String,
        unlockTimestamp: Long
    ): String {
        val hash = ZMSGProtocol.generateAddressHash(senderAddress)
        return "$TIMELOCK_PREFIX$LOCK_TYPE_SCHEDULED|$unlockTimestamp|$hash|$message"
    }

    /**
     * Create a block-height locked message
     * Format: ZTL|BLK|<unlock_height>|<sender_hash>|<message>
     *
     * @param unlockHeight Block height when message becomes visible
     */
    fun createBlockLockedMessage(
        message: String,
        senderAddress: String,
        unlockHeight: Long
    ): String {
        val hash = ZMSGProtocol.generateAddressHash(senderAddress)
        return "$TIMELOCK_PREFIX$LOCK_TYPE_BLOCK|$unlockHeight|$hash|$message"
    }

    /**
     * Create a payment-to-reveal message
     * Format: ZTL|PAY|<required_zatoshi>|<sender_hash>|<message>
     *
     * @param requiredZatoshi Amount in zatoshi required to unlock (sent back to sender)
     */
    fun createPaymentLockedMessage(
        message: String,
        senderAddress: String,
        requiredZatoshi: Long
    ): String {
        val hash = ZMSGProtocol.generateAddressHash(senderAddress)
        return "$TIMELOCK_PREFIX$LOCK_TYPE_PAYMENT|$requiredZatoshi|$hash|$message"
    }

    /**
     * Create a conditional release message (secret answer required)
     * Format: ZTL|CND|<answer_hash>|<hint>|<sender_hash>|<message>
     *
     * @param answer The secret answer (will be hashed)
     * @param hint A hint for the recipient
     */
    fun createConditionalMessage(
        message: String,
        senderAddress: String,
        answer: String,
        hint: String
    ): String {
        val senderHash = ZMSGProtocol.generateAddressHash(senderAddress)
        // Hash the answer so it's not stored in plaintext
        val answerHash = ZMSGProtocol.generateAddressHash(answer.lowercase().trim())
        // Replace pipes in hint with dashes to avoid parsing issues
        val safeHint = hint.replace("|", "-")
        return "$TIMELOCK_PREFIX$LOCK_TYPE_CONDITIONAL|$answerHash|$safeHint|$senderHash|$message"
    }

    /**
     * Create an unlock payment memo (sent to unlock a PAY message)
     * Format: ZUNLOCK|PAY|<original_txid>|<sender_hash>
     */
    fun createUnlockPayment(originalTxId: String, senderAddress: String): String {
        val hash = ZMSGProtocol.generateAddressHash(senderAddress)
        return "$UNLOCK_PREFIX$LOCK_TYPE_PAYMENT|$originalTxId|$hash"
    }

    /**
     * Create an unlock answer memo (sent to unlock a CND message)
     * Format: ZUNLOCK|CND|<original_txid>|<answer>|<sender_hash>
     */
    fun createUnlockAnswer(originalTxId: String, answer: String, senderAddress: String): String {
        val hash = ZMSGProtocol.generateAddressHash(senderAddress)
        return "$UNLOCK_PREFIX$LOCK_TYPE_CONDITIONAL|$originalTxId|$answer|$hash"
    }

    /**
     * Parse a time-locked message
     */
    fun parseTimeLock(memo: String, addressCache: AddressCache): ParsedTimeLock? {
        if (!memo.startsWith(TIMELOCK_PREFIX)) return null

        val content = memo.removePrefix(TIMELOCK_PREFIX)
        val parts = content.split("|")

        if (parts.size < 4) return null

        return when (parts[0]) {
            LOCK_TYPE_SCHEDULED -> {
                // SCH|<timestamp>|<hash>|<message>
                val unlockTime = parts[1].toLongOrNull() ?: return null
                val senderHash = parts[2]
                val message = parts.drop(3).joinToString("|")
                val senderAddress = addressCache.getAddress(senderHash)

                ParsedTimeLock(
                    lockType = TimeLockType.SCHEDULED,
                    message = message,
                    senderAddress = senderAddress,
                    senderHash = senderHash,
                    unlockTimestamp = unlockTime,
                    unlockBlockHeight = null,
                    requiredPayment = null,
                    hint = null,
                    answerHash = null
                )
            }
            LOCK_TYPE_BLOCK -> {
                // BLK|<height>|<hash>|<message>
                val unlockHeight = parts[1].toLongOrNull() ?: return null
                val senderHash = parts[2]
                val message = parts.drop(3).joinToString("|")
                val senderAddress = addressCache.getAddress(senderHash)

                ParsedTimeLock(
                    lockType = TimeLockType.BLOCK_HEIGHT,
                    message = message,
                    senderAddress = senderAddress,
                    senderHash = senderHash,
                    unlockTimestamp = null,
                    unlockBlockHeight = unlockHeight,
                    requiredPayment = null,
                    hint = null,
                    answerHash = null
                )
            }
            LOCK_TYPE_PAYMENT -> {
                // PAY|<zatoshi>|<hash>|<message>
                val requiredPayment = parts[1].toLongOrNull() ?: return null
                val senderHash = parts[2]
                val message = parts.drop(3).joinToString("|")
                val senderAddress = addressCache.getAddress(senderHash)

                ParsedTimeLock(
                    lockType = TimeLockType.PAYMENT,
                    message = message,
                    senderAddress = senderAddress,
                    senderHash = senderHash,
                    unlockTimestamp = null,
                    unlockBlockHeight = null,
                    requiredPayment = requiredPayment,
                    hint = null,
                    answerHash = null
                )
            }
            LOCK_TYPE_CONDITIONAL -> {
                // CND|<answer_hash>|<hint>|<hash>|<message>
                if (parts.size < 5) return null
                val answerHash = parts[1]
                val hint = parts[2]
                val senderHash = parts[3]
                val message = parts.drop(4).joinToString("|")
                val senderAddress = addressCache.getAddress(senderHash)

                ParsedTimeLock(
                    lockType = TimeLockType.CONDITIONAL,
                    message = message,
                    senderAddress = senderAddress,
                    senderHash = senderHash,
                    unlockTimestamp = null,
                    unlockBlockHeight = null,
                    requiredPayment = null,
                    hint = hint,
                    answerHash = answerHash
                )
            }
            else -> null
        }
    }

    /**
     * Parse an unlock memo
     */
    fun parseUnlock(memo: String, addressCache: AddressCache): ParsedUnlock? {
        if (!memo.startsWith(UNLOCK_PREFIX)) return null

        val content = memo.removePrefix(UNLOCK_PREFIX)
        val parts = content.split("|")

        if (parts.size < 3) return null

        return when (parts[0]) {
            LOCK_TYPE_PAYMENT -> {
                // PAY|<txid>|<hash>
                ParsedUnlock(
                    unlockType = TimeLockType.PAYMENT,
                    originalTxId = parts[1],
                    senderAddress = addressCache.getAddress(parts[2]),
                    senderHash = parts[2],
                    answer = null
                )
            }
            LOCK_TYPE_CONDITIONAL -> {
                // CND|<txid>|<answer>|<hash>
                if (parts.size < 4) return null
                ParsedUnlock(
                    unlockType = TimeLockType.CONDITIONAL,
                    originalTxId = parts[1],
                    senderAddress = addressCache.getAddress(parts[3]),
                    senderHash = parts[3],
                    answer = parts[2]
                )
            }
            else -> null
        }
    }

    /**
     * Check if answer matches the hash in a conditional message
     */
    fun verifyConditionalAnswer(answer: String, answerHash: String): Boolean {
        val computedHash = ZMSGProtocol.generateAddressHash(answer.lowercase().trim())
        return computedHash == answerHash
    }

    /**
     * Check if a memo is a time-locked message
     */
    fun isTimeLock(memo: String): Boolean = memo.startsWith(TIMELOCK_PREFIX)

    /**
     * Check if a memo is an unlock message
     */
    fun isUnlock(memo: String): Boolean = memo.startsWith(UNLOCK_PREFIX)

    // ==========================================
    // PAYMENT REQUESTS
    // ==========================================

    /**
     * Create a payment request message.
     * Format: ZREQ|<amount_zatoshi>|<sender_hash>|<reason>
     *
     * @param amountZatoshi The amount being requested in zatoshi
     * @param senderAddress The address of the person requesting payment
     * @param reason Optional reason/message for the request
     */
    fun createPaymentRequest(amountZatoshi: Long, senderAddress: String, reason: String = ""): String {
        require(amountZatoshi > 0) { "Payment request amount must be positive, got $amountZatoshi" }
        val hash = ZMSGProtocol.generateAddressHash(senderAddress)
        val safeReason = reason.replace("|", "/")
        return "$REQUEST_PREFIX$amountZatoshi|$hash|$safeReason"
    }

    /**
     * Parse a payment request memo.
     * Returns null if not a valid payment request.
     */
    fun parsePaymentRequest(memo: String, addressCache: AddressCache): ParsedPaymentRequest? {
        if (!memo.startsWith(REQUEST_PREFIX)) return null

        val content = memo.removePrefix(REQUEST_PREFIX)
        val parts = content.split("|", limit = 3)

        if (parts.size < 2) return null

        val amountZatoshi = parts[0].toLongOrNull() ?: return null
        if (amountZatoshi <= 0) return null
        val senderHash = parts[1]
        val reason = if (parts.size > 2) parts[2] else ""
        val senderAddress = addressCache.getAddress(senderHash)

        return ParsedPaymentRequest(
            amountZatoshi = amountZatoshi,
            reason = reason,
            senderAddress = senderAddress,
            senderHash = senderHash
        )
    }

    /**
     * Check if a memo is a payment request.
     */
    fun isPaymentRequest(memo: String): Boolean = memo.startsWith(REQUEST_PREFIX)
}
