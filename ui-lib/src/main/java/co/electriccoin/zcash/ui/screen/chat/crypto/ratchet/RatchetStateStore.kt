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
    suspend fun load(convId: String): RatchetConversationState?

    suspend fun save(state: RatchetConversationState)

    suspend fun mutexFor(convId: String): Mutex
}
