package co.electriccoin.zcash.ui.screen.chat.model

import java.security.MessageDigest

/**
 * ZMSGv3 Protocol for Zcash Chat Messages
 *
 * Formats:
 * - Initial message: ZMSG|v3|INIT|<full_sender_address>|<message>
 * - Reply message:   ZMSG|v3|<address_hash>|<message>
 * - Legacy v2:       ZMSG|v2|<full_sender_address>|<message>
 *
 * Chunked formats (for messages > 512 bytes):
 * - First chunk INIT: ZMSG|v3c|1/N|INIT|<address>|<message_part>
 * - First chunk reply: ZMSG|v3c|1/N|<hash>|<message_part>
 * - Continuation:      ZMSG|v3c|M/N|CONT|<message_part>
 *
 * The hash is the first 12 characters of SHA256(address) in hex.
 * This saves ~238 bytes compared to full address, allowing ~490 char messages.
 */
object ZMSGProtocol {

    private const val PREFIX_V3 = "ZMSG|v3|"
    private const val PREFIX_V3C = "ZMSG|v3c|"  // v3 chunked
    private const val PREFIX_V2 = "ZMSG|v2|"
    private const val INIT_MARKER = "INIT|"
    private const val CONT_MARKER = "CONT|"
    private const val HASH_LENGTH = 12
    private const val MAX_MEMO_SIZE = 512

    // Available space for message content in each chunk type
    // ZMSG|v3c|1/N|INIT|<address~141>| = ~160 bytes overhead -> ~350 bytes for message
    // ZMSG|v3c|1/N|<hash12>| = ~30 bytes overhead -> ~480 bytes for message
    // ZMSG|v3c|M/N|CONT| = ~20 bytes overhead -> ~490 bytes for message
    private const val CHUNK_SIZE_INIT = 340
    private const val CHUNK_SIZE_REPLY_FIRST = 470
    private const val CHUNK_SIZE_CONTINUATION = 485

    /**
     * Generate a short hash from a Zcash address
     */
    fun generateAddressHash(address: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(address.toByteArray())
        return hashBytes.take(6).joinToString("") { "%02x".format(it) }
    }

    /**
     * Create an INIT message (first message to a new contact)
     * Format: ZMSG|v3|INIT|<full_address>|<message>
     */
    fun createInitMessage(senderAddress: String, message: String): String {
        return "$PREFIX_V3$INIT_MARKER$senderAddress|$message"
    }

    /**
     * Create a reply message (subsequent messages)
     * Format: ZMSG|v3|<hash>|<message>
     */
    fun createReplyMessage(senderAddress: String, message: String): String {
        val hash = generateAddressHash(senderAddress)
        return "$PREFIX_V3$hash|$message"
    }

    /**
     * Create a legacy v2 message (for compatibility)
     * Format: ZMSG|v2|<full_address>|<message>
     */
    fun createLegacyMessage(senderAddress: String, message: String): String {
        return "$PREFIX_V2$senderAddress|$message"
    }

    /**
     * Parse a ZMSG memo and extract sender info and message
     */
    fun parseMemo(memo: String, addressCache: AddressCache): ParsedMessage {
        return when {
            // ZMSGv3 INIT message
            memo.startsWith("$PREFIX_V3$INIT_MARKER") -> {
                parseV3InitMessage(memo, addressCache)
            }
            // ZMSGv3 RPL (reply) message - MUST check before generic v3 hash format!
            memo.startsWith("$PREFIX_V3$REPLY_MARKER") -> {
                parseReplyMemo(memo, addressCache) ?: ParsedMessage(
                    senderAddress = null,
                    senderHash = null,
                    message = memo,
                    isUnknownSender = true,
                    reason = UnknownReason.MALFORMED_MESSAGE
                )
            }
            // ZMSGv3 hash-based message (non-reply)
            memo.startsWith(PREFIX_V3) -> {
                parseV3ReplyMessage(memo, addressCache)
            }
            // Legacy ZMSGv2 message
            memo.startsWith(PREFIX_V2) -> {
                parseV2Message(memo, addressCache)
            }
            // Plain text (not ZMSG format)
            else -> {
                ParsedMessage(
                    senderAddress = null,
                    senderHash = null,
                    message = memo,
                    isUnknownSender = true,
                    reason = UnknownReason.NOT_ZMSG_FORMAT
                )
            }
        }
    }

