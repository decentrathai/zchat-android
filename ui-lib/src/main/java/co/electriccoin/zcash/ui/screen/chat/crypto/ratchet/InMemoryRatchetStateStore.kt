package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import kotlinx.coroutines.sync.Mutex

/**
 * In-memory [RatchetStateStore] for unit tests.
 *
 * Not thread-safe across [load] / [save] because the caller is expected to hold
 * [mutexFor] around every state mutation.
 */
class InMemoryRatchetStateStore : RatchetStateStore {
    private val states = mutableMapOf<String, RatchetConversationState>()
    private val mutexes = mutableMapOf<String, Mutex>()

    override suspend fun load(convId: String): RatchetConversationState? = states[convId]

    override suspend fun save(state: RatchetConversationState) {
        states[state.convId] = state
    }

    override suspend fun mutexFor(convId: String): Mutex =
        synchronized(mutexes) { mutexes.getOrPut(convId) { Mutex() } }
}
