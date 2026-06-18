package co.electriccoin.zcash.ui.screen.chat.model

import android.util.Log
import java.security.MessageDigest

/**
 * ZMSG Multi-Version Protocol for Zcash Chat Messages
 *
 * Supports protocol versions v2 (legacy), v3 (hash-based), and v4 (conversation ID based).
 *
 * V4 FORMATS (PRIMARY - conversation ID based):
 * - INIT:  ZMSG|v4|<convID>|INIT|<address>|<message>
 * - Reply: ZMSG|v4|<convID>|<hash16>|<message>
 * - KEX:   ZMSG|v4|<convID>|KEX|<hash16>|<kex_payload>
 * - ADDR:  ZMSG|v4|<convID>|ADDR|<hash16>|<new_address>|<signature>
 *
 * V4 Chunked:
 * - First chunk INIT:  ZMSG|v4c|1/N|<convID>|INIT|<address>|<message_part>
 * - First chunk reply: ZMSG|v4c|1/N|<convID>|<hash16>|<message_part>
 * - Continuation:      ZMSG|v4c|M/N|CONT|<message_part>
 *
 * V3 FORMATS (LEGACY - hash-based, for backward compatibility):
 * - INIT:  ZMSG|v3|INIT|<address>|<message>
 * - Reply: ZMSG|v3|<hash12>|<message>
 *
 * V2 FORMAT (LEGACY - full address):
 * - ZMSG|v2|<address>|<message>
 *
 * Hash formats:
 * - hash16: First 8 bytes of SHA256(address) as hex (16 chars) - used in v4
 * - hash12: First 6 bytes of SHA256(address) as hex (12 chars) - legacy v3 compat
 */
object ZMSGProtocol {

    // ==========================================
    // CONSTANTS (from ZMSGConstants)
    // ==========================================

    private const val PREFIX_V4 = ZMSGConstants.Prefixes.V4
    private const val PREFIX_V4C = ZMSGConstants.Prefixes.V4C
    private const val PREFIX_V3 = ZMSGConstants.Prefixes.V3
    private const val PREFIX_V3C = ZMSGConstants.Prefixes.V3C
    private const val PREFIX_V2 = ZMSGConstants.Prefixes.V2
    private const val INIT_MARKER = ZMSGConstants.Markers.INIT
    private const val CONT_MARKER = ZMSGConstants.Markers.CONT
    private const val REF_MARKER = ZMSGConstants.Markers.REF
    private const val CONV_ID_LENGTH = ZMSGConstants.CONV_ID_LENGTH
    private const val HASH_LENGTH = ZMSGConstants.HASH_LENGTH
    private const val HASH_LENGTH_NEW = ZMSGConstants.HASH_LENGTH_NEW
    private const val MAX_MEMO_SIZE = ZMSGConstants.MAX_MEMO_SIZE
    private const val CONV_ID_CHARS = ZMSGConstants.CONV_ID_CHARS

    // v3 chunk sizes
    private const val CHUNK_SIZE_INIT = ZMSGConstants.ChunkSizes.V3_INIT
    private const val CHUNK_SIZE_REPLY_FIRST = ZMSGConstants.ChunkSizes.V3_REPLY_FIRST
    private const val CHUNK_SIZE_CONTINUATION = ZMSGConstants.ChunkSizes.CONTINUATION

    // v4 chunk sizes
    private const val CHUNK_SIZE_V4_INIT = ZMSGConstants.ChunkSizes.V4_INIT
    private const val CHUNK_SIZE_V4_REPLY_FIRST = ZMSGConstants.ChunkSizes.V4_REPLY_FIRST

    // Maximum chunk count to prevent DoS attacks via memory exhaustion
    // 1000 chunks × ~480 bytes = ~480KB max message (already very large for chat)
    private const val MAX_CHUNKS = 1000

    /**
     * Returns a substring of [str] starting at char index [startIndex] that fits within
     * [maxBytes] when encoded as UTF-8. Avoids splitting multi-byte characters.
     */
    private fun substringByBytes(str: String, startIndex: Int, maxBytes: Int): String {
        var byteCount = 0
        var endIndex = startIndex
        while (endIndex < str.length) {
            val codePoint = Character.codePointAt(str, endIndex)
            // Measure the WHOLE code point's UTF-8 size (1–4 bytes) and advance by whole code points,
            // so a surrogate pair (emoji / non-BMP char) is never split across a chunk boundary.
            // Splitting one emits lone surrogates that UTF-8-encode to '?' → permanent corruption
            // ("😀" stored on-chain as "??"); counting a lone surrogate as 1 byte also overflowed
            // the 512-byte memo limit on emoji-heavy chunks. Code-point iteration fixes both.
            val cpBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (byteCount + cpBytes > maxBytes) break
            byteCount += cpBytes
            endIndex += Character.charCount(codePoint)
        }
        return str.substring(startIndex, endIndex)
    }

    /**
     * Pack [message] into chunk-sized parts using the SAME code-point-aware budgeting that the
     * wire format uses, so the part COUNT always equals what actually gets packed. The first part
     * gets [firstChunkSize] UTF-8 bytes; every later part gets [CHUNK_SIZE_CONTINUATION]. An empty
     * message yields a single empty part (mirrors the legacy "1 chunk" contract).
     *
     * Why this exists: the old code counted chunks arithmetically (byteLen / chunkSize) but packed
     * them separately via [substringByBytes]. Once [substringByBytes] became code-point-aware, a
     * 4-byte emoji that straddles a budget boundary forces an early break, wasting 1–3 bytes in that
     * chunk. Enough wasted bytes and the real packing needs MORE chunks than the arithmetic predicted —
     * and the builder loop, bounded by the arithmetic count, would silently drop the message tail.
     * Driving both the count and the builder off this one function makes that desync impossible.
     */
    private fun packChunks(message: String, firstChunkSize: Int): List<String> {
        if (message.isEmpty()) return listOf("")
        val parts = mutableListOf<String>()
        var pos = 0
        while (pos < message.length) {
            val budget = if (parts.isEmpty()) firstChunkSize else CHUNK_SIZE_CONTINUATION
            var part = substringByBytes(message, pos, budget)
            if (part.isEmpty()) {
                // Budget too small for the next whole code point — force one through so we never spin.
                part = String(Character.toChars(Character.codePointAt(message, pos)))
            }
            parts.add(part)
            pos += part.length
        }
        return parts
    }

    /**
     * Returns the byte length of a string when encoded as UTF-8.
     */
    private fun byteLen(str: String): Int = str.toByteArray(Charsets.UTF_8).size

    /**
     * Validate that a conversation ID is properly formatted.
     * @throws IllegalArgumentException if convId is invalid
     */
    private fun validateConvId(convId: String) {
        require(convId.length == CONV_ID_LENGTH) {
            "Invalid convId length: ${convId.length}, expected $CONV_ID_LENGTH"
        }
        require(convId.all { it in CONV_ID_CHARS }) {
            "Invalid convId characters: convId must contain only $CONV_ID_CHARS"
        }
    }

