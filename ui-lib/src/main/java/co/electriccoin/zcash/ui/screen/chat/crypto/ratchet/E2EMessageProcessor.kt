package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

/**
 * High-level E2E encryption/decryption for chat messages. This is the class that
 * ChatViewModel calls — it wraps [E2ERatchet] and [CiphertextWireFormat] into a
 * single `String → String` interface that operates on memo content.
 *
 * **Encrypt outgoing:** plaintext → ratchet encrypt → wire format → `"E2E1:..."` string
 * **Decrypt incoming:** `"E2E1:..."` string → wire format parse → ratchet decrypt → plaintext
 *
 * Non-E2E content (no `E2E1:` or `E2E:` prefix) passes through unchanged on decrypt.
 *
 * @param rootKey 32-byte ratchet root key, derived from ECDH shared secret + optional PSK.
 * @param convId 8-character ZMSG v4 conversation ID.
 * @param isLower true if this party's pubkey is lexicographically lower than the peer's.
 * @param store Persistence for ratchet state (counters, seen-counter sets).
 */
class E2EMessageProcessor(
    rootKey: ByteArray,
    convId: String,
    isLower: Boolean,
    store: RatchetStateStore,
) {
    private val ratchet = E2ERatchet(rootKey, convId, isLower, store)

    /**
     * Encrypt outgoing message. Returns an `E2E1:...` wire string suitable for embedding
     * in a ZMSG v4 memo payload (replaces the plaintext message content).
     */
    suspend fun encryptOutgoing(plaintext: String): String {
        val ct = ratchet.encrypt(plaintext.toByteArray(Charsets.UTF_8))
        return CiphertextWireFormat.serialize(ct)
    }

    /**
     * Decrypt incoming memo content. If the content starts with `E2E1:`, it is parsed and
     * decrypted via the ratchet. Otherwise it is returned unchanged (plaintext message or
     * unrecognized format).
     *
     * Throws [ReplayDetectedException] on counter replay.
     * Throws [CounterOutOfRangeException] if counter exceeds MAX_SKIP window.
     * Throws [javax.crypto.AEADBadTagException] on tampered ciphertext.
     */
    suspend fun decryptIncoming(wireContent: String): String {
        if (!CiphertextWireFormat.isRatcheted(wireContent)) return wireContent

        val ct = CiphertextWireFormat.parse(wireContent) ?: return wireContent
        val plainBytes = ratchet.decrypt(ct)
        return String(plainBytes, Charsets.UTF_8)
    }
}