    private fun parseV3InitMessage(memo: String, addressCache: AddressCache): ParsedMessage {
        // Format: ZMSG|v3|INIT|<address>|<message>
        val content = memo.removePrefix("$PREFIX_V3$INIT_MARKER")
        val separatorIndex = content.indexOf('|')

        if (separatorIndex == -1) {
            return ParsedMessage(
                senderAddress = null,
                senderHash = null,
                message = memo,
                isUnknownSender = true,
                reason = UnknownReason.MALFORMED_MESSAGE
            )
        }

        val address = content.substring(0, separatorIndex)
        val message = content.substring(separatorIndex + 1)
        val hash = generateAddressHash(address)

        // Cache the address for future lookups
        addressCache.cacheAddress(hash, address)

        return ParsedMessage(
            senderAddress = address,
            senderHash = hash,
            message = message,
            isUnknownSender = false,
            reason = null
        )
    }

    private fun parseV3ReplyMessage(memo: String, addressCache: AddressCache): ParsedMessage {
        // Format: ZMSG|v3|<hash>|<message>
        val content = memo.removePrefix(PREFIX_V3)
        val separatorIndex = content.indexOf('|')

        if (separatorIndex == -1) {
            return ParsedMessage(
                senderAddress = null,
                senderHash = null,
                message = memo,
                isUnknownSender = true,
                reason = UnknownReason.MALFORMED_MESSAGE
            )
        }

        val hash = content.substring(0, separatorIndex)
        val message = content.substring(separatorIndex + 1)

        // Look up address from cache
        val address = addressCache.getAddress(hash)

        return if (address != null) {
            ParsedMessage(
                senderAddress = address,
                senderHash = hash,
                message = message,
                isUnknownSender = false,
                reason = null
            )
        } else {
            ParsedMessage(
                senderAddress = null,
                senderHash = hash,
                message = message,
                isUnknownSender = true,
                reason = UnknownReason.HASH_NOT_IN_CACHE
            )
        }
    }

    private fun parseV2Message(memo: String, addressCache: AddressCache): ParsedMessage {
        // Format: ZMSG|v2|<address>|<message>
        val content = memo.removePrefix(PREFIX_V2)
        val separatorIndex = content.indexOf('|')

        if (separatorIndex == -1) {
            return ParsedMessage(
                senderAddress = null,
                senderHash = null,
                message = memo,
                isUnknownSender = true,
                reason = UnknownReason.MALFORMED_MESSAGE
            )
        }

        val address = content.substring(0, separatorIndex)
        val message = content.substring(separatorIndex + 1)
        val hash = generateAddressHash(address)

        // Cache the address for future lookups
        addressCache.cacheAddress(hash, address)

        return ParsedMessage(
            senderAddress = address,
            senderHash = hash,
            message = message,
            isUnknownSender = false,
            reason = null
        )
    }

    /**
     * Calculate available message space for a single memo
     */
    fun getAvailableMessageLength(isInitMessage: Boolean, senderAddress: String): Int {
        return if (isInitMessage) {
            // ZMSG|v3|INIT|<address>|
            val overhead = PREFIX_V3.length + INIT_MARKER.length + senderAddress.length + 1
            MAX_MEMO_SIZE - overhead
        } else {
            // ZMSG|v3|<hash>|
            val overhead = PREFIX_V3.length + HASH_LENGTH + 1
            MAX_MEMO_SIZE - overhead
        }
    }

    /**
     * Check if a message needs to be chunked (split across multiple outputs)
     */
    fun needsChunking(message: String, isInitMessage: Boolean, senderAddress: String): Boolean {
        val availableLength = getAvailableMessageLength(isInitMessage, senderAddress)
        return message.length > availableLength
    }

