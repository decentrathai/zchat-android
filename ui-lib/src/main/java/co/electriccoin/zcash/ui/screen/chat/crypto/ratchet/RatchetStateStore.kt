package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import kotlinx.coroutines.sync.Mutex

/**
 * Persisted per-conversation ratchet state.
 *
 * Contains only the fields required for restore-from-seed: direction counters and the
 * seen-counter window per direction. The root key, chain keys, and skipped-key cache are
 * ephemeral (re-derived from the seed + KEX context on demand).
 */
data class RatchetConversationState(
    val convId: String,
    val nextCounterA2B: Long,
    val nextCounterB2A: Long,
    val seenCountersA2B: Set<Long>,
    val seenCountersB2A: Set<Long>,
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
}

/**
 * Thrown when persisted ratchet state exists but cannot be parsed (corruption, partial write,
 * or schema drift). Signals "do not send" rather than "no state" — recovering requires an
 * explicit re-KEX, which establishes a fresh root key so counter 0 is safe again.
 */
class RatchetStateCorruptionException(convId: String, cause: Throwable) :
    Exception("Ratchet state for conversation $convId is corrupt and cannot be loaded", cause)
