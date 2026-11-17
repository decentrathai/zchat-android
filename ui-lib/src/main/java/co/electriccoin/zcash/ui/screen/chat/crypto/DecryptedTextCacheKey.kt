package co.electriccoin.zcash.ui.screen.chat.crypto

/**
 * Cache-key derivation for the decrypted-plaintext (L2) cache in ZchatPreferences.
 *
 * The key is the lowercase SHA-256 hex of the UTF-8 bytes of `"<len(convId)>:<convId>:<wire>"`, where
 * the wire ciphertext is the message's immutable `E2E1:` envelope. The key hashes CIPHERTEXT, never
 * plaintext.
 *
 * The conversation id is part of the key ON PURPOSE. [legacyOf] — the original derivation — hashed
 * the ciphertext alone, which made a cache hit independent of WHO the message was with: the same
 * envelope replayed into a different conversation resolved to the same row and the cache handed back
 * the plaintext it had learned elsewhere, before any conversation binding was checked. Binding the
 * key to the conversation closes that oracle; the callers additionally only consult the cache after
 * a non-null conversation id is established.
 *
 * ONE shared definition, used by BOTH:
 *  - the decrypt path (ChatViewModel.tryDecryptMessage — seeds the cache on first successful
 *    receive-side decrypt), and
 *  - the send-time self-cache (ChatViewModel.cacheOwnOutgoingPlaintext — seeds the cache the
 *    moment the outgoing ciphertext is produced),
 * so a sender's own row is found by the exact key a later rescan derives. Any drift between the
 * two derivations would silently orphan every self-cached row.
 */
object DecryptedTextCacheKey {
    /**
     * Conversation-bound key. Use this everywhere.
     *
     * The conversation id is length-prefixed so the boundary between the two fields is unambiguous:
     * a plain `"$convId:$wire"` join would hash `("a", "b:c")` and `("a:b", "c")` identically, which
     * is exactly the kind of collision this key exists to prevent.
     */
    fun of(convId: String, wireCiphertext: String): String =
        sha256Hex("${convId.length}:$convId:$wireCiphertext")

    /**
     * The pre-binding key: sha256(wire) with no conversation.
     *
     * Retained ONLY so rows written by earlier builds can be migrated on first use. Re-keying
     * without a migration would not merely lose a cache entry — the symmetric ratchet refuses to
     * re-decrypt an already-consumed counter, so an orphaned row means a message the user could
     * read yesterday becomes permanently unreadable. Callers must consult this only after the
     * conversation id is known, and must rewrite the hit under [of] and delete the legacy row, so
     * each row is upgraded exactly once and the unbound key stops being consulted for it.
     */
    fun legacyOf(wireCiphertext: String): String = sha256Hex(wireCiphertext)

    /**
     * ZC-1792 (W-142, P-A): the key of a message opened with an earlier root (OldRootFallback), bound to its ROW (the
     * transaction [txid]) as well: a copy of the same envelope mined later is a different row and is never answered from
     * it. The `row:` prefix keeps it apart from [of], whose input starts with a digit.
     */
    fun ofRow(
        convId: String,
        txid: String,
        wireCiphertext: String,
    ): String = sha256Hex("row:${convId.length}:$convId:${txid.length}:$txid:$wireCiphertext")

    private fun sha256Hex(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
