package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import kotlinx.coroutines.sync.Mutex

/**
 * Persisted per-conversation ratchet state.
 *
 * Contains the fields required for restore-from-seed: the per-direction send counters and the
 * per-direction receive HIGH-WATER MARK. The root key, chain keys, and skipped-key cache are
 * ephemeral (re-derived from the seed + KEX context on demand).
 *
 * [seenCountersA2B]/[seenCountersB2A] are retained for wire compatibility with older blobs but are
 * NOT used for windowing — within-session replay protection is tracked in the ephemeral session
 * seen-sets, and the persisted DoS window is anchored on [maxSeenA2B]/[maxSeenB2A].
 */
data class RatchetConversationState(
    val convId: String,
    val nextCounterA2B: Long,
    val nextCounterB2A: Long,
    val seenCountersA2B: Set<Long>,
    val seenCountersB2A: Set<Long>,
    // Receive-side HIGH-WATER MARK: the largest counter ever ACCEPTED (successfully decrypted) on
    // each peer chain. Persisted so that after an app restart — when the session seen-set is empty
    // because history is served from ChatViewModel's L2 plaintext cache and never re-enters the
    // ratchet — the DoS window (base + MAX_SKIP) is anchored at the established position instead of
    // 0. That cures the post-restart "conversation wedge" (a legitimate high counter rejected as
    // CounterOutOfRange forever, nudging the user toward a ZEC-spending re-KEX) WITHOUT widening the
    // pre-AEAD skip walk beyond MAX_SKIP. Defaulted to 0 so blobs persisted before this field
    // existed deserialize unchanged, and so a genuinely new conversation starts from 0.
    val maxSeenA2B: Long = 0L,
    val maxSeenB2A: Long = 0L,
)

/**
 * Interface for persisting ratchet state per conversation.
 *
 * Production impl: EncryptedPrefsRatchetStateStore (backed by EncryptedSharedPreferences).
 * Test impl: [InMemoryRatchetStateStore].
 */
interface RatchetStateStore {
    /**
     * Returns the persisted state for [convId], or `null` ONLY when no state has ever been
     * stored (a genuinely fresh conversation). Implementations MUST NOT collapse a
     * present-but-unparseable record into `null`: doing so makes the caller re-init counters
     * to 0 and reuse an already-burned AES-GCM nonce. On corruption, throw
     * [RatchetStateCorruptionException] so the caller fails closed instead of reusing a nonce.
     */
    suspend fun load(convId: String): RatchetConversationState?

    suspend fun save(state: RatchetConversationState)

    suspend fun mutexFor(convId: String): Mutex

    /**
     * Load the durable counter FLOOR for ([convId], [direction]), or 0 if none was ever saved.
     *
     * [convId] is the floor's KEY: a ratchet root's [E2ERatchet.floorKey] (a hash of the root — the authoritative
     * floor since R2-4, shared by every conversation id of that root), or a conversation id (the keying before
     * R2-4, still written as a mirror and carried into the root's floor by [E2ERatchet]).
     *
     * SECURITY (D8, AES-GCM nonce reuse): the main ratchet state lives in an ENCRYPTED store that
     * the keystore/Tink corruption self-heal can wipe and recreate EMPTY — while the E2E root
     * material survives in a separate store and re-derives the SAME root. Key and nonce are
     * deterministic functions of (root, direction, counter), so a state wipe that rolls the send
     * counter back to 0 makes the next send REUSE an already-burned (key, nonce) pair — a
     * catastrophic AES-GCM break. The floor is therefore persisted in storage that SURVIVES the
     * encrypted-state wipe (production: the PLAIN "zchat_ratchet_counter_floor" SharedPreferences —
     * intentionally NOT encrypted, counters are not secret) and [E2ERatchet] clamps the loaded
     * state up to it, so a wiped/empty state store can never roll a counter back below what was
     * already consumed under the current root.
     *
     * Value semantics (the store is agnostic; [E2ERatchet] interprets per direction):
     * - For this device's SEND direction: the next usable send counter (last used + 1) —
     *   floors `nextCounterA2B`/`nextCounterB2A`.
     * - For the PEER's direction: the receive high-water mark (largest counter accepted) —
     *   floors `maxSeenA2B`/`maxSeenB2A`, so the post-wipe DoS/replay window resumes at the
     *   established anchor instead of 0 (which would wedge legitimate high counters).
     */
    suspend fun loadCounterFloor(convId: String, direction: Byte): Long

    /**
     * Raise the durable counter floor for ([convId], [direction]) to [value].
     *
     * Implementations MUST persist durably before returning (synchronous commit, matching [save]'s
     * fail-closed discipline — throw on a failed persist) and MUST be monotonic: a call with a
     * [value] at or below the current floor is a no-op, never a rewind (a lowered floor re-opens
     * the nonce-reuse window).
     */
    suspend fun saveCounterFloor(convId: String, direction: Byte, value: Long)

    /**
     * Permanently delete any persisted state for [convId]. Used ONLY by an explicit, user-initiated
     * "Reset Encryption / re-pair" recovery: after deletion the next KEX establishes a fresh root key,
     * so re-initialising counters to 0 is safe again (a new root means a never-before-used key, so the
     * usual nonce-reuse hazard of resetting counters does not apply). MUST NOT be called on the normal
     * send/receive path.
     *
     * ZC-545, ZC-564: a second caller is the accept of a contact's new key (it adopts a new root after the same wipe).
     * Both callers hold the contact's session lock (ChatViewModel.withPeerSession), which every user of a ratchet
     * processor holds around its ratchet call: a load, mutate, save sequence therefore finishes before a delete or
     * starts after it. [delete] itself takes no [mutexFor]; the state carries no root id, so a save landing after a
     * delete would hand the old root's counters to the next root.
     *
     * Implementations MUST also clear the counter floors kept under [convId] (both directions): [E2ERatchet]
     * carries them into the next root's floor, and a floor retained across a re-pair would make the fresh
     * conversation start above 0 — beyond the peer's fresh MAX_SKIP window if large — wedging it. Floors kept
     * under a root's [E2ERatchet.floorKey] are NOT cleared: a new root has a different key, and the old root's
     * floor must survive in case that root is ever derived again.
     */
    suspend fun delete(convId: String)
}

/**
 * Thrown when persisted ratchet state exists but cannot be parsed (corruption, partial write,
 * or schema drift). Signals "do not send" rather than "no state" — recovering requires an
 * explicit re-KEX, which establishes a fresh root key so counter 0 is safe again.
 */
class RatchetStateCorruptionException(convId: String, cause: Throwable) :
    Exception("Ratchet state for conversation $convId is corrupt and cannot be loaded", cause)
