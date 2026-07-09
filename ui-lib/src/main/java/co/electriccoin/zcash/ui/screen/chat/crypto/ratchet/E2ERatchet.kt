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

    // Session-scoped seen-counter sets. NOT persisted across restarts.
    // Persisting them would break re-scan: on restart, all previously-decrypted
    // incoming messages would trigger ReplayDetectedException and show as
    // E2E1: blobs. Instead, replay detection is per-session only.
    // Send counters (nextCounterA2B/B2A) ARE persisted to prevent GCM nonce reuse.
    private val sessionSeenA2B = mutableSetOf<Long>()
    private val sessionSeenB2A = mutableSetOf<Long>()

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
            require(counter < MAX_SEND_COUNTER) {
                "Send counter $counter exceeds MAX_SEND_COUNTER — re-KEX required to reset"
            }
            val messageKey = deriveMessageKey(myDirection, counter)
            val nonce = counterNonce(myDirection, counter)
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
     * consumed, or [CounterOutOfRangeException] if the counter is implausibly far ahead — a
     * DoS guard that rejects forged counters before the O(counter) chain walk (incoming:
     * beyond MAX_SKIP; own-outgoing: at/above MAX_SEND_COUNTER). Throws on AEAD auth failure
     * if the ciphertext has been tampered with.
     */
    suspend fun decrypt(ciphertext: Ciphertext): ByteArray {
        val isOwnOutgoing = ciphertext.direction == myDirection
        val mutex = store.mutexFor(convId)
        return mutex.withLock {
            // Persisted state for an INCOMING message, held so we can seed the DoS window from the
            // receive high-water mark AND persist an advanced one after a successful decrypt. Read
            // once, under the per-conversation mutex, so the load→check→save read-modify-write is
            // atomic and can't race encrypt()/another decrypt() on the same conversation. Stays null
            // on the own-outgoing path, which needs no state and skips the load entirely (keeps
            // blockchain re-scan of our OWN messages I/O-free).
            var incomingState: RatchetConversationState? = null

            // Replay + DoS checks only for INCOMING messages (peer direction).
            // Own outgoing messages are deterministic — re-decrypting on re-scan
            // is expected and must not trigger replay detection.
            if (!isOwnOutgoing) {
                val sessionSeen = sessionSeenFor(ciphertext.direction)
                if (ciphertext.counter in sessionSeen) {
                    throw ReplayDetectedException(ciphertext.direction, ciphertext.counter)
                }
                val state = loadOrInit()
                incomingState = state
                // Anchor the DoS window on the LARGER of what we've decrypted THIS session and the
                // PERSISTED receive high-water mark. The session seen-set is empty right after an app
                // restart — previously-decrypted messages are served from ChatViewModel's L2 plaintext
                // cache and never re-enter the ratchet — so without the persisted anchor a long-lived
                // chat's legitimate next counter (easily > MAX_SKIP) would be rejected as
                // CounterOutOfRange on every retry: a PERMANENT conversation wedge that nudged the user
                // toward a ZEC-spending re-KEX. Seeding the base from persistence keeps the tight,
                // ALWAYS-enforced maxSeen+MAX_SKIP window (base 0 for a genuinely new conversation), so
                // an attacker's forged first-of-session counter still can't drive more than MAX_SKIP
                // pre-AEAD HMAC steps beyond the established position — curing the wedge WITHOUT
                // weakening DoS protection.
                val windowBase = maxOf(sessionSeen.maxOrNull() ?: 0L, highWaterFor(state, ciphertext.direction))
                if (ciphertext.counter > windowBase + MAX_SKIP) {
                    throw CounterOutOfRangeException(
                        direction = ciphertext.direction,
                        counter = ciphertext.counter,
                        maxAllowed = windowBase + MAX_SKIP,
                    )
                }
            } else {
                // DoS guard for the OWN-outgoing path. Without this, a forged memo that
                // carries OUR own direction byte plus an arbitrarily large counter skips
                // the incoming maxSeen+MAX_SKIP window above and drops straight into the
                // O(counter) chain walk in deriveMessageKey() — an effectively unbounded
                // HMAC loop that hangs every re-scan/restore (the memo is on-chain).
                //
                // We deliberately do NOT bound against the persisted send counter: a
                // restored device re-derives history from a reset (0) send counter, so
                // legitimate own messages can carry any counter we ever emitted. The safe
                // bound is the absolute send ceiling — encrypt() rejects counters >=
                // MAX_SEND_COUNTER, so any such own-direction counter was never emitted by
                // us and is rejected here, before the chain walk.
                if (ciphertext.counter >= MAX_SEND_COUNTER) {
                    throw CounterOutOfRangeException(
                        direction = ciphertext.direction,
                        counter = ciphertext.counter,
                        maxAllowed = MAX_SEND_COUNTER - 1,
                    )
                }
            }

            val messageKey = deriveMessageKey(ciphertext.direction, ciphertext.counter)
            val nonce = counterNonce(ciphertext.direction, ciphertext.counter)
            val aad = aadFor(ciphertext.direction, ciphertext.counter, convId)
            val plaintext = aesGcmDecrypt(messageKey, nonce, aad, ciphertext.bytes)

            // Everything below runs ONLY after aesGcmDecrypt() SUCCEEDS. A message that fails the GCM
            // tag must NOT consume its counter or advance the high-water (correct ratchet/Signal
            // semantics) — otherwise an attacker who can route garbage to counter N would permanently
            // block the REAL message at N. The cost of re-walking the chain on a retried bad ciphertext
            // is already bounded by the MAX_SKIP window check above, so do NOT "harden" this by marking
            // failed decrypts as seen. (incomingState is non-null iff this was an incoming message.)
            incomingState?.let { loaded ->
                // Persist an advanced high-water mark BEFORE marking the counter seen in-session, and
                // only when the counter actually ADVANCES the mark. Persisting first means a failed
                // commit (commit()==false → IOException) leaves the counter un-consumed and the message
                // re-deliverable on retry, mirroring encrypt(), which only "commits" a counter once its
                // advanced state reaches disk. Gating on a strict advance keeps the mark monotonic (never
                // rewound by a backfilled/out-of-order counter) and avoids a redundant fsync per message.
                if (ciphertext.counter > highWaterFor(loaded, ciphertext.direction)) {
                    store.save(withHighWater(loaded, ciphertext.direction, ciphertext.counter))
                }
                // Within-session replay guard (NOT persisted) — prevents replay this session while still
                // allowing re-scan to re-decrypt after a restart.
                sessionSeenFor(ciphertext.direction).add(ciphertext.counter)
            }
            plaintext
        }
    }

    private fun sessionSeenFor(direction: Byte): MutableSet<Long> =
        if (direction == DIRECTION_A2B) sessionSeenA2B else sessionSeenB2A

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

    private fun highWaterFor(state: RatchetConversationState, direction: Byte): Long =
        if (direction == DIRECTION_A2B) state.maxSeenA2B else state.maxSeenB2A

    /**
     * Returns a copy of [state] with the receive high-water mark for [direction] raised to [counter].
     * Callers MUST only invoke this when [counter] strictly exceeds the current mark, so the mark stays
     * monotonic non-decreasing (never rewound by a backfilled/out-of-order counter). Send counters and
     * the opposite direction's mark are left untouched.
     */
    private fun withHighWater(
        state: RatchetConversationState,
        direction: Byte,
        counter: Long,
    ): RatchetConversationState =
        if (direction == DIRECTION_A2B) {
            state.copy(maxSeenA2B = counter)
        } else {
            state.copy(maxSeenB2A = counter)
        }

    /**
     * Walk the chain from `chain_key_0` to `chain_key_counter`, then derive the message
     * key via `HMAC(chain_key_counter, 0x01)`. Deterministic — both parties reach the same
     * key for the same (direction, counter) given the same root.
     */
    private fun deriveMessageKey(direction: Byte, counter: Long): ByteArray {
        require(counter >= 0L) { "counter must be non-negative, was $counter" }
        // Defense-in-depth hard cap: callers (encrypt/decrypt) already bound the counter,
        // but never let an unguarded path drive the chain walk past the send ceiling.
        require(counter < MAX_SEND_COUNTER) {
            "counter $counter exceeds MAX_SEND_COUNTER ($MAX_SEND_COUNTER) — refusing unbounded chain walk"
        }
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

    private fun counterNonce(direction: Byte, counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH)
        // Byte 0: direction (defense-in-depth — ensures nonce uniqueness across
        // chains even if a bug causes key collision between A2B and B2A).
        nonce[0] = direction
        // Bytes 1-3: reserved (zero)
        // Bytes 4-11: counter as big-endian u64
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

        /**
         * Sender-side counter cap. Prevents runaway counter growth that would cause
         * O(n) chain-walk performance degradation and potential OOM.
         */
        private const val MAX_SEND_COUNTER: Long = 1_000_000L

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

        /**
         * Canonical, ORDER-INDEPENDENT root material for a KEX or KEXACK txid set (B1/B2 convergence).
         * Both devices observe the same on-chain txs, so sorting the set + joining yields byte-identical
         * bytes on each side. [legacyScalar] (the pre-set single txid) is folded in for backward-compat:
         * a single-KEX chat's material is byte-identical to the old `scalar.toByteArray()`, and empty +
         * null → ByteArray(0), matching the old `?: ByteArray(0)` fallback — so existing chats don't break.
         * Callers feed the two results as [deriveRatchetRoot]'s kexTxid / kexAckTxid. Keep this the SINGLE
         * source of the derivation so tests exercise production code, not a drifting mirror.
         */
        fun canonicalTxidMaterial(txids: Set<String>, legacyScalar: String?): ByteArray =
            (txids + listOfNotNull(legacyScalar)).toSortedSet().joinToString("|").toByteArray(Charsets.UTF_8)
    }
}
