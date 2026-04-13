package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import co.electriccoin.zcash.ui.screen.chat.crypto.HKDF
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric ratchet for E2E-encrypted conversations. Stage B of the 2026-04-12
 * deterministic-root design. See docs/superpowers/specs/2026-04-12-e2e-ratchet-deterministic-design.md.
 *
 * @param rootKey 32-byte root key, derived from `HKDF(ECDH_secret || optional_psk,
 *   salt="ZCHAT_RATCHET_ROOT_V1", info=sha256(kex_txid || kexack_txid))`.
 *   Both parties can derive this deterministically from (seed, peer pubkey, on-chain txids).
 * @param convId 8-character conversation identifier shared with ZMSG v4.
 * @param isLower true if this party's compressed secp256r1 public key is lexicographically
 *   lower than the peer's. This determines which directional chain is used for sending.
 * @param store Persistence backend for [RatchetConversationState]. In tests, use
 *   [InMemoryRatchetStateStore].
 */
class E2ERatchet(
    private val rootKey: ByteArray,
    private val convId: String,
    private val isLower: Boolean,
    private val store: RatchetStateStore,
) {
    private val myDirection: Byte = if (isLower) 0x00 else 0x01

    /**
     * Encrypt outgoing [plaintext]. Uses the sender's directional chain. Counter advances
     * per call and is persisted in [store]. Returns a [Ciphertext] tagged with direction
     * and counter.
     */
    suspend fun encrypt(plaintext: ByteArray): Ciphertext {
        val mutex = store.mutexFor(convId)
        return mutex.withLock {
            val state = loadOrInit()
            val counter = counterFor(state, myDirection)
            val messageKey = deriveMessageKey(myDirection, counter)
            val nonce = counterNonce(counter)
            val aad = aadFor(myDirection, counter, convId)
            val cipherBytes = aesGcmEncrypt(messageKey, nonce, aad, plaintext)
            store.save(advanceSendCounter(state))
            Ciphertext(myDirection, counter, cipherBytes)
        }
    }

    /**
     * Decrypt incoming [ciphertext]. Uses the peer's directional chain. Returns the
     * recovered plaintext.
     *
     * Throws [ReplayDetectedException] if the (direction, counter) pair has already been
     * consumed. Throws on AEAD auth failure if the ciphertext has been tampered with.
     */
    suspend fun decrypt(ciphertext: Ciphertext): ByteArray {
        val isOwnOutgoing = ciphertext.direction == myDirection
        val mutex = store.mutexFor(convId)
        return mutex.withLock {
            val state = loadOrInit()

            // Replay + DoS checks only for INCOMING messages (peer direction).
            // Own outgoing messages are deterministic — re-decrypting on re-scan
            // is expected and must not trigger replay detection.
            if (!isOwnOutgoing) {
                val seen = seenCountersFor(state, ciphertext.direction)
                if (ciphertext.counter in seen) {
                    throw ReplayDetectedException(ciphertext.direction, ciphertext.counter)
                }
                val maxSeen = seen.maxOrNull() ?: 0L
                if (ciphertext.counter > maxSeen + MAX_SKIP) {
                    throw CounterOutOfRangeException(
                        direction = ciphertext.direction,
                        counter = ciphertext.counter,
                        maxAllowed = maxSeen + MAX_SKIP,
                    )
                }
            }

            val messageKey = deriveMessageKey(ciphertext.direction, ciphertext.counter)
            val nonce = counterNonce(ciphertext.counter)
            val aad = aadFor(ciphertext.direction, ciphertext.counter, convId)
            val plaintext = aesGcmDecrypt(messageKey, nonce, aad, ciphertext.bytes)

            // Mark counter as seen ONLY for incoming messages, AFTER successful auth.
            if (!isOwnOutgoing) {
                store.save(markSeen(state, ciphertext.direction, ciphertext.counter))
            }
            plaintext
        }
    }

    private fun seenCountersFor(state: RatchetConversationState, direction: Byte): Set<Long> =
        if (direction == DIRECTION_A2B) state.seenCountersA2B else state.seenCountersB2A

    private fun markSeen(
        state: RatchetConversationState,
        direction: Byte,
        counter: Long,
    ): RatchetConversationState {
        val updated = if (direction == DIRECTION_A2B) {
            state.copy(seenCountersA2B = pruneSeenSet(state.seenCountersA2B + counter))
        } else {
            state.copy(seenCountersB2A = pruneSeenSet(state.seenCountersB2A + counter))
        }
        return updated
    }

    /**
     * Prune the seen-counter set to prevent unbounded growth in persistent storage.
     * Keeps only the most recent [SEEN_SET_MAX_SIZE] entries. Older counters below
     * (maxSeen - SEEN_SET_MAX_SIZE) are implicitly "seen" since blockchain processing
     * is monotonic; any replay with a very old counter would fail MAX_SKIP bounds anyway.
     */
    private fun pruneSeenSet(seen: Set<Long>): Set<Long> {
        if (seen.size <= SEEN_SET_MAX_SIZE) return seen
        val threshold = (seen.maxOrNull() ?: 0L) - SEEN_SET_MAX_SIZE
        return seen.filter { it > threshold }.toSet()
    }

    private suspend fun loadOrInit(): RatchetConversationState =
        store.load(convId) ?: RatchetConversationState(
            convId = convId,
            nextCounterA2B = 0L,
            nextCounterB2A = 0L,
            seenCountersA2B = emptySet(),
            seenCountersB2A = emptySet(),
        )

    private fun counterFor(state: RatchetConversationState, direction: Byte): Long =
        if (direction == DIRECTION_A2B) state.nextCounterA2B else state.nextCounterB2A

    private fun advanceSendCounter(state: RatchetConversationState): RatchetConversationState =
        if (myDirection == DIRECTION_A2B) {
            state.copy(nextCounterA2B = state.nextCounterA2B + 1)
        } else {
            state.copy(nextCounterB2A = state.nextCounterB2A + 1)
        }

    /**
     * Walk the chain from `chain_key_0` to `chain_key_counter`, then derive the message
     * key via `HMAC(chain_key_counter, 0x01)`. Deterministic — both parties reach the same
     * key for the same (direction, counter) given the same root.
     */
    private fun deriveMessageKey(direction: Byte, counter: Long): ByteArray {
        require(counter >= 0L) { "counter must be non-negative, was $counter" }
        var chainKey = deriveChainKey0(direction)
        var step = 0L
        while (step < counter) {
            chainKey = hmacSha256(chainKey, byteArrayOf(CHAIN_STEP_BYTE))
            step++
        }
        return hmacSha256(chainKey, byteArrayOf(MESSAGE_KEY_BYTE))
    }

    private fun deriveChainKey0(direction: Byte): ByteArray {
        val info =
            if (direction == DIRECTION_A2B) CHAIN_INFO_A2B else CHAIN_INFO_B2A
        return HKDF.deriveKey(
            ikm = rootKey,
            salt = null,
            info = info.toByteArray(Charsets.UTF_8),
            length = KEY_LENGTH,
        )
    }

    private fun counterNonce(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH)
        val encoded = ByteBuffer.allocate(8).putLong(counter).array()
        System.arraycopy(encoded, 0, nonce, 4, 8)
        return nonce
    }

    private fun aadFor(direction: Byte, counter: Long, convId: String): ByteArray {
        val counterBuf = ByteBuffer.allocate(8).putLong(counter).array()
        val convIdBytes = convId.toByteArray(Charsets.UTF_8)
        return byteArrayOf(direction) + counterBuf + convIdBytes
    }

    private fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    companion object {
        private const val KEY_LENGTH = 32
        private const val NONCE_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
        private const val CHAIN_INFO_A2B = "ZCHAT_CHAIN_A2B_V1"
        private const val CHAIN_INFO_B2A = "ZCHAT_CHAIN_B2A_V1"
        private const val MESSAGE_KEY_BYTE: Byte = 0x01
        private const val CHAIN_STEP_BYTE: Byte = 0x02
        private const val DIRECTION_A2B: Byte = 0x00
        private val ROOT_SALT = "ZCHAT_RATCHET_ROOT_V1".toByteArray(Charsets.UTF_8)

        /**
         * Maximum number of counter steps a receiver will walk ahead of its current
         * max-seen value. Caps DoS work at ~1000 HMAC operations per bogus message.
         * Legitimate peers are unlikely to send more than 1000 messages without any
         * being decrypted in between.
         */
        const val MAX_SKIP: Long = 1000L
        private const val SEEN_SET_MAX_SIZE = 2000

        /**
         * Derive the deterministic ratchet root for a conversation.
         *
         * ```
         * root_key = HKDF-SHA256(
         *     ikm   = ecdh_shared_secret || (psk ?: empty),
         *     salt  = "ZCHAT_RATCHET_ROOT_V1",
         *     info  = sha256(kex_txid || kexack_txid),
         *     length = 32,
         * )
         * ```
         *
         * Both parties can reach the same root by feeding the same ECDH secret (computed
         * locally from their own private key and the peer's public key from the KEX memo)
         * together with the two on-chain txids that are visible to anyone scanning the
         * blockchain with the recipient's viewing key. Optional [psk] is mixed in to
         * support the Quantum Shield out-of-band pre-shared key.
         */
        fun deriveRatchetRoot(
            ecdhSharedSecret: ByteArray,
            psk: ByteArray?,
            kexTxid: ByteArray,
            kexAckTxid: ByteArray,
        ): ByteArray {
            val ikm = if (psk != null) ecdhSharedSecret + psk else ecdhSharedSecret
            val kexContext = sha256(kexTxid + kexAckTxid)
            return HKDF.deriveKey(
                ikm = ikm,
                salt = ROOT_SALT,
                info = kexContext,
                length = KEY_LENGTH,
            )
        }

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)
    }
}
