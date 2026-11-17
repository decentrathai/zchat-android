package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Round 8 P3 (PHONE_TRIAGE F1) — which known contact a sender hash names, when a memo carries only the hash.
 *
 * A Shielded payment request (ZREQ|) or time-locked message (ZTL|) names its sender only by a hash of the sender's
 * address (ZMSGProtocol.generateAddressHash, 8 bytes; older builds sent the 6-byte legacy hash). The router matches it
 * against the addresses it already knows. It routes only on exactly one match and never guesses between two.
 */
object SenderHashMatch {
    /** The hashes an address can appear under on the wire: the current 8-byte hash and the legacy 6-byte one. */
    fun wireHashes(address: String): List<String> =
        listOf(ZMSGProtocol.generateAddressHash(address), ZMSGProtocol.generateLegacyAddressHash(address))

    /**
     * The single distinct address among [candidates] whose [hashesOf] contains [senderHash]; null when none does or
     * when two or more do.
     */
    fun unique(
        senderHash: String,
        candidates: Iterable<String>,
        hashesOf: (String) -> List<String> = ::wireHashes,
    ): String? =
        candidates
            .toSet()
            .filter { senderHash in hashesOf(it) }
            .singleOrNull()
}
