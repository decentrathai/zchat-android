package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Constants for the ZMSG (Zcash Message) protocol.
 *
 * This file centralizes all protocol-related constants for easy reference
 * and to maintain consistency across the codebase.
 */
object ZMSGConstants {

    // ==========================================
    // MEMO SIZE LIMITS
    // ==========================================

    /** Maximum size of a Zcash memo in bytes */
    const val MAX_MEMO_SIZE = 512

    // ==========================================
    // CONVERSATION IDs (v4)
    // ==========================================

    /** Length of conversation ID in characters */
    const val CONV_ID_LENGTH = 8

    /** Characters used for conversation ID generation (alphanumeric uppercase) */
    const val CONV_ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** Length of address hash in characters */
    const val HASH_LENGTH = 12

    // ==========================================
    // MESSAGE FORMAT PREFIXES
    // ==========================================

    object Prefixes {
        // ZMSG versioned formats
        const val V4 = "ZMSG|v4|"      // v4 with conversation IDs
        const val V4C = "ZMSG|v4c|"    // v4 chunked
        const val V3 = "ZMSG|v3|"      // v3 with address hash
        const val V3C = "ZMSG|v3c|"    // v3 chunked
        const val V2 = "ZMSG|v2|"      // v2 legacy (full address)

        // Group chat protocol
        const val GROUP = "ZMSG:3.0:GROUP:"  // Group messages

        // Special message types
        const val REACTION = "ZREACT|"     // Emoji reactions
        const val RECEIPT = "ZRCPT|"       // Read receipts
        const val STATUS = "ZSTAT|"        // User status
        const val TIMELOCK = "ZTL|"        // Time-locked messages
        const val UNLOCK = "ZUNLOCK|"      // Unlock messages
        const val REQUEST = "ZREQ|"        // Payment requests
    }

    // ==========================================
    // MESSAGE FORMAT MARKERS
    // ==========================================

    object Markers {
        const val INIT = "INIT|"       // First message (includes full address)
        const val CONT = "CONT|"       // Continuation chunk
        const val REF = "REF|"         // Transaction reference (v3 threading)
        const val REPLY = "RPL|"       // Reply to specific message
        const val KEX = "KEX|"         // Key exchange with signature (E2E)
        const val KEX_ACK = "KEXACK|"  // Key exchange acknowledgment
        const val ADDR = "ADDR|"       // Address change notification
    }

    // ==========================================
    // TIME-LOCK TYPES
    // ==========================================

    object TimeLockTypes {
        const val SCHEDULED = "SCH"    // Unlock at timestamp
        const val BLOCK = "BLK"        // Unlock at block height
        const val PAYMENT = "PAY"      // Unlock with payment
        const val CONDITIONAL = "CND"  // Unlock with secret answer
    }

    // ==========================================
    // CHUNK SIZE LIMITS
    // ==========================================

    /**
     * Available space for message content in each chunk type.
     * These account for protocol overhead (prefix, markers, addresses/hashes).
     */
    object ChunkSizes {
        // v3 chunk sizes (address hash based)
        // ZMSG|v3c|1/N|INIT|<address~141>| = ~160 bytes overhead
        const val V3_INIT = 340

        // ZMSG|v3c|1/N|<hash12>| = ~30 bytes overhead
        const val V3_REPLY_FIRST = 470

        // ZMSG|v3c|M/N|CONT| = ~20 bytes overhead
        const val CONTINUATION = 485

        // v4 chunk sizes (conversation ID based)
        // ZMSG|v4c|1/N|<convID8>|INIT|<address~141>| = ~170 bytes overhead
        const val V4_INIT = 330

        // ZMSG|v4c|1/N|<convID8>|<hash12>| = ~43 bytes overhead
        // (includes sender hash for fallback identification)
        const val V4_REPLY_FIRST = 462
    }

    // ==========================================
    // REMOTE KILL
    // ==========================================

    /** Prefix for remote kill command */
    const val REMOTE_KILL_PREFIX = "ZCHAT_DESTROY:"

    // ==========================================
    // PLATFORM ADDRESSES
    // ==========================================

    /** Platform fee address - should be filtered from conversations */
    const val PLATFORM_FEE_ADDRESS =
        "u1pm2ju3zua63jtww3zexpahpqlgcu35qqq9hv7689n5luz3pkuefwyk27f4t2r8wf3up8" +
        "cajkvtelhmnlja4sqk58s6qjavlyf5xv5s2qck6yuc4muee4g86zn8h4uzvdp9q3px2f6c" +
        "lxd46fvcllsphyndl7tvkjzwal68eccq7p4w53"
}
