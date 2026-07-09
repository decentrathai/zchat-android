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
     * Permanently delete any persisted state for [convId]. Used ONLY by an explicit, user-initiated
     * "Reset Encryption / re-pair" recovery: after deletion the next KEX establishes a fresh root key,
     * so re-initialising counters to 0 is safe again (a new root means a never-before-used key, so the
     * usual nonce-reuse hazard of resetting counters does not apply). MUST NOT be called on the normal
     * send/receive path.
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