    /**
     * Calculate the number of chunks needed for a message
     */
    fun calculateChunkCount(message: String, isInitMessage: Boolean): Int {
        val firstChunkSize = if (isInitMessage) CHUNK_SIZE_INIT else CHUNK_SIZE_REPLY_FIRST

        if (message.length <= firstChunkSize) return 1

        var remaining = message.length - firstChunkSize
        var chunks = 1

        while (remaining > 0) {
            chunks++
            remaining -= CHUNK_SIZE_CONTINUATION
        }

        return chunks
    }

    /**
     * Create chunked INIT messages (first message to a new contact)
     * Returns list of memo strings, one per output
     */
    fun createChunkedInitMessages(senderAddress: String, message: String): List<String> {
        val totalChunks = calculateChunkCount(message, true)

        if (totalChunks == 1) {
            return listOf(createInitMessage(senderAddress, message))
        }

        val chunks = mutableListOf<String>()
        var position = 0

        for (i in 1..totalChunks) {
            val chunkSize = if (i == 1) CHUNK_SIZE_INIT else CHUNK_SIZE_CONTINUATION
            val endPosition = minOf(position + chunkSize, message.length)
            val messagePart = message.substring(position, endPosition)
            position = endPosition

            val memo = if (i == 1) {
                // First chunk: include sender address
                "${PREFIX_V3C}$i/$totalChunks|$INIT_MARKER$senderAddress|$messagePart"
            } else {
                // Continuation chunks
                "${PREFIX_V3C}$i/$totalChunks|$CONT_MARKER$messagePart"
            }

            chunks.add(memo)
        }

        return chunks
    }

    /**
     * Create chunked reply messages (subsequent messages)
     * Returns list of memo strings, one per output
     */
    fun createChunkedReplyMessages(senderAddress: String, message: String): List<String> {
        val totalChunks = calculateChunkCount(message, false)

        if (totalChunks == 1) {
            return listOf(createReplyMessage(senderAddress, message))
        }

        val hash = generateAddressHash(senderAddress)
        val chunks = mutableListOf<String>()
        var position = 0

        for (i in 1..totalChunks) {
            val chunkSize = if (i == 1) CHUNK_SIZE_REPLY_FIRST else CHUNK_SIZE_CONTINUATION
            val endPosition = minOf(position + chunkSize, message.length)
            val messagePart = message.substring(position, endPosition)
            position = endPosition

            val memo = if (i == 1) {
                // First chunk: include hash
                "${PREFIX_V3C}$i/$totalChunks|$hash|$messagePart"
            } else {
                // Continuation chunks
                "${PREFIX_V3C}$i/$totalChunks|$CONT_MARKER$messagePart"
            }

            chunks.add(memo)
        }

        return chunks
    }

    /**
     * Reassemble chunked memos from a single transaction into a complete message.
     * Call this with all memos from the same transaction.
     * Returns null if chunks are incomplete or invalid.
     */
    fun reassembleChunks(memos: List<String>, addressCache: AddressCache): ParsedMessage? {
        if (memos.isEmpty()) return null

        // Filter only chunked messages
        val chunkedMemos = memos.filter { it.startsWith(PREFIX_V3C) }

        if (chunkedMemos.isEmpty()) {
            // Not chunked, parse as single message
            return if (memos.size == 1) parseMemo(memos[0], addressCache) else null
        }

        // Parse chunk info and sort
        val chunks = chunkedMemos.mapNotNull { memo ->
            parseChunkInfo(memo)
        }.sortedBy { it.index }

        if (chunks.isEmpty()) return null

        // Verify we have all chunks
        val totalChunks = chunks.first().total
        if (chunks.size != totalChunks) return null
        if (chunks.map { it.index }.toSet() != (1..totalChunks).toSet()) return null

        // Get sender info from first chunk
        val firstChunk = chunks.first()
        val senderAddress: String?
        val senderHash: String?

        when {
            firstChunk.isInit -> {
                senderAddress = firstChunk.senderInfo
                senderHash = senderAddress?.let { generateAddressHash(it) }
                // Cache the address
                if (senderAddress != null && senderHash != null) {
                    addressCache.cacheAddress(senderHash, senderAddress)
                }
            }
            firstChunk.senderInfo != null -> {
                senderHash = firstChunk.senderInfo
                senderAddress = addressCache.getAddress(senderHash)
            }
            else -> {
                senderAddress = null
                senderHash = null
            }
        }

        // Concatenate all message parts
        val fullMessage = chunks.joinToString("") { it.messagePart }

        return ParsedMessage(
            senderAddress = senderAddress,
            senderHash = senderHash,
            message = fullMessage,
            isUnknownSender = senderAddress == null,
            reason = if (senderAddress == null) {
                if (firstChunk.isInit) UnknownReason.MALFORMED_MESSAGE
                else UnknownReason.HASH_NOT_IN_CACHE
            } else null
        )
    }

