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
 * @param legacyFloorKeys every conversation id this device has used with the peer. Before R2-4 the counter
 *   floors (and the ratchet state) were kept per conversation id only; their maximum is carried into this
 *   root's floor (see [floorKey]), so a counter used under any of them is never used again.
 * @param sendConvId the conversation id this device sends to the peer under (its current one). What an older
 *   version kept under it is this root's and is carried for good at once. What it kept under any OTHER id may be
 *   an older root's (a Reset Encryption before R2-4 cleared the current id only): it is carried too, so no counter
 *   is ever reused, but only until [settleLegacyJump] or [adoptLegacyJump] decides (R3-3).
 */
class E2ERatchet(
    private val rootKey: ByteArray,
    private val convId: String,
    private val isLower: Boolean,
    private val store: RatchetStateStore,
    private val legacyFloorKeys: Set<String> = emptySet(),
    private val sendConvId: String = convId,
) {
    private val myDirection: Byte = if (isLower) 0x00 else 0x01

    /**
     * The key this ratchet's durable counter floors are kept under in [store] (see
     * [RatchetStateStore.loadCounterFloor]): a hash of the ROOT, never the raw key.
     *
     * R2-4 (a): message key and nonce depend only on (root, direction, counter); the conversation id is only
     * part of the AAD, and one peer can have two conversation ids on this device (ours and the one its KEX
     * carried, or a new one after a restore). A floor kept per conversation id let a ratchet under the second id
     * start at counter 0 under the same root and reuse an AES-GCM (key, nonce) pair. Keyed by the root, every
     * conversation id of the root shares one floor, and a new root (Reset Encryption) starts fresh.
     */
    val floorKey: String = floorKeyFor(rootKey)

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
    suspend fun encrypt(plaintext: ByteArray): Ciphertext =
        withRatchetLocks {
            val state = loadOrInit(forSend = true)
            val counter = counterFor(state, myDirection)
            require(counter < MAX_SEND_COUNTER) {
                "Send counter $counter exceeds MAX_SEND_COUNTER — re-KEX required to reset"
            }
            val messageKey = deriveMessageKey(myDirection, counter)
            val nonce = counterNonce(myDirection, counter)
            val aad = aadFor(myDirection, counter, convId)
            val cipherBytes = aesGcmEncrypt(messageKey, nonce, aad, plaintext)
            store.save(advanceSendCounter(state))
            // D8 — durable send-counter floor, in storage that SURVIVES a wipe of the main
            // (encrypted) ratchet state. Key+nonce are deterministic functions of
            // (root, direction, counter), and the root is re-derived from material that survives
            // such a wipe — so a state wipe that rolled this counter back to 0 would REUSE an
            // already-burned AES-GCM (key, nonce) pair. loadOrInit() clamps the loaded state up to
            // this floor, making rollback impossible. Both writes are synchronous commits: if
            // either fails, encrypt() throws and the ciphertext never leaves the device.
            raiseFloor(myDirection, counter + 1)
            Ciphertext(myDirection, counter, cipherBytes)
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
        return withRatchetLocks {
            // Persisted state for an INCOMING message, held so we can seed the DoS window from the
            // receive high-water mark AND persist an advanced one after a successful decrypt. Read
            // once, under the per-conversation mutex, so the load→check→save read-modify-write is
            // atomic and can't race encrypt()/another decrypt() on the same conversation. Stays null
            // on the own-outgoing path, which needs no state and skips the load entirely (the only
            // own-outgoing I/O is the monotonic R5 send-counter floor raise after a SUCCESSFUL
            // decrypt, below — a no-op once the floor covers the counter).
            var incomingState: RatchetConversationState? = null

            // Replay + DoS checks only for INCOMING messages (peer direction).
            // Own outgoing messages are deterministic — re-decrypting on re-scan
            // is expected and must not trigger replay detection.
            if (!isOwnOutgoing) {
                val sessionSeen = sessionSeenFor(ciphertext.direction)
                if (ciphertext.counter in sessionSeen) {
                    throw ReplayDetectedException(ciphertext.direction, ciphertext.counter)
                }
                val state = loadOrInit(forSend = false)
                incomingState = state
                // Anchor the DoS window on the PERSISTED receive high-water mark, which is authoritative.
                // Every counter added to the session seen-set (below) has already advanced this persisted
                // mark, and loadOrInit() re-reads that persisted state on each call, so the mark always
                // dominates the session set's max — making a maxOf(sessionSeen.maxOrNull(), ...) scan
                // redundant. Reading the mark directly is O(1) and avoids an O(n) scan of the seen-set per
                // receive. The mark also survives an app restart — where the session seen-set is empty
                // because previously-decrypted messages are served from ChatViewModel's L2 plaintext cache
                // and never re-enter the ratchet — so without it a long-lived chat's legitimate next
                // counter (easily > MAX_SKIP) would be rejected as CounterOutOfRange on every retry: a
                // PERMANENT conversation wedge that nudged the user toward a ZEC-spending re-KEX. The mark
                // keeps the tight, ALWAYS-enforced maxSeen+MAX_SKIP window (base 0 for a genuinely new
                // conversation), so an attacker's forged first-of-session counter still can't drive more
                // than MAX_SKIP pre-AEAD HMAC steps beyond the established position — curing the wedge
                // WITHOUT weakening DoS protection.
                val windowBase = highWaterFor(state, ciphertext.direction)
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
            if (isOwnOutgoing) {
                // R5 (D1-recovery, Stage 3) — restored-device send-counter floor: a seed restore
                // re-derives a BYTE-IDENTICAL root on a device whose ratchet state AND floor store
                // start EMPTY, so without this the first post-restore send would restart at counter 0
                // and REUSE an AES-GCM (key, nonce) pair already burned by the pre-wipe device. Every
                // successful own-direction decrypt during the blockchain rescan is proof that
                // (root, myDirection, counter) was already consumed — floor the send counter at
                // counter + 1 in the SAME wipe-surviving store D8 uses, and applyCounterFloors()
                // clamps the next encrypt() up to it. Runs only after the AEAD tag verifies (a forged
                // own-direction memo can't inflate the floor), and saveCounterFloor is monotonic, so
                // partial/out-of-order rescans anchor on the HIGHEST observed counter.
                raiseFloor(myDirection, ciphertext.counter + 1)
            }
            incomingState?.let { loaded ->
                // Persist an advanced high-water mark BEFORE marking the counter seen in-session, and
                // only when the counter actually ADVANCES the mark. Persisting first means a failed
                // commit (commit()==false → IOException) leaves the counter un-consumed and the message
                // re-deliverable on retry, mirroring encrypt(), which only "commits" a counter once its
                // advanced state reaches disk. Gating on a strict advance keeps the mark monotonic (never
                // rewound by a backfilled/out-of-order counter) and avoids a redundant fsync per message.
                if (ciphertext.counter > highWaterFor(loaded, ciphertext.direction)) {
                    store.save(withHighWater(loaded, ciphertext.direction, ciphertext.counter))
                    // D8 — mirror the advanced high-water into the wipe-surviving floor store, so
                    // the replay/DoS window anchor is restored (not reset to 0) after a localized
                    // wipe of the main ratchet state. See applyCounterFloors().
                    raiseFloor(ciphertext.direction, ciphertext.counter)
                }
                // Within-session replay guard (NOT persisted) — prevents replay this session while still
                // allowing re-scan to re-decrypt after a restart.
                sessionSeenFor(ciphertext.direction).add(ciphertext.counter)
            }
            plaintext
        }
    }

    /**
     * R3-3 (1): the conversation ids other than [sendConvId] whose older send counters are above this root's own
     * floor and not settled yet. Until they are, every send carries them (never a reused counter) and makes them
     * this root's for good, so the app holds sends back until the history pass has settled them.
     */
    suspend fun unsettledLegacyJumps(): Set<String> =
        if ((legacyFloorKeys + convId).none { it != sendConvId }) {
            emptySet() // no other conversation id: nothing to settle (and no storage read)
        } else {
            withRatchetLocks { sendFloor(forSend = false).unsettled.keys }
        }

    /**
     * R3-3 (1): settle what an older version kept under [legacyConvId] from [ownEnvelopes], our OWN messages under
     * that id (from our own sent transactions only, never a received memo: a forged one must not count).
     *
     * A message and nonce depend only on (root, direction, counter), and the conversation id is part of the
     * authenticated data. A counter this root used under [legacyConvId] and sent is one of our own messages there
     * that authenticates under this root and that id. If one does (at or above this root's floor), the carried
     * counters are kept for good. If none does, they are an older root's (for example the one a Reset before R2-4
     * replaced) and stop counting for this root, which then continues from its own floor, inside the peer's window.
     *
     * The caller passes every one of our own messages under [legacyConvId] from a pass that read all of them; in
     * doubt it calls [adoptLegacyJump] instead.
     */
    suspend fun settleLegacyJump(
        legacyConvId: String,
        ownEnvelopes: Collection<Ciphertext>,
    ) = withRatchetLocks {
        val floor = sendFloor(forSend = false)
        val carried = floor.unsettled[legacyConvId] ?: return@withRatchetLocks
        val usedByThisRoot =
            authenticCounters(
                legacyConvId,
                ownEnvelopes
                    .filter { it.direction == myDirection && it.counter >= floor.settled && it.counter < MAX_SEND_COUNTER }
                    .sortedBy { it.counter },
            )
        if (usedByThisRoot.isEmpty()) {
            store.saveCounterFloor(legacyMarkKey(FOREIGN_MARK, legacyConvId), myDirection, carried)
        } else {
            raiseFloor(myDirection, maxOf(carried, usedByThisRoot.max() + 1))
        }
    }

    /** R3-3 (1): keep what an older version kept under [legacyConvId] as this root's for good (never a reused counter). */
    suspend fun adoptLegacyJump(legacyConvId: String) =
        withRatchetLocks {
            sendFloor(forSend = false).unsettled[legacyConvId]?.let { raiseFloor(myDirection, it) }
            Unit
        }

    private fun sessionSeenFor(direction: Byte): MutableSet<Long> =
        if (direction == DIRECTION_A2B) sessionSeenA2B else sessionSeenB2A

    /**
     * Run [block] holding the ROOT lock, then the conversation lock — in that order everywhere, so two ratchets
     * can never deadlock. The root lock serialises every ratchet of this root, whatever its conversation id,
     * around the floor they share (two ids sending at once would otherwise read the same floor and both use it).
     */
    private suspend fun <T> withRatchetLocks(block: suspend () -> T): T =
        store.mutexFor(floorKey).withLock { store.mutexFor(convId).withLock { block() } }

    /**
     * Raise the durable floor for [direction] to [value] under this root ([floorKey]), and mirror it under the
     * conversation id, where an app version from before R2-4 reads it (a downgrade must not reuse a counter).
     * Both are synchronous, monotonic commits that throw on failure.
     */
    private suspend fun raiseFloor(direction: Byte, value: Long) {
        store.saveCounterFloor(floorKey, direction, value)
        store.saveCounterFloor(convId, direction, value)
    }

    /**
     * This root's durable floor for [direction], after carrying in (monotonic maximum, persisted) every floor and
     * ratchet state an older version kept per conversation id for the peer ([legacyFloorKeys] and [convId]).
     * Re-read on every load, so a floor raised later under a conversation id is never missed. For the send
     * direction the value is the next usable counter; for the peer's direction the receive high-water mark.
     *
     * An unreadable older state of ANOTHER conversation id fails the send closed (its counters are unknown), and
     * is skipped on receive (the receive window only needs a lower bound).
     */
    private suspend fun rootFloor(direction: Byte, forSend: Boolean): Long {
        var carried = 0L
        for (key in legacyFloorKeys + convId) carried = maxOf(carried, legacyValue(key, direction, forSend))
        return persistRootFloor(direction, carried)
    }

    /** Raise this root's floor for [direction] to [value] when it is higher; returns the floor. */
    private suspend fun persistRootFloor(
        direction: Byte,
        value: Long,
    ): Long {
        val current = store.loadCounterFloor(floorKey, direction)
        if (value <= current) return current
        store.saveCounterFloor(floorKey, direction, value)
        return value
    }

    /**
     * What an older version kept under conversation id [key] for [direction]: its floor, and (for another id) its
     * ratchet state (this conversation's own state is loaded and clamped by the caller).
     */
    private suspend fun legacyValue(
        key: String,
        direction: Byte,
        forSend: Boolean,
    ): Long {
        var value = store.loadCounterFloor(key, direction)
        if (key == convId) return value
        val older =
            try {
                store.load(key)
            } catch (e: RatchetStateCorruptionException) {
                if (forSend) throw e
                null
            }
        if (older != null) {
            value = maxOf(value, if (direction == myDirection) counterFor(older, direction) else highWaterFor(older, direction))
        }
        return value
    }

    /**
     * The send floor (R3-3 (1)): [settled] is this root's own floor, with what an older version kept under
     * [sendConvId] carried in for good; [unsettled] holds, per other conversation id, the older counters above it
     * that are carried but not made permanent until [settleLegacyJump] or [adoptLegacyJump].
     */
    private class SendFloor(
        val settled: Long,
        val unsettled: Map<String, Long>,
    ) {
        val value: Long get() = maxOf(settled, unsettled.values.maxOrNull() ?: 0L)
    }

    private suspend fun sendFloor(forSend: Boolean): SendFloor {
        var own = 0L
        val others = LinkedHashMap<String, Long>()
        for (key in legacyFloorKeys + convId + sendConvId) {
            val value = legacyValue(key, myDirection, forSend)
            if (key == sendConvId) own = maxOf(own, value) else others[key] = value
        }
        val settled = persistRootFloor(myDirection, own)
        val unsettled = LinkedHashMap<String, Long>()
        for ((key, value) in others) {
            // The older counters are also kept under this root in the plain floor file, so a wipe of the encrypted
            // ratchet state before the settlement cannot lose them.
            val seen = legacyMarkKey(SEEN_MARK, key)
            val carried = maxOf(value, store.loadCounterFloor(seen, myDirection))
            if (value > store.loadCounterFloor(seen, myDirection)) store.saveCounterFloor(seen, myDirection, value)
            val foreign = store.loadCounterFloor(legacyMarkKey(FOREIGN_MARK, key), myDirection)
            if (carried > foreign && carried > settled) unsettled[key] = carried
        }
        return SendFloor(settled, unsettled)
    }

    private fun legacyMarkKey(
        mark: String,
        legacyConvId: String,
    ) = "$floorKey|$mark|$legacyConvId"

    /**
     * The counters of [sorted] (ascending, all in [myDirection]) that authenticate under this root with
     * [aadConvId] as the conversation id: one walk of the chain, as [deriveMessageKey] walks it.
     */
    private fun authenticCounters(
        aadConvId: String,
        sorted: List<Ciphertext>,
    ): List<Long> {
        if (sorted.isEmpty()) return emptyList()
        val found = mutableListOf<Long>()
        var chainKey = deriveChainKey0(myDirection)
        var step = 0L
        for (ct in sorted) {
            while (step < ct.counter) {
                chainKey = hmacSha256(chainKey, byteArrayOf(CHAIN_STEP_BYTE))
                step++
            }
            val messageKey = hmacSha256(chainKey, byteArrayOf(MESSAGE_KEY_BYTE))
            val authentic =
                try {
                    aesGcmDecrypt(messageKey, counterNonce(myDirection, ct.counter), aadFor(myDirection, ct.counter, aadConvId), ct.bytes)
                    true
                } catch (e: java.security.GeneralSecurityException) {
                    false
                }
            if (authentic) found += ct.counter
        }
        return found
    }

    private suspend fun loadOrInit(forSend: Boolean): RatchetConversationState =
        applyCounterFloors(
            forSend,
            store.load(convId) ?: RatchetConversationState(
                convId = convId,
                nextCounterA2B = 0L,
                nextCounterB2A = 0L,
                seenCountersA2B = emptySet(),
                seenCountersB2A = emptySet(),
            ),
        )

    /**
     * Clamp the loaded [state] UP to the durable counter floors (never down — floors only raise).
     *
     * D8 nonce-reuse guard: the main ratchet state lives in an encrypted store that the
     * keystore/Tink corruption self-heal can wipe and recreate EMPTY, while the root material
     * survives elsewhere and re-derives the SAME root. The floors live in plain storage that
     * survives that wipe, so a wiped/empty state can never roll the send counter back below what
     * was already used under the current root (see [RatchetStateStore.loadCounterFloor]).
     */
    private suspend fun applyCounterFloors(
        forSend: Boolean,
        state: RatchetConversationState,
    ): RatchetConversationState {
        val peerDirection = if (myDirection == DIRECTION_A2B) DIRECTION_B2A else DIRECTION_A2B
        val sendFloor = sendFloor(forSend).value
        // Receive-side: the floor holds the high-water mark (largest counter ACCEPTED from the
        // peer). Restoring it keeps the replay/DoS window anchored at `mark + MAX_SKIP` after a
        // state wipe — without it the window collapses to [0, MAX_SKIP] and a long-lived peer's
        // legitimate next counter is rejected as CounterOutOfRange forever (permanent wedge).
        val receiveFloor = rootFloor(peerDirection, forSend)
        var floored = state
        if (myDirection == DIRECTION_A2B) {
            if (floored.nextCounterA2B < sendFloor) floored = floored.copy(nextCounterA2B = sendFloor)
            if (floored.maxSeenB2A < receiveFloor) floored = floored.copy(maxSeenB2A = receiveFloor)
        } else {
            if (floored.nextCounterB2A < sendFloor) floored = floored.copy(nextCounterB2A = sendFloor)
            if (floored.maxSeenA2B < receiveFloor) floored = floored.copy(maxSeenA2B = receiveFloor)
        }
        return floored
    }

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
        private const val DIRECTION_B2A: Byte = 0x01
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

        private val FLOOR_KEY_LABEL = "ZCHAT_RATCHET_FLOOR_KEY_V1".toByteArray(Charsets.UTF_8)

        /** R3-3 (1): marks kept in the floor file under a root's floor key, per older conversation id. */
        private const val SEEN_MARK = "legacy_seen"
        private const val FOREIGN_MARK = "legacy_foreign"

        /**
         * The durable-floor key of a ratchet root: "root_" + hex(HMAC-SHA256(root, "ZCHAT_RATCHET_FLOOR_KEY_V1")).
         * The floor file is PLAIN storage, so the root itself never goes there; an HMAC under the root reveals
         * nothing about it, and it cannot collide with an 8-character conversation id.
         */
        fun floorKeyFor(rootKey: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(rootKey, "HmacSHA256"))
            return "root_" + mac.doFinal(FLOOR_KEY_LABEL).joinToString("") { "%02x".format(it) }
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
