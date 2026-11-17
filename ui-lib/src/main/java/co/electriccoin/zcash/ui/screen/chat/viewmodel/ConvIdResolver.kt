package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants

/**
 * Conversation-id resolution for memos, kept free of ViewModel state so it is unit-testable.
 */
internal object ConvIdResolver {
    /** A ZMSG envelope is "ZMSG|v4|<convId>|…"; the conversation id is the field at this index. */
    private const val CONV_ID_INDEX = 3

    /** Fewest pipe-separated fields an envelope must have before a conversation id can exist. */
    private const val MIN_PARTS_WITH_CONV_ID = 3

    // Parsing a wire envelope: each early return names a specific malformed shape, and the nesting
    // follows the envelope's own structure. Both are clearer here than a flattened rewrite.
    @Suppress("ReturnCount", "NestedBlockDepth")
    internal fun extractConvIdFromMemo(memo: String): String? {
        // Handle multi-line memos (chunked messages joined with \n)
        // Check each line to find the convId
        val prefixV4 = ZMSGConstants.Prefixes.V4 // "ZMSG|v4|"
        val prefixV4C = ZMSGConstants.Prefixes.V4C // "ZMSG|v4c|"
        val contMarker = ZMSGConstants.Markers.CONT // "CONT"
        val convIdLength = ZMSGConstants.CONV_ID_LENGTH // 8
        val convIdChars = ZMSGConstants.CONV_ID_CHARS // "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        // Helper to validate convId format (length + character set)
        fun isValidConvId(convId: String): Boolean = convId.length == convIdLength && convId.all { it in convIdChars }

        for (line in memo.split("\n")) {
            val trimmedLine = line.trim()
            when {
                // v4 single memo: ZMSG|v4|<convID>|...
                trimmedLine.startsWith(prefixV4) && !trimmedLine.startsWith(prefixV4C) -> {
                    val parts = trimmedLine.split("|", limit = 4)
                    if (parts.size >= MIN_PARTS_WITH_CONV_ID) {
                        val candidate = parts[2]
                        if (isValidConvId(candidate)) return candidate
                    }
                }
                // v4 chunked first chunk: ZMSG|v4c|1/N|<convID>|...
                // Skip CONT chunks (they don't have convId)
                trimmedLine.startsWith(prefixV4C) -> {
                    val parts = trimmedLine.split("|", limit = 5)
                    // parts[0]=ZMSG, parts[1]=v4c, parts[2]=M/N, parts[3]=convID or CONT
                    if (parts.size > CONV_ID_INDEX && parts[CONV_ID_INDEX] != contMarker) {
                        val candidate = parts[CONV_ID_INDEX]
                        if (isValidConvId(candidate)) return candidate
                    }
                    // If this is a CONT chunk, continue checking other lines
                }
            }
        }
        // v3 and v2 don't have convId, or no valid convId found
        return null
    }

    /**
     * The conversation id to decrypt one of OUR OWN mined messages under, on rescan.
     *
     * Normally it is parsed out of the ZMSG v4 envelope in the memo. Control sends (ZDIS/ZMODE
     * over VAULT) are written with rawMemo = true and carry the bare E2E1 blob with no envelope, so
     * the memo yields nothing — [stored] supplies the peer -> convId mapping the send path used when
     * it self-cached the plaintext under the conversation-bound cache key.
     *
     * Safe for OUTGOING only: [stored] is keyed by the recipient of our own transaction, which the
     * memo cannot influence.
     */
    internal fun outgoingConvId(memoText: String, stored: () -> String?): String? =
        extractConvIdFromMemo(memoText) ?: stored()
}