    /**
     * Generate a hash from a Zcash address for sender identification.
     * Uses 8 bytes (16 hex chars) for strong collision resistance.
     *
     * IMPORTANT: Hash length was increased from 6 to 8 bytes to reduce
     * collision probability from 1:16M to 1:18 quintillion.
     */
    fun generateAddressHash(address: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(address.toByteArray())
        // Use 8 bytes (16 hex chars) for strong collision resistance
        return hashBytes.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Check if a hash matches the legacy 6-byte format (12 hex chars).
     * Used for backward compatibility with older messages.
     */
    fun isLegacyHash(hash: String): Boolean {
        return hash.length == 12 && hash.all { it.isDigit() || it in 'a'..'f' }
    }

    /**
     * Generate legacy 6-byte hash for backward compatibility.
     * Only used when receiving messages with old hash format.
     */
    fun generateLegacyAddressHash(address: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(address.toByteArray())
        return hashBytes.take(6).joinToString("") { "%02x".format(it) }
    }

    // ==========================================
    // ZMSG v4 PROTOCOL - Conversation ID Based
    // ==========================================

    /**
     * Generate a unique conversation ID (8 alphanumeric characters).
     * This ID is used to reliably thread messages in a conversation.
     */
    fun generateConversationId(): String {
        val random = java.security.SecureRandom()
        return (1..CONV_ID_LENGTH)
            .map { CONV_ID_CHARS[random.nextInt(CONV_ID_CHARS.length)] }
            .joinToString("")
    }

    /**
     * Create a v4 INIT message (first message to a new contact)
     * Format: ZMSG|v4|<convID>|INIT|<full_address>|<message>
     * @throws IllegalArgumentException if convId is invalid
     */
    fun createV4InitMessage(convId: String, senderAddress: String, message: String): String {
        validateConvId(convId)
        return "$PREFIX_V4$convId|$INIT_MARKER$senderAddress|$message"
    }

    /**
     * Create a v4 reply message (subsequent messages in conversation)
     * Format: ZMSG|v4|<convID>|<hash>|<message>
     *
     * Includes sender hash for fallback identification if convID lookup fails.
     * This adds 13 bytes of overhead but provides reliable message threading.
     * @throws IllegalArgumentException if convId is invalid
     */
    fun createV4ReplyMessage(convId: String, senderAddress: String, message: String): String {
        validateConvId(convId)
        val hash = generateAddressHash(senderAddress)
        return "$PREFIX_V4$convId|$hash|$message"
    }

    /**
     * Lightweight parse of a SINGLE (non-chunked) v4 INIT memo, returning (senderAddress, message) or
     * null if it isn't a well-formed INIT. Unlike [parseMemo] this needs no [AddressCache] and does not
     * touch any caches — used by the NOSTR receive path (#224) to extract the claimed sender address +
     * first-message text from an unknown-pubkey INIT before deciding to surface it as a contact request.
     * The returned address is UNVERIFIED (attacker-controlled plaintext); callers must gate trust.
     */
    fun parseV4Init(memo: String): Pair<String, String>? {
        if (!memo.startsWith(PREFIX_V4)) return null
        val content = memo.removePrefix(PREFIX_V4)
        val firstPipe = content.indexOf('|')
        if (firstPipe != CONV_ID_LENGTH) return null
        val convId = content.substring(0, firstPipe)
        if (!convId.all { it in CONV_ID_CHARS }) return null
        val remaining = content.substring(firstPipe + 1)
        if (!remaining.startsWith(INIT_MARKER)) return null
        val afterInit = remaining.removePrefix(INIT_MARKER)
        val sep = afterInit.indexOf('|')
        if (sep == -1) return null
        val address = afterInit.substring(0, sep)
        val message = afterInit.substring(sep + 1)
        if (address.isBlank()) return null
        return address to message
    }

    // ==========================================
    // KEX (Key Exchange) MESSAGES
    // ==========================================

    private const val KEX_MARKER = ZMSGConstants.Markers.KEX
    private const val KEXACK_MARKER = ZMSGConstants.Markers.KEX_ACK
    private const val ADDR_MARKER = ZMSGConstants.Markers.ADDR

    /**
     * Create a v4 KEX (Key Exchange) message.
     * Format: ZMSG|v4|<convID>|KEX|<sender_hash>|<kex_payload>
     *
     * The KEX payload includes the public key and signature, created by E2EEncryption.createKEXPayload.
     *
     * @param convId Conversation ID
     * @param senderAddress Sender's full Zcash address
     * @param kexPayload The KEX payload from E2EEncryption.createKEXPayload
     * @return Complete ZMSG KEX message
     * @throws IllegalArgumentException if convId is invalid
     */
    fun createV4KEXMessage(convId: String, senderAddress: String, kexPayload: String): String {
        validateConvId(convId)
        val hash = generateAddressHash(senderAddress)
        return "$PREFIX_V4$convId|$KEX_MARKER$hash|$kexPayload"
    }

    /**
     * Create a v4 KEX acknowledgment message.
     * Format: ZMSG|v4|<convID>|KEXACK|<sender_hash>|<kexack_payload>
     *
     * Sent in response to receiving a valid KEX message.
     * @throws IllegalArgumentException if convId is invalid
     */
    fun createV4KEXAckMessage(convId: String, senderAddress: String, kexAckPayload: String): String {
        validateConvId(convId)
        val hash = generateAddressHash(senderAddress)
        return "$PREFIX_V4$convId|$KEXACK_MARKER$hash|$kexAckPayload"
    }

    /**
     * Check if a message is a KEX message.
     */
    fun isKEXMessage(memo: String): Boolean {
        if (!memo.startsWith(PREFIX_V4)) return false
        // Match: ZMSG|v4|<8-char-convId>|KEX|...
        val afterPrefix = memo.removePrefix(PREFIX_V4)
        return afterPrefix.length > CONV_ID_LENGTH + 1 &&
            afterPrefix[CONV_ID_LENGTH] == '|' &&
            afterPrefix.substring(CONV_ID_LENGTH + 1).startsWith(KEX_MARKER)
    }

    /**
     * Check if a message is a KEX acknowledgment.
     */
    fun isKEXAckMessage(memo: String): Boolean {
        if (!memo.startsWith(PREFIX_V4)) return false
        // Match: ZMSG|v4|<8-char-convId>|KEXACK|...
        val afterPrefix = memo.removePrefix(PREFIX_V4)
        return afterPrefix.length > CONV_ID_LENGTH + 1 &&
            afterPrefix[CONV_ID_LENGTH] == '|' &&
            afterPrefix.substring(CONV_ID_LENGTH + 1).startsWith(KEXACK_MARKER)
    }

    /**
     * Parse a KEX message to extract conversation ID and payload.
     *
     * @param memo The full memo string
     * @return Pair of (convId, kexPayload) or null if invalid
     */
    fun parseKEXMessage(memo: String): Pair<String, String>? {
        if (!isKEXMessage(memo)) return null

        try {
            // Remove prefix: ZMSG|v4|
            val afterPrefix = memo.removePrefix(PREFIX_V4)
            // Split: <convID>|KEX|<hash>|<kex_payload>
            val parts = afterPrefix.split("|", limit = 4)
            if (parts.size < 4) return null

            val convId = parts[0]
            // parts[1] should be "KEX"
            // parts[2] is sender hash
            val kexPayload = parts[3]

            return Pair(convId, kexPayload)
        } catch (e: Exception) {
            Log.e("ZCHAT_PROTO", "Failed to parse KEX message: ${memo.take(80)}", e)
            return null
        }
    }

    /**
     * Parse a KEX acknowledgment message.
     *
     * @param memo The full memo string
     * @return Pair of (convId, kexAckPayload) or null if invalid
     */
    fun parseKEXAckMessage(memo: String): Pair<String, String>? {
        if (!isKEXAckMessage(memo)) return null

        try {
            val afterPrefix = memo.removePrefix(PREFIX_V4)
            val parts = afterPrefix.split("|", limit = 4)
            if (parts.size < 4) return null

            val convId = parts[0]
            // parts[1] should be "KEXACK"
            // parts[2] is sender hash
            val kexAckPayload = parts[3]

            return Pair(convId, kexAckPayload)
        } catch (e: Exception) {
            Log.e("ZCHAT_PROTO", "Failed to parse KEXACK message: ${memo.take(80)}", e)
            return null
        }
    }

    // ==========================================
    // ADDR (Address Change Notification) MESSAGES
    // ==========================================

    /**
     * Create a v4 ADDR (Address Change) notification message.
     * Format: ZMSG|v4|<convID>|ADDR|<old_sender_hash>|<new_address>|<signature>
     *
     * This message notifies a contact that the sender has changed their address.
     * The signature proves ownership of the new address (signed with new private key).
     *
     * @param convId Existing conversation ID
     * @param oldSenderAddress The OLD address (will be hashed for identification)
     * @param newAddress The NEW full unified address
     * @param signature ECDSA signature of newAddress using the NEW private key
     * @return Complete ZMSG ADDR message
     * @throws IllegalArgumentException if convId is invalid
     */
    fun createV4ADDRMessage(
        convId: String,
        oldSenderAddress: String,
        newAddress: String,
        signature: String
    ): String {
        validateConvId(convId)
        val oldHash = generateAddressHash(oldSenderAddress)
        return "$PREFIX_V4$convId|$ADDR_MARKER$oldHash|$newAddress|$signature"
    }

    /**
     * Check if a message is an ADDR (address change) notification.
     */
    fun isADDRMessage(memo: String): Boolean {
        if (!memo.startsWith(PREFIX_V4)) return false
        // Match: ZMSG|v4|<8-char-convId>|ADDR|...
        val afterPrefix = memo.removePrefix(PREFIX_V4)
        return afterPrefix.length > CONV_ID_LENGTH + 1 &&
            afterPrefix[CONV_ID_LENGTH] == '|' &&
            afterPrefix.substring(CONV_ID_LENGTH + 1).startsWith(ADDR_MARKER)
    }

    /**
     * Parse an ADDR message to extract address change information.
     *
     * @param memo The full memo string
     * @return ParsedADDRMessage with convId, old hash, new address, and signature, or null if invalid
     */
    fun parseADDRMessage(memo: String): ParsedADDRMessage? {
        if (!isADDRMessage(memo)) return null

        try {
            val afterPrefix = memo.removePrefix(PREFIX_V4)
            val parts = afterPrefix.split("|", limit = 5)
            if (parts.size < 5) return null

            val convId = parts[0]
            // parts[1] should be "ADDR"
            val oldSenderHash = parts[2]
            val newAddress = parts[3]
            val signature = parts[4]

            return ParsedADDRMessage(
                conversationId = convId,
                oldSenderHash = oldSenderHash,
                newAddress = newAddress,
                signature = signature
            )
        } catch (e: Exception) {
            Log.e("ZCHAT_PROTO", "Failed to parse ADDR message: ${memo.take(80)}", e)
            return null
        }
    }

    /**
     * Create chunked v4 INIT messages (first message to a new contact)
     * Returns list of memo strings, one per output
     * @throws IllegalArgumentException if convId is invalid
     */
    fun createChunkedV4InitMessages(convId: String, senderAddress: String, message: String): List<String> {
        validateConvId(convId)
        val parts = packChunks(message, CHUNK_SIZE_V4_INIT)
        val totalChunks = parts.size
        require(totalChunks <= MAX_CHUNKS) { "Message too large: $totalChunks chunks exceeds max $MAX_CHUNKS" }

        if (totalChunks == 1) {
            return listOf(createV4InitMessage(convId, senderAddress, message))
        }

        val chunks = mutableListOf<String>()
        for (i in 1..totalChunks) {
            val messagePart = parts[i - 1]
            val memo = if (i == 1) {
                "${PREFIX_V4C}$i/$totalChunks|$convId|$INIT_MARKER$senderAddress|$messagePart"
            } else {
                "${PREFIX_V4C}$i/$totalChunks|$CONT_MARKER$messagePart"
            }

            chunks.add(memo)
        }

        return chunks
    }

    /**
     * Create chunked v4 reply messages (subsequent messages)
     * Returns list of memo strings, one per output
     *
     * Includes sender hash in first chunk for fallback identification.
     * @throws IllegalArgumentException if convId is invalid
     */
    fun createChunkedV4ReplyMessages(convId: String, senderAddress: String, message: String): List<String> {
        validateConvId(convId)
        val parts = packChunks(message, CHUNK_SIZE_V4_REPLY_FIRST)
        val totalChunks = parts.size
        require(totalChunks <= MAX_CHUNKS) { "Message too large: $totalChunks chunks exceeds max $MAX_CHUNKS" }

        if (totalChunks == 1) {
            return listOf(createV4ReplyMessage(convId, senderAddress, message))
        }

        val hash = generateAddressHash(senderAddress)
        val chunks = mutableListOf<String>()
        for (i in 1..totalChunks) {
            val messagePart = parts[i - 1]
            val memo = if (i == 1) {
                "${PREFIX_V4C}$i/$totalChunks|$convId|$hash|$messagePart"
            } else {
                "${PREFIX_V4C}$i/$totalChunks|$CONT_MARKER$messagePart"
            }

            chunks.add(memo)
        }

        return chunks
    }

    /**
     * Calculate number of chunks needed for a v4 message
     */
    fun calculateV4ChunkCount(message: String, isInitMessage: Boolean): Int {
        val firstChunkSize = if (isInitMessage) CHUNK_SIZE_V4_INIT else CHUNK_SIZE_V4_REPLY_FIRST
        // Count by SIMULATING the real packing so the count can never disagree with what the
        // builder produces (see packChunks for the desync this prevents).
        return packChunks(message, firstChunkSize).size
    }

    /**
     * Check if a memo is a v4 format message
     */
    fun isV4Message(memo: String): Boolean = memo.startsWith(PREFIX_V4) || memo.startsWith(PREFIX_V4C)

    /**
     * Check if a memo is a v4 chunked message
     */
    fun isV4ChunkedMemo(memo: String): Boolean = memo.startsWith(PREFIX_V4C)

    // ==========================================
    // ZMSG v3 PROTOCOL (Legacy support)
    // ==========================================

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
     *
     * @deprecated Use createRefMessage instead for reliable conversation threading
     */
    fun createReplyMessage(senderAddress: String, message: String): String {
        val hash = generateAddressHash(senderAddress)
        return "$PREFIX_V3$hash|$message"
    }

    /**
     * Create a REF message (transaction-referenced reply)
     * Format: ZMSG|v3|REF|<last_received_txid>|<sender_hash>|<message>
     *
     * This format uses the transaction ID of the last RECEIVED message in the conversation
     * to enable reliable conversation threading, regardless of diversified addresses.
     *
     * @param senderAddress The sender's address (will be hashed)
     * @param message The message content
     * @param lastReceivedTxId The txid of the last message RECEIVED in this conversation
     */
    fun createRefMessage(senderAddress: String, message: String, lastReceivedTxId: String): String {
        Log.d("ZCHAT_THREADING", "createRefMessage: embedding txId = '$lastReceivedTxId'")
        val hash = generateAddressHash(senderAddress)
        val result = "$PREFIX_V3$REF_MARKER$lastReceivedTxId|$hash|$message"
        Log.d("ZCHAT_THREADING", "createRefMessage: created = ${result.take(80)}...")
        return result
    }

    /**
     * Create a REF INIT message (first message that references a prior transaction)
     * Format: ZMSG|v3|REF|<last_received_txid>|INIT|<sender_address>|<message>
     *
     * Used when sending a first message to someone who has already sent you a message.
     */
    fun createRefInitMessage(senderAddress: String, message: String, lastReceivedTxId: String): String {
        return "$PREFIX_V3$REF_MARKER$lastReceivedTxId|$INIT_MARKER$senderAddress|$message"
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
        val branch: String
        val result = when {
            // GROUP protocol messages - check first
            memo.startsWith(ZMSGConstants.Prefixes.GROUP) -> {
                branch = "GROUP"
                parseGroupMessage(memo)
            }
            // ZBOOT (NOSTR-identity handshake) — NOT a ZMSG envelope, so it would otherwise fall to the
            // PLAIN branch with conversationId=null and never resolve its sender (a shielded receive
            // hides the sender + the ZBOOT carries no address/hash). Extract the convId so TIER-1 routing
            // can map it to the peer whose KEX/KEXACK already established that convId. The signature is
            // re-verified in routeIncomingBoot; here we only surface the convId for threading/routing.
            ZBootMessage.isBootMessage(memo) -> {
                branch = "ZBOOT"
                ParsedMessage(
                    senderAddress = null,
                    senderHash = null,
                    message = memo,
                    isUnknownSender = true,
                    reason = null,
                    conversationId = ZBootMessage.parse(memo)?.convId
                )
            }
            // ZMSGv4 messages (conversation ID based) - check first for latest protocol
            memo.startsWith(PREFIX_V4) -> {
                branch = "V4"
                parseV4Message(memo, addressCache)
            }
            // ZMSGv3 INIT message
            memo.startsWith("$PREFIX_V3$INIT_MARKER") -> {
                branch = "V3_INIT"
                parseV3InitMessage(memo, addressCache)
            }
            // ZMSGv3 REF message (transaction-referenced) - check before RPL and hash formats
            memo.startsWith("$PREFIX_V3$REF_MARKER") -> {
                branch = "V3_REF"
                parseRefMessage(memo, addressCache) ?: ParsedMessage(
                    senderAddress = null,
                    senderHash = null,
                    message = memo,
                    isUnknownSender = true,
                    reason = UnknownReason.MALFORMED_MESSAGE
                )
            }
            // ZMSGv3 RPL (reply) message - MUST check before generic v3 hash format!
            memo.startsWith("$PREFIX_V3$REPLY_MARKER") -> {
                branch = "V3_RPL"
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
                branch = "V3_HASH"
                parseV3ReplyMessage(memo, addressCache)
            }
            // Legacy ZMSGv2 message
            memo.startsWith(PREFIX_V2) -> {
                branch = "V2"
                parseV2Message(memo, addressCache)
            }
            // Recognized ZMSG envelope ("ZMSG|...") but an unsupported/legacy version.
            // This IS a ZCHAT message — we just can't decode this version. Flag it distinctly
            // from a truly foreign memo so callers don't mislabel it "not sent using ZCHAT".
            isRecognizedZmsgEnvelope(memo) -> {
                branch = "ZMSG_VERSION"
                ParsedMessage(
                    senderAddress = null,
                    senderHash = null,
                    message = memo,
                    isUnknownSender = true,
                    reason = UnknownReason.VERSION_MISMATCH
                )
            }
            // Plain text / foreign memo (NO recognized ZCHAT prefix at all)
            else -> {
                branch = "PLAIN"
                ParsedMessage(
                    senderAddress = null,
                    senderHash = null,
                    message = memo,
                    isUnknownSender = true,
                    reason = UnknownReason.NOT_ZMSG_FORMAT
                )
            }
        }
        Log.d("ZCHAT_PROTO", "parseMemo branch=$branch unknown=${result.isUnknownSender} reason=${result.reason} convId=${result.conversationId} memo=${memo.take(40)}")
        return result
    }

    /**
     * Returns true if [memo] carries a recognized ZCHAT/ZMSG envelope, even if the specific
     * version cannot be decoded by this build. Used to distinguish a real-but-undecodable ZCHAT
     * message (VERSION_MISMATCH) from a genuinely foreign/plain memo (NOT_ZMSG_FORMAT).
     *
     * SECURITY: this is intentionally narrow — it only matches the "ZMSG|" / "ZMSG:" envelope and
     * the dedicated ZCHAT special-message prefixes. A plain memo that merely contains a convId-like
     * string is NOT matched here and must still be flagged NOT_ZMSG_FORMAT.
     */
    fun isRecognizedZmsgEnvelope(memo: String): Boolean {
        return memo.startsWith("ZMSG|") ||
            memo.startsWith("ZMSG:") ||
            memo.startsWith(ZMSGConstants.Prefixes.REACTION) ||
            memo.startsWith(ZMSGConstants.Prefixes.RECEIPT) ||
            memo.startsWith(ZMSGConstants.Prefixes.STATUS) ||
            memo.startsWith(ZMSGConstants.Prefixes.TIMELOCK) ||
            memo.startsWith(ZMSGConstants.Prefixes.UNLOCK) ||
            memo.startsWith(ZMSGConstants.Prefixes.REQUEST) ||
            memo.startsWith(ZMSGConstants.Prefixes.FILE)
    }

    /**
     * Parse a GROUP protocol message.
     * Format: ZMSG:3.0:GROUP:<type>:<group_id>:<payload>
     * Returns a ParsedMessage with messageType=GROUP and group-specific fields populated.
     */
    private fun parseGroupMessage(memo: String): ParsedMessage {
        val groupId = ZMSGGroupProtocol.parseGroupId(memo)
        val messageType = ZMSGGroupProtocol.parseMessageType(memo)
        val payload = ZMSGGroupProtocol.parsePayload(memo)

        return ParsedMessage(
            senderAddress = null,  // Sender is encoded in the payload, decrypted later
            senderHash = null,
            message = payload ?: memo,  // Raw payload for later processing
            isUnknownSender = false,
            reason = null,
            messageType = MessageType.GROUP,
            groupId = groupId,
            groupMessageType = messageType
        )
    }

    /**
     * Parse a v4 message (conversation ID based)
     * Formats:
     * - INIT: ZMSG|v4|<convID>|INIT|<address>|<message>
     * - Reply (new): ZMSG|v4|<convID>|<hash>|<message>
     * - Reply (legacy): ZMSG|v4|<convID>|<message>
     */
    private fun parseV4Message(memo: String, addressCache: AddressCache): ParsedMessage {
        val content = memo.removePrefix(PREFIX_V4)
        val firstPipe = content.indexOf('|')
        if (firstPipe == -1 || firstPipe != CONV_ID_LENGTH) {
            return ParsedMessage(
                senderAddress = null,
                senderHash = null,
                message = memo,
                isUnknownSender = true,
                reason = UnknownReason.MALFORMED_MESSAGE
            )
        }

        val convId = content.substring(0, firstPipe)

        // Validate convId format (same validation as in creation functions)
        if (!convId.all { it in CONV_ID_CHARS }) {
            Log.w("ZMSG", "Invalid convId characters in v4 message: ${convId.take(2)}...")
            return ParsedMessage(
                senderAddress = null,
                senderHash = null,
                message = memo,
                isUnknownSender = true,
                reason = UnknownReason.MALFORMED_MESSAGE
            )
        }

        val remaining = content.substring(firstPipe + 1)

        // Check if this is an INIT message
        return if (remaining.startsWith(INIT_MARKER)) {
            // INIT format: INIT|<address>|<message>
            val afterInit = remaining.removePrefix(INIT_MARKER)
            val sepIndex = afterInit.indexOf('|')
            if (sepIndex == -1) {
                return ParsedMessage(
                    senderAddress = null,
                    senderHash = null,
                    message = memo,
                    isUnknownSender = true,
                    reason = UnknownReason.MALFORMED_MESSAGE
                )
            }

            val address = afterInit.substring(0, sepIndex)
            val message = afterInit.substring(sepIndex + 1)
            val hash = generateAddressHash(address)

            // Cache the address for future lookups
            addressCache.cacheAddress(hash, address)

            ParsedMessage(
                senderAddress = address,
                senderHash = hash,
                message = message,
                isUnknownSender = false,
                reason = null,
                conversationId = convId,
                messageType = MessageType.REGULAR
            )
        } else {
            // Check for new reply format with hash: <hash>|<message>
            // Hash is 12 hex characters (legacy) or 16 hex characters (new)
            val hashSepIndex = remaining.indexOf('|')
            val hasHashFormat = (hashSepIndex == HASH_LENGTH || hashSepIndex == HASH_LENGTH_NEW) &&
                remaining.substring(0, hashSepIndex).all { it in '0'..'9' || it in 'a'..'f' }

            if (hasHashFormat) {
                // New reply format: <hash>|<message>
                val hash = remaining.substring(0, hashSepIndex)
                val message = remaining.substring(hashSepIndex + 1)
                val address = addressCache.getAddress(hash)

                ParsedMessage(
                    senderAddress = address,  // May be null if not in cache, will use convID as primary
                    senderHash = hash,  // Hash available for fallback
                    message = message,
                    isUnknownSender = false,  // Will resolve via convID first, then hash
                    reason = null,
                    conversationId = convId,
                    messageType = MessageType.REGULAR
                )
            } else {
                // Legacy reply format: just <message>
                ParsedMessage(
                    senderAddress = null,  // Will be resolved via convID lookup
                    senderHash = null,
                    message = remaining,
                    isUnknownSender = false,  // Not unknown - will resolve via convID
                    reason = null,
                    conversationId = convId,
                    messageType = MessageType.REGULAR
                )
            }
        }
    }

    /**
     * Parse a REF message (transaction-referenced reply)
     * Formats:
     * - ZMSG|v3|REF|<txid>|<hash>|<message>
     * - ZMSG|v3|REF|<txid>|INIT|<address>|<message>
     */
    private fun parseRefMessage(memo: String, addressCache: AddressCache): ParsedMessage? {
        Log.d("ZCHAT_THREADING", "parseRefMessage: raw memo = ${memo.take(100)}...")
        val content = memo.removePrefix("$PREFIX_V3$REF_MARKER")
        val firstPipe = content.indexOf('|')
        if (firstPipe == -1) return null

        val refTxId = content.substring(0, firstPipe)
        Log.d("ZCHAT_THREADING", "parseRefMessage: extracted refTxId = '$refTxId'")
        val remaining = content.substring(firstPipe + 1)

        // Check if this is a REF|INIT format
        return if (remaining.startsWith(INIT_MARKER)) {
            // REF|<txid>|INIT|<address>|<message>
            val afterInit = remaining.removePrefix(INIT_MARKER)
            val sepIndex = afterInit.indexOf('|')
            if (sepIndex == -1) return null

            val address = afterInit.substring(0, sepIndex)
            val message = afterInit.substring(sepIndex + 1)
            val hash = generateAddressHash(address)

            // Cache the address
            addressCache.cacheAddress(hash, address)

            ParsedMessage(
                senderAddress = address,
                senderHash = hash,
                message = message,
                isUnknownSender = false,
                reason = null,
                replyToTxId = refTxId,  // Use refTxId for conversation lookup
                messageType = MessageType.REGULAR
            )
        } else {
            // REF|<txid>|<hash>|<message>
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
                replyToTxId = refTxId,  // Use refTxId for conversation lookup
                messageType = MessageType.REGULAR
            )
        }
    }