    /**
     * Parse chunk information from a chunked memo
     */
    private fun parseChunkInfo(memo: String): ChunkInfo? {
        if (!memo.startsWith(PREFIX_V3C)) return null

        val content = memo.removePrefix(PREFIX_V3C)

        // Parse chunk number: "1/3|..."
        val chunkEndIndex = content.indexOf('|')
        if (chunkEndIndex == -1) return null

        val chunkPart = content.substring(0, chunkEndIndex)
        val slashIndex = chunkPart.indexOf('/')
        if (slashIndex == -1) return null

        val chunkIndex = chunkPart.substring(0, slashIndex).toIntOrNull() ?: return null
        val totalChunks = chunkPart.substring(slashIndex + 1).toIntOrNull() ?: return null

        val remaining = content.substring(chunkEndIndex + 1)

        return when {
            // First chunk with INIT: "INIT|<address>|<message>"
            remaining.startsWith(INIT_MARKER) -> {
                val afterInit = remaining.removePrefix(INIT_MARKER)
                val sepIndex = afterInit.indexOf('|')
                if (sepIndex == -1) return null
                ChunkInfo(
                    index = chunkIndex,
                    total = totalChunks,
                    isInit = true,
                    senderInfo = afterInit.substring(0, sepIndex),
                    messagePart = afterInit.substring(sepIndex + 1)
                )
            }
            // Continuation chunk: "CONT|<message>"
            remaining.startsWith(CONT_MARKER) -> {
                ChunkInfo(
                    index = chunkIndex,
                    total = totalChunks,
                    isInit = false,
                    senderInfo = null,
                    messagePart = remaining.removePrefix(CONT_MARKER)
                )
            }
            // First chunk with hash: "<hash>|<message>"
            else -> {
                val sepIndex = remaining.indexOf('|')
                if (sepIndex == -1) return null
                ChunkInfo(
                    index = chunkIndex,
                    total = totalChunks,
                    isInit = false,
                    senderInfo = remaining.substring(0, sepIndex),
                    messagePart = remaining.substring(sepIndex + 1)
                )
            }
        }
    }

    /**
     * Check if a memo is part of a chunked message
     */
    fun isChunkedMemo(memo: String): Boolean = memo.startsWith(PREFIX_V3C)

    /**
     * Get the maximum message length when using chunking (practically unlimited with multi-output)
     * Returns the approximate max based on reasonable limits (e.g., 10 chunks)
     */
    fun getMaxChunkedMessageLength(isInitMessage: Boolean, maxChunks: Int = 10): Int {
        val firstChunkSize = if (isInitMessage) CHUNK_SIZE_INIT else CHUNK_SIZE_REPLY_FIRST
        return firstChunkSize + (maxChunks - 1) * CHUNK_SIZE_CONTINUATION
    }

    // ==========================================
    // REACTIONS, READ RECEIPTS, AND REPLIES
    // ==========================================

    private const val REACTION_PREFIX = "ZREACT|"
    private const val RECEIPT_PREFIX = "ZRCPT|"
    private const val REPLY_MARKER = "RPL|"

    /**
     * Create a reaction message
     * Format: ZREACT|<target_txid>|<emoji>|<sender_hash>
     */
    fun createReaction(targetTxId: String, emoji: String, senderAddress: String): String {
        val hash = generateAddressHash(senderAddress)
        return "$REACTION_PREFIX$targetTxId|$emoji|$hash"
    }

