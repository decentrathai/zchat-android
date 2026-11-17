package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.model.ZcashReceivers

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

    /** Length of LEGACY address hash in characters (6 bytes = 12 hex chars).
     * Note: New messages use 8 bytes (16 hex chars) via generateAddressHash().
     * This constant is kept for parsing backward-compatible messages. */
    const val HASH_LENGTH = 12

    /** Length of NEW address hash in characters (8 bytes = 16 hex chars).
     * Used by generateAddressHash() for outgoing messages.
     * Parsing should accept both HASH_LENGTH (legacy) and HASH_LENGTH_NEW. */
    const val HASH_LENGTH_NEW = 16

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
        const val FILE = "ZFILE|"          // File attachment (image, document)
        const val DISAPPEAR = "ZEXP|"      // Disappearing-messages TTL control (B17)
        const val MODE_CHANGE = "ZMODE|"   // Conversation-mode change control (cross-device mode sync)
        const val PROFILE = "ZPROF|"       // Self-avatar propagation (chunked JPEG over free NOSTR)
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
        // ZMSG|v4c|1/N|<convID8>|INIT|<address>| — the INIT first chunk embeds the FULL sender address,
        // whose byte length varies (testnet ~145, MAINNET 178–213). The AUTHORITATIVE first-chunk budget
        // is therefore computed DYNAMICALLY from the real address in ZMSGProtocol.v4InitFirstChunkBudget;
        // this fixed value (sized for a ~141-byte address) is only a best-effort fallback for pre-address
        // display estimates. Do NOT use it to size an on-chain memo directly — that overflowed 512 bytes
        // for real mainnet addresses (MemoTooLongException on send).
        const val V4_INIT = 330

        // ZMSG|v4c|1/N|<convID8>|<hash16>| = ~41 bytes overhead
        // (includes 16-char sender hash for fallback identification)
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

    /**
     * Platform fee address - should be filtered from conversations.
     *
     * Flavor-resolved (ui-lib/build.gradle.kts network flavors): `zcashmainnet` carries the
     * production fee UA (byte-identical to the previously hardcoded constant); `zcashtestnet`
     * carries a valid testnet UA so chat ZIP-321s (which always append the fee output — see
     * CreateChunkedMessageProposalUseCase.buildZip321Uri) pass testnet address validation instead
     * of failing every send on that flavor.
     */
    val PLATFORM_FEE_ADDRESS: String = BuildConfig.ZCHAT_PLATFORM_FEE_ADDRESS

    /**
     * R2-5 (b): platform-fee addresses earlier builds paid. Every chat message sent before 9 Sep 2026 (mainnet,
     * up to c1275ecbb) carries a fee output to the first one, and the wallet history keeps those transactions
     * for good, so they must be filtered like the current one or they open a chat of their own. Harmless on
     * testnet (a mainnet address never appears there).
     */
    val PREVIOUS_PLATFORM_FEE_ADDRESSES: Set<String> =
        setOf(
            "u1pm2ju3zua63jtww3zexpahpqlgcu35qqq9hv7689n5luz3pkuefwyk27f4" +
                "t2r8wf3up8cajkvtelhmnlja4sqk58s6qjavlyf5xv5s2qck6yuc4muee4g8" +
                "6zn8h4uzvdp9q3px2f6clxd46fvcllsphyndl7tvkjzwal68eccq7p4w53",
        )

    /**
     * True for the current platform-fee address and every one an earlier build paid. ZC-1082 (N-13): also for any address
     * whose receivers all sit within one of them — after a restore the wallet library names the fee output by the single
     * receiver it paid (a short address that never equals the fee address's text).
     */
    fun isPlatformFeeAddress(address: String): Boolean =
        address == PLATFORM_FEE_ADDRESS ||
            address in PREVIOUS_PLATFORM_FEE_ADDRESSES ||
            ZcashReceivers.within(address, PLATFORM_FEE_ADDRESS) ||
            PREVIOUS_PLATFORM_FEE_ADDRESSES.any { ZcashReceivers.within(address, it) }

    /**
     * Memo prefix for AI credit top-up payments (must stay in sync with the backend watcher's
     * MEMO_PREFIX). A top-up is a wallet payment to the AI-credit address, NOT a chat message, so
     * convertToConversations must skip it — otherwise the top-up tx surfaces as a bogus conversation
     * in the chat list (it still appears in the wallet's Transaction History + the AI top-up history).
     */
    const val AI_TOPUP_MEMO_PREFIX = "ai-topup:"

    // ==========================================
    // ADDRESS SHAPE (money-safety)
    // ==========================================

    /**
     * Every real Zcash address form (t1/t3 base58, zs1/u1/tex1 bech32/bech32m) is BARE ALPHANUMERIC.
     *
     * This exists because addresses reach money-handling code from attacker-influenceable sources — the
     * GROUP_INVITE "inviter" JSON adopted UNAUTHENTICATED on the on-chain path, scanned QRs, pasted
     * contact codes — and [CreateChunkedMessageProposalUseCase.buildZip321Uri] interpolates the
     * destination RAW into a ZIP-321 URI that is then submitted with allowMultiPayment=true and NO
     * review screen. A metacharacter payload such as "&address.5=<attacker>&amount.5=5.0" would append
     * a hidden payment output and redirect funds.
     *
     * NOTE: the `isValidZcashAddress` helpers scattered in the UI check only PREFIX + LENGTH, so they
     * happily accept `"u1" + 110 chars + "&address.5=..."`. They are unfit for this purpose — use this.
     */
    val BARE_ADDRESS_CHARSET = Regex("^[A-Za-z0-9]{20,512}$")

    /** True iff [address] is shaped like a bare Zcash address (see [BARE_ADDRESS_CHARSET]). */
    fun isBareAddressShaped(address: String): Boolean = BARE_ADDRESS_CHARSET.matches(address)
}