    /**
     * Check if a memo is a REF (transaction-referenced) message
     */
    fun isRefMessage(memo: String): Boolean = memo.startsWith("$PREFIX_V3$REF_MARKER")

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
        return byteLen(message) > availableLength
    }

    /**
     * Calculate the number of chunks needed for a message
     */
    fun calculateChunkCount(message: String, isInitMessage: Boolean): Int {
        val firstChunkSize = if (isInitMessage) CHUNK_SIZE_INIT else CHUNK_SIZE_REPLY_FIRST
        // Simulate the real packing so count == what the builder emits (see packChunks).
        return packChunks(message, firstChunkSize).size
    }

    /**
     * Create chunked INIT messages (first message to a new contact)
     * Returns list of memo strings, one per output
     */
    fun createChunkedInitMessages(senderAddress: String, message: String): List<String> {
        val parts = packChunks(message, CHUNK_SIZE_INIT)
        val totalChunks = parts.size
        require(totalChunks <= MAX_CHUNKS) { "Message too large: $totalChunks chunks exceeds max $MAX_CHUNKS" }

        if (totalChunks == 1) {
            return listOf(createInitMessage(senderAddress, message))
        }

        val chunks = mutableListOf<String>()
        for (i in 1..totalChunks) {
            val messagePart = parts[i - 1]
            val memo = if (i == 1) {
                "${PREFIX_V3C}$i/$totalChunks|$INIT_MARKER$senderAddress|$messagePart"
            } else {
                "${PREFIX_V3C}$i/$totalChunks|$CONT_MARKER$messagePart"
            }

            chunks.add(memo)
        }

        return chunks
    }

    /**
     * Create chunked reply messages (subsequent messages)
     * Returns list of memo strings, one per output
     *
     * @deprecated Use createChunkedRefMessages for reliable conversation threading
     */
    fun createChunkedReplyMessages(senderAddress: String, message: String): List<String> {
        val parts = packChunks(message, CHUNK_SIZE_REPLY_FIRST)
        val totalChunks = parts.size
        require(totalChunks <= MAX_CHUNKS) { "Message too large: $totalChunks chunks exceeds max $MAX_CHUNKS" }

        if (totalChunks == 1) {
            return listOf(createReplyMessage(senderAddress, message))
        }

        val hash = generateAddressHash(senderAddress)
        val chunks = mutableListOf<String>()
        for (i in 1..totalChunks) {
            val messagePart = parts[i - 1]
            val memo = if (i == 1) {
                "${PREFIX_V3C}$i/$totalChunks|$hash|$messagePart"
            } else {
                "${PREFIX_V3C}$i/$totalChunks|$CONT_MARKER$messagePart"
            }

            chunks.add(memo)
        }

        return chunks
    }

    /**
     * Create chunked REF messages (transaction-referenced replies)
     * Returns list of memo strings, one per output
     *
     * This format uses a transaction reference for reliable conversation threading.
     *
     * @param senderAddress The sender's address (will be hashed)
     * @param message The message content
     * @param lastReceivedTxId The txid of the last message received in this conversation
     */
    fun createChunkedRefMessages(senderAddress: String, message: String, lastReceivedTxId: String): List<String> {
        // For REF format, first chunk has more overhead (~70 bytes for REF|txid|hash|)
        // so we use a smaller first chunk size
        val refFirstChunkSize = CHUNK_SIZE_REPLY_FIRST - 70
        val parts = packChunks(message, refFirstChunkSize)
        val totalChunks = parts.size
        require(totalChunks <= MAX_CHUNKS) { "Message too large: $totalChunks chunks exceeds max $MAX_CHUNKS" }

        if (totalChunks == 1) {
            return listOf(createRefMessage(senderAddress, message, lastReceivedTxId))
        }

        val hash = generateAddressHash(senderAddress)
        val chunks = mutableListOf<String>()
        for (i in 1..totalChunks) {
            val messagePart = parts[i - 1]
            val memo = if (i == 1) {
                "${PREFIX_V3C}$i/$totalChunks|$REF_MARKER$lastReceivedTxId|$hash|$messagePart"
            } else {
                "${PREFIX_V3C}$i/$totalChunks|$CONT_MARKER$messagePart"
            }

            chunks.add(memo)
        }

        return chunks
    }

    /**
     * Calculate chunk count for REF format messages
     */
    private fun calculateChunkCountForRef(message: String, firstChunkSize: Int): Int =
        // Simulate the real packing so count == what the builder emits (see packChunks).
        packChunks(message, firstChunkSize).size

    /**
     * Reassemble chunked memos from a single transaction into a complete message.
     * Call this with all memos from the same transaction.
     * Returns null if chunks are incomplete or invalid.
     */
    fun reassembleChunks(memos: List<String>, addressCache: AddressCache): ParsedMessage? {
        if (memos.isEmpty()) return null

        // Filter only chunked messages (v3 or v4)
        val chunkedMemos = memos.filter { it.startsWith(PREFIX_V3C) || it.startsWith(PREFIX_V4C) }

        if (chunkedMemos.isEmpty()) {
            // Not chunked, parse as single message
            if (memos.size != 1) {
                Log.w("ZCHAT_PROTO", "reassembleChunks: ${memos.size} non-chunked memos, cannot parse as single message")
            }
            return if (memos.size == 1) parseMemo(memos[0], addressCache) else null
        }

        // Parse chunk info and sort
        val parsedCount = chunkedMemos.size
        val chunks = chunkedMemos.mapNotNull { memo ->
            parseChunkInfo(memo)
        }.sortedBy { it.index }

        if (chunks.isEmpty()) {
            Log.w("ZCHAT_PROTO", "reassembleChunks: all $parsedCount chunked memos failed to parse")
            return null
        }
        if (chunks.size < parsedCount) {
            Log.w("ZCHAT_PROTO", "reassembleChunks: ${parsedCount - chunks.size}/$parsedCount chunk headers failed to parse")
        }

        // Verify we have all chunks
        val totalChunks = chunks.first().total
        if (chunks.size != totalChunks) {
            Log.w("ZCHAT_PROTO", "reassembleChunks: expected $totalChunks chunks but got ${chunks.size} (indices: ${chunks.map { it.index }})")
            return null
        }
        if (chunks.map { it.index }.toSet() != (1..totalChunks).toSet()) {
            Log.w("ZCHAT_PROTO", "reassembleChunks: non-contiguous chunk indices: ${chunks.map { it.index }} (expected 1..$totalChunks)")
            return null
        }

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
            firstChunk.senderInfo != null && firstChunk.conversationId == null -> {
                // v3 format with hash
                senderHash = firstChunk.senderInfo
                senderAddress = addressCache.getAddress(senderHash)
            }
            else -> {
                // v4 format - extract hash for fallback routing, resolve address via convID
                // For v4 replies, senderInfo contains the hex hash (12 or 16 chars) which enables
                // fallback routing if convID lookup fails (e.g., after data loss)
                senderHash = if (!firstChunk.isInit &&
                                 firstChunk.senderInfo != null &&
                                 (firstChunk.senderInfo.length == HASH_LENGTH || firstChunk.senderInfo.length == HASH_LENGTH_NEW) &&
                                 firstChunk.senderInfo.all { it in '0'..'9' || it in 'a'..'f' }) {
                    firstChunk.senderInfo
                } else null
                senderAddress = senderHash?.let { addressCache.getAddress(it) }
            }
        }

        // Concatenate all message parts
        val fullMessage = chunks.joinToString("") { it.messagePart }

        // Get refTxId and convId from first chunk if present
        val refTxId = firstChunk.refTxId
        val convId = firstChunk.conversationId

        // For v4 format without INIT, senderAddress is null but isUnknownSender should be false
        // because we'll resolve via convID
        val isUnknown = if (convId != null && !firstChunk.isInit) {
            false  // Will resolve via convID
        } else {
            senderAddress == null
        }

        return ParsedMessage(
            senderAddress = senderAddress,
            senderHash = senderHash,
            message = fullMessage,
            isUnknownSender = isUnknown,
            reason = if (isUnknown && senderAddress == null) {
                if (firstChunk.isInit) UnknownReason.MALFORMED_MESSAGE
                else UnknownReason.HASH_NOT_IN_CACHE
            } else null,
            replyToTxId = refTxId,
            conversationId = convId
        )
    }

    /**
     * Parse chunk information from a chunked memo (v3 or v4)
     */
    private fun parseChunkInfo(memo: String): ChunkInfo? {
        // Handle v4 chunked format first
        if (memo.startsWith(PREFIX_V4C)) {
            return parseV4ChunkInfo(memo)
        }

        // Handle v3 chunked format
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

        // Validate chunk bounds to prevent DoS via OOM
        if (chunkIndex < 1 || totalChunks < 1 || chunkIndex > totalChunks || totalChunks > MAX_CHUNKS) {
            Log.w("ZMSG", "Invalid v3 chunk count: $chunkIndex/$totalChunks (max $MAX_CHUNKS)")
            return null
        }

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
            // First chunk with REF: "REF|<txid>|<hash>|<message>" or "REF|<txid>|INIT|<address>|<message>"
            remaining.startsWith(REF_MARKER) -> {
                val afterRef = remaining.removePrefix(REF_MARKER)
                val firstPipe = afterRef.indexOf('|')
                if (firstPipe == -1) return null

                val refTxId = afterRef.substring(0, firstPipe)
                val afterTxId = afterRef.substring(firstPipe + 1)

                // Check if this is REF|txid|INIT|address|message format
                if (afterTxId.startsWith(INIT_MARKER)) {
                    val afterInit = afterTxId.removePrefix(INIT_MARKER)
                    val sepIndex = afterInit.indexOf('|')
                    if (sepIndex == -1) return null
                    ChunkInfo(
                        index = chunkIndex,
                        total = totalChunks,
                        isInit = true,
                        senderInfo = afterInit.substring(0, sepIndex),
                        messagePart = afterInit.substring(sepIndex + 1),
                        refTxId = refTxId
                    )
                } else {
                    // REF|txid|hash|message format
                    val sepIndex = afterTxId.indexOf('|')
                    if (sepIndex == -1) return null
                    ChunkInfo(
                        index = chunkIndex,
                        total = totalChunks,
                        isInit = false,
                        senderInfo = afterTxId.substring(0, sepIndex),
                        messagePart = afterTxId.substring(sepIndex + 1),
                        refTxId = refTxId
                    )
                }
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
     * Parse v4 chunk information
     * Formats:
     * - First chunk INIT: ZMSG|v4c|1/N|<convID>|INIT|<address>|<message_part>
     * - First chunk reply: ZMSG|v4c|1/N|<convID>|<message_part>
     * - Continuation: ZMSG|v4c|M/N|CONT|<message_part>
     */
    private fun parseV4ChunkInfo(memo: String): ChunkInfo? {
        val content = memo.removePrefix(PREFIX_V4C)

        // Parse chunk number: "1/3|..."
        val chunkEndIndex = content.indexOf('|')
        if (chunkEndIndex == -1) return null

        val chunkPart = content.substring(0, chunkEndIndex)
        val slashIndex = chunkPart.indexOf('/')
        if (slashIndex == -1) return null

        val chunkIndex = chunkPart.substring(0, slashIndex).toIntOrNull() ?: return null
        val totalChunks = chunkPart.substring(slashIndex + 1).toIntOrNull() ?: return null

        // Validate chunk bounds to prevent DoS via OOM
        // Max 1000 chunks (~500KB message) is reasonable; more than this is likely an attack
        if (chunkIndex < 1 || totalChunks < 1 || chunkIndex > totalChunks || totalChunks > MAX_CHUNKS) {
            Log.w("ZMSG", "Invalid v4 chunk count: $chunkIndex/$totalChunks (max $MAX_CHUNKS)")
            return null
        }

        val remaining = content.substring(chunkEndIndex + 1)

        return when {
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
            // First chunk - starts with convID
            else -> {
                // Extract convID (8 chars)
                val convIdEnd = remaining.indexOf('|')
                if (convIdEnd == -1 || convIdEnd != CONV_ID_LENGTH) return null

                val convId = remaining.substring(0, convIdEnd)

                // Validate convId characters
                if (!convId.all { it in CONV_ID_CHARS }) {
                    Log.w("ZMSG", "Invalid convId characters in v4 chunk")
                    return null
                }

                val afterConvId = remaining.substring(convIdEnd + 1)

                // Check if this is INIT format
                if (afterConvId.startsWith(INIT_MARKER)) {
                    val afterInit = afterConvId.removePrefix(INIT_MARKER)
                    val sepIndex = afterInit.indexOf('|')
                    if (sepIndex == -1) return null
                    ChunkInfo(
                        index = chunkIndex,
                        total = totalChunks,
                        isInit = true,
                        senderInfo = afterInit.substring(0, sepIndex),
                        messagePart = afterInit.substring(sepIndex + 1),
                        conversationId = convId
                    )
                } else {
                    // Check for new reply format with hash: <hash>|<message>
                    // Hash is 12 hex characters (legacy) or 16 hex characters (new)
                    val hashSepIndex = afterConvId.indexOf('|')
                    val hasHashFormat = (hashSepIndex == HASH_LENGTH || hashSepIndex == HASH_LENGTH_NEW) &&
                        afterConvId.substring(0, hashSepIndex).all { it in '0'..'9' || it in 'a'..'f' }

                    if (hasHashFormat) {
                        // New reply format: <hash>|<message>
                        ChunkInfo(
                            index = chunkIndex,
                            total = totalChunks,
                            isInit = false,
                            senderInfo = afterConvId.substring(0, hashSepIndex),  // hash as senderInfo
                            messagePart = afterConvId.substring(hashSepIndex + 1),
                            conversationId = convId
                        )
                    } else {
                        // Legacy reply format: just message
                        ChunkInfo(
                            index = chunkIndex,
                            total = totalChunks,
                            isInit = false,
                            senderInfo = null,
                            messagePart = afterConvId,
                            conversationId = convId
                        )
                    }
                }
            }
        }
    }

    /**
     * Check if a memo is part of a chunked message
     */
    fun isChunkedMemo(memo: String): Boolean = memo.startsWith(PREFIX_V3C) || memo.startsWith(PREFIX_V4C)

    /**
     * Get the maximum message length when using chunking (practically unlimited with multi-output)
     * Returns the approximate max based on reasonable limits (e.g., 10 chunks)
     */
    fun getMaxChunkedMessageLength(isInitMessage: Boolean, maxChunks: Int = 10): Int {
        val firstChunkSize = if (isInitMessage) CHUNK_SIZE_INIT else CHUNK_SIZE_REPLY_FIRST
        return firstChunkSize + (maxChunks - 1) * CHUNK_SIZE_CONTINUATION
    }

    // ==========================================
    // REPLIES (part of core v3 protocol)
    // ==========================================

    private const val REPLY_MARKER = ZMSGConstants.Markers.REPLY

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
     * Check if a memo is a reply to another message
     */
    fun isReply(memo: String): Boolean = memo.startsWith("$PREFIX_V3$REPLY_MARKER")

    // ==========================================
    // SPECIAL MESSAGES (delegated to ZMSGSpecialMessages)
    // These functions maintain backward compatibility
    // ==========================================

    // Reactions
    fun createReaction(targetTxId: String, emoji: String, senderAddress: String): String =
        ZMSGSpecialMessages.createReaction(targetTxId, emoji, senderAddress)

    fun parseReaction(memo: String, addressCache: AddressCache): ParsedReaction? =
        ZMSGSpecialMessages.parseReaction(memo, addressCache)

    fun isReaction(memo: String): Boolean = ZMSGSpecialMessages.isReaction(memo)

    // Read receipts
    fun createReadReceipt(targetTxId: String, senderAddress: String): String =
        ZMSGSpecialMessages.createReadReceipt(targetTxId, senderAddress)

    fun parseReadReceipt(memo: String, addressCache: AddressCache): ParsedReadReceipt? =
        ZMSGSpecialMessages.parseReadReceipt(memo, addressCache)

    fun isReadReceipt(memo: String): Boolean = ZMSGSpecialMessages.isReadReceipt(memo)

    // Status
    fun createStatusMessage(statusText: String, senderAddress: String): String =
        ZMSGSpecialMessages.createStatusMessage(statusText, senderAddress)

    fun parseStatus(memo: String, addressCache: AddressCache): ParsedStatus? =
        ZMSGSpecialMessages.parseStatus(memo, addressCache)

    fun isStatus(memo: String): Boolean = ZMSGSpecialMessages.isStatus(memo)

    // Time-locked messages
    fun createScheduledMessage(message: String, senderAddress: String, unlockTimestamp: Long): String =
        ZMSGSpecialMessages.createScheduledMessage(message, senderAddress, unlockTimestamp)

    fun createBlockLockedMessage(message: String, senderAddress: String, unlockHeight: Long): String =
        ZMSGSpecialMessages.createBlockLockedMessage(message, senderAddress, unlockHeight)

    fun createPaymentLockedMessage(message: String, senderAddress: String, requiredZatoshi: Long): String =
        ZMSGSpecialMessages.createPaymentLockedMessage(message, senderAddress, requiredZatoshi)

    fun createConditionalMessage(message: String, senderAddress: String, answer: String, hint: String): String =
        ZMSGSpecialMessages.createConditionalMessage(message, senderAddress, answer, hint)

    fun createUnlockPayment(originalTxId: String, senderAddress: String): String =
        ZMSGSpecialMessages.createUnlockPayment(originalTxId, senderAddress)

    fun createUnlockAnswer(originalTxId: String, answer: String, senderAddress: String): String =
        ZMSGSpecialMessages.createUnlockAnswer(originalTxId, answer, senderAddress)

    fun parseTimeLock(memo: String, addressCache: AddressCache): ParsedTimeLock? =
        ZMSGSpecialMessages.parseTimeLock(memo, addressCache)

    fun parseUnlock(memo: String, addressCache: AddressCache): ParsedUnlock? =
        ZMSGSpecialMessages.parseUnlock(memo, addressCache)

    fun verifyConditionalAnswer(answer: String, answerHash: String): Boolean =
        ZMSGSpecialMessages.verifyConditionalAnswer(answer, answerHash)

    fun isTimeLock(memo: String): Boolean = ZMSGSpecialMessages.isTimeLock(memo)

    fun isUnlock(memo: String): Boolean = ZMSGSpecialMessages.isUnlock(memo)

    // Payment requests
    fun createPaymentRequest(amountZatoshi: Long, senderAddress: String, reason: String = ""): String =
        ZMSGSpecialMessages.createPaymentRequest(amountZatoshi, senderAddress, reason)

    fun parsePaymentRequest(memo: String, addressCache: AddressCache): ParsedPaymentRequest? =
        ZMSGSpecialMessages.parsePaymentRequest(memo, addressCache)

    fun isPaymentRequest(memo: String): Boolean = ZMSGSpecialMessages.isPaymentRequest(memo)

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
    val messagePart: String,
    val refTxId: String? = null,  // Transaction ID reference for REF format (v3)
    val conversationId: String? = null  // Conversation ID for v4 format
)

/**
 * Parsed address change notification
 */
data class ParsedADDRMessage(
    val conversationId: String,
    val oldSenderHash: String,
    val newAddress: String,
    val signature: String
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
    val replyToTxId: String? = null,  // Transaction ID being replied to (v3 REF format)
    val conversationId: String? = null, // Conversation ID for threading (v4 format)
    val messageType: MessageType = MessageType.REGULAR,
    // Group message fields (when messageType == GROUP)
    val groupId: String? = null,
    val groupMessageType: GroupMessageType? = null
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
    PAYMENT_REQUEST, // Request for payment
    GROUP            // Group chat message (handled by ZMSGGroupProtocol)
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
    NOT_ZMSG_FORMAT,      // Message has NO recognized ZCHAT/ZMSG prefix at all (plain/foreign memo)
    MALFORMED_MESSAGE,    // Recognized ZMSG prefix but the body is malformed
    HASH_NOT_IN_CACHE,    // Recognized ZMSG message whose sender hash isn't in the address cache yet
    VERSION_MISMATCH      // Recognized ZMSG envelope ("ZMSG|...") but an unsupported/legacy version
}

/**
 * Interface for address cache
 */
interface AddressCache {
    fun cacheAddress(hash: String, address: String)
    /**
     * Cache address from a trusted/high-confidence source (e.g., convID-resolved TIER1 routing).
     * Bypasses collision protection — will overwrite existing entries.
     */
    fun cacheAddressValidated(hash: String, address: String)
    fun getAddress(hash: String): String?
    fun hasAddress(hash: String): Boolean
    fun getAllCachedAddresses(): Map<String, String>

    /**
     * Track an address as a "conversation partner" - someone we've communicated with.
     * This helps match incoming messages from different diversified addresses.
     */
    fun addConversationPartner(address: String)

    /**
     * Get all conversation partner addresses.
     */
    fun getConversationPartners(): Set<String>

    /**
     * Check if we've communicated with this address.
     */
    fun isConversationPartner(address: String): Boolean

    /**
     * Find a conversation partner by partial hash match.
     * This is used when an incoming message has a hash we don't recognize,
     * but might be from a diversified address of someone we know.
     * Returns the most likely conversation partner address, or null.
     */
    fun findConversationPartnerByHash(hash: String): String?
}