    /**
     * Create a read receipt message
     * Format: ZRCPT|<target_txid>|<sender_hash>
     */
    fun createReadReceipt(targetTxId: String, senderAddress: String): String {
        val hash = generateAddressHash(senderAddress)
        return "$RECEIPT_PREFIX$targetTxId|$hash"
    }

    /**
     * Create a reply message (references a specific message)
     * Format: ZMSG|v3|RPL|<quoted_txid>|INIT|<address>|<message>
     * Or:     ZMSG|v3|RPL|<quoted_txid>|<hash>|<message>
     */
    fun createReplyInitMessage(
        senderAddress: String,
        message: String,
        replyToTxId: String
    ): String {
        return "$PREFIX_V3$REPLY_MARKER$replyToTxId|$INIT_MARKER$senderAddress|$message"
    }

    fun createReplyMessage(
        senderAddress: String,
        message: String,
        replyToTxId: String
    ): String {
        val hash = generateAddressHash(senderAddress)
        return "$PREFIX_V3$REPLY_MARKER$replyToTxId|$hash|$message"
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
     * Check if a memo is a reaction
     */
    fun isReaction(memo: String): Boolean = memo.startsWith(REACTION_PREFIX)

    /**
     * Check if a memo is a read receipt
     */
    fun isReadReceipt(memo: String): Boolean = memo.startsWith(RECEIPT_PREFIX)

    // ==========================================
    // USER STATUS
    // ==========================================

    private const val STATUS_PREFIX = "ZSTAT|"

    /**
     * Create a user status message
     * Format: ZSTAT|<status_text>|<sender_hash>
     * Status text is limited to 100 characters
     */
    fun createStatusMessage(statusText: String, senderAddress: String): String {
        val hash = generateAddressHash(senderAddress)
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

    private const val TIMELOCK_PREFIX = "ZTL|"

    /**
     * Time-lock types:
     * - SCH: Scheduled - unlocks at a specific timestamp
     * - BLK: Block-height locked - unlocks at a specific block height
     * - PAY: Payment-to-reveal - requires payment to unlock
     * - CND: Conditional - requires correct answer to unlock
     */

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
        val hash = generateAddressHash(senderAddress)
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
        val hash = generateAddressHash(senderAddress)
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
        val hash = generateAddressHash(senderAddress)
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
        val senderHash = generateAddressHash(senderAddress)
        // Hash the answer so it's not stored in plaintext
        val answerHash = generateAddressHash(answer.lowercase().trim())
        // Replace pipes in hint with dashes to avoid parsing issues
        val safeHint = hint.replace("|", "-")
        return "$TIMELOCK_PREFIX$LOCK_TYPE_CONDITIONAL|$answerHash|$safeHint|$senderHash|$message"
    }

    /**
     * Create an unlock payment memo (sent to unlock a PAY message)
     * Format: ZUNLOCK|PAY|<original_txid>|<sender_hash>
     */
    fun createUnlockPayment(originalTxId: String, senderAddress: String): String {
        val hash = generateAddressHash(senderAddress)
        return "$UNLOCK_PREFIX$LOCK_TYPE_PAYMENT|$originalTxId|$hash"
    }

    /**
     * Create an unlock answer memo (sent to unlock a CND message)
     * Format: ZUNLOCK|CND|<original_txid>|<answer>|<sender_hash>
     */
    fun createUnlockAnswer(originalTxId: String, answer: String, senderAddress: String): String {
        val hash = generateAddressHash(senderAddress)
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
        val computedHash = generateAddressHash(answer.lowercase().trim())
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

    // Time-lock type constants
    private const val LOCK_TYPE_SCHEDULED = "SCH"
    private const val LOCK_TYPE_BLOCK = "BLK"
    private const val LOCK_TYPE_PAYMENT = "PAY"
    private const val LOCK_TYPE_CONDITIONAL = "CND"
    private const val UNLOCK_PREFIX = "ZUNLOCK|"

    // ==========================================
    // PAYMENT REQUESTS
    // ==========================================

    private const val REQUEST_PREFIX = "ZREQ|"

    /**
     * Create a payment request message.
     * Format: ZREQ|<amount_zatoshi>|<sender_hash>|<reason>
     *
     * @param amountZatoshi The amount being requested in zatoshi
     * @param senderAddress The address of the person requesting payment
     * @param reason Optional reason/message for the request
     */
    fun createPaymentRequest(amountZatoshi: Long, senderAddress: String, reason: String = ""): String {
        val hash = generateAddressHash(senderAddress)
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

    /**
     * Check if a memo is a reply to another message
     */
    fun isReply(memo: String): Boolean = memo.startsWith("$PREFIX_V3$REPLY_MARKER")

    /**
     * Parse a reply memo and extract the quoted txid and message
     */
    fun parseReplyMemo(memo: String, addressCache: AddressCache): ParsedMessage? {
        if (!isReply(memo)) return null

        // Format: ZMSG|v3|RPL|<txid>|INIT|<address>|<message>
        // Or:     ZMSG|v3|RPL|<txid>|<hash>|<message>
        val content = memo.removePrefix("$PREFIX_V3$REPLY_MARKER")
        val firstPipe = content.indexOf('|')
        if (firstPipe == -1) return null

        val replyToTxId = content.substring(0, firstPipe)
        val remaining = content.substring(firstPipe + 1)

        // Check if it's an INIT reply or hash reply
        return if (remaining.startsWith(INIT_MARKER)) {
            // INIT|<address>|<message>
            val afterInit = remaining.removePrefix(INIT_MARKER)
            val sepIndex = afterInit.indexOf('|')
            if (sepIndex == -1) return null

            val address = afterInit.substring(0, sepIndex)
            val message = afterInit.substring(sepIndex + 1)
            val hash = generateAddressHash(address)

            addressCache.cacheAddress(hash, address)

            ParsedMessage(
                senderAddress = address,
                senderHash = hash,
                message = message,
                isUnknownSender = false,
                reason = null,
                replyToTxId = replyToTxId,
                messageType = MessageType.REPLY
            )
        } else {
            // <hash>|<message>
            val sepIndex = remaining.indexOf('|')
            if (sepIndex == -1) return null

            val hash = remaining.substring(0, sepIndex)
            val message = remaining.substring(sepIndex + 1)
            val address = addressCache.getAddress(hash)

            ParsedMessage(
                senderAddress = address,
                senderHash = hash,
                message = message,
                isUnknownSender = address == null,
                reason = if (address == null) UnknownReason.HASH_NOT_IN_CACHE else null,
                replyToTxId = replyToTxId,
                messageType = MessageType.REPLY
            )
        }
    }
}

/**
 * Internal data class for chunk parsing
 */
private data class ChunkInfo(
    val index: Int,
    val total: Int,
    val isInit: Boolean,
    val senderInfo: String?,  // address for INIT, hash for reply
    val messagePart: String
)

/**
 * Parsed message result
 */
data class ParsedMessage(
    val senderAddress: String?,
    val senderHash: String?,
    val message: String,
    val isUnknownSender: Boolean,
    val reason: UnknownReason?,
    val replyToTxId: String? = null,  // Transaction ID being replied to
    val messageType: MessageType = MessageType.REGULAR
)

/**
 * Types of messages in the protocol
 */
enum class MessageType {
    REGULAR,         // Normal chat message
    REACTION,        // Emoji reaction to a message
    READ_RECEIPT,    // Read confirmation
    REPLY,           // Reply to specific message
    STATUS,          // User status update
    TIME_LOCK,       // Time-locked message
    UNLOCK,          // Unlock message for time-locked content
    PAYMENT_REQUEST  // Request for payment
}

/**
 * Parsed reaction data
 */
data class ParsedReaction(
    val targetTxId: String,
    val emoji: String,
    val senderAddress: String?,
    val senderHash: String?
)

/**
 * Parsed read receipt data
 */
data class ParsedReadReceipt(
    val targetTxId: String,
    val senderAddress: String?,
    val senderHash: String?
)

/**
 * Parsed payment request data
 */
data class ParsedPaymentRequest(
    val amountZatoshi: Long,
    val reason: String,
    val senderAddress: String?,
    val senderHash: String?
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
}

/**
 * Parsed user status data
 */
data class ParsedStatus(
    val statusText: String,
    val senderAddress: String?,
    val senderHash: String?
)

/**
 * Types of time-locked messages
 */
enum class TimeLockType {
    SCHEDULED,      // Unlocks at a specific timestamp
    BLOCK_HEIGHT,   // Unlocks at a specific block height
    PAYMENT,        // Unlocks when payment is received
    CONDITIONAL     // Unlocks when correct answer is provided
}

/**
 * Parsed time-locked message data
 */
data class ParsedTimeLock(
    val lockType: TimeLockType,
    val message: String,
    val senderAddress: String?,
    val senderHash: String?,
    val unlockTimestamp: Long?,      // For SCHEDULED
    val unlockBlockHeight: Long?,    // For BLOCK_HEIGHT
    val requiredPayment: Long?,      // For PAYMENT (in zatoshi)
    val hint: String?,               // For CONDITIONAL
    val answerHash: String?          // For CONDITIONAL
) {
    /**
     * Check if this time-lock is currently unlocked
     */
    fun isUnlocked(currentTimestamp: Long, currentBlockHeight: Long?, isPaymentReceived: Boolean = false, answerProvided: Boolean = false): Boolean {
        return when (lockType) {
            TimeLockType.SCHEDULED -> unlockTimestamp != null && currentTimestamp >= unlockTimestamp
            TimeLockType.BLOCK_HEIGHT -> currentBlockHeight != null && unlockBlockHeight != null && currentBlockHeight >= unlockBlockHeight
            TimeLockType.PAYMENT -> isPaymentReceived
            TimeLockType.CONDITIONAL -> answerProvided
        }
    }

    /**
     * Get human-readable lock status description
     */
    fun getLockDescription(currentBlockHeight: Long?): String {
        return when (lockType) {
            TimeLockType.SCHEDULED -> {
                unlockTimestamp?.let {
                    val remaining = it - (System.currentTimeMillis() / 1000)
                    if (remaining <= 0) "Unlocked"
                    else formatDuration(remaining)
                } ?: "Unknown"
            }
            TimeLockType.BLOCK_HEIGHT -> {
                if (currentBlockHeight != null && unlockBlockHeight != null) {
                    val remaining = unlockBlockHeight - currentBlockHeight
                    if (remaining <= 0) "Unlocked"
                    else "$remaining blocks (~${formatDuration(remaining * 75)})"
                } else {
                    "Block #${unlockBlockHeight ?: "?"}"
                }
            }
            TimeLockType.PAYMENT -> {
                requiredPayment?.let {
                    val zec = it / 100_000_000.0
                    "Pay ${String.format("%.8f", zec)} ZEC to unlock"
                } ?: "Payment required"
            }
            TimeLockType.CONDITIONAL -> {
                hint?.let { "Hint: $it" } ?: "Answer required"
            }
        }
    }

    private fun formatDuration(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
        }
    }
}

/**
 * Parsed unlock message data
 */
data class ParsedUnlock(
    val unlockType: TimeLockType,
    val originalTxId: String,
    val senderAddress: String?,
    val senderHash: String?,
    val answer: String?              // For CONDITIONAL unlocks
)

/**
 * Reasons why a sender might be unknown
 */
enum class UnknownReason {
    NOT_ZMSG_FORMAT,      // Message wasn't sent using ZMSG protocol
    MALFORMED_MESSAGE,    // ZMSG format but malformed
    HASH_NOT_IN_CACHE     // Hash-based message but address not in cache
}

/**
 * Interface for address cache
 */
interface AddressCache {
    fun cacheAddress(hash: String, address: String)
    fun getAddress(hash: String): String?
    fun hasAddress(hash: String): Boolean
    fun getAllCachedAddresses(): Map<String, String>
}
