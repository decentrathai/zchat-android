package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import kotlinx.coroutines.sync.Mutex

/**
 * In-memory [RatchetStateStore] for unit tests.
 *
 * Not thread-safe across [load] / [save] because the caller is expected to hold
 * [mutexFor] around every state mutation.
 *
 * @param floors Backing map for the durable counter floors. Injectable so tests can model the
 *   production D8 corruption event — the encrypted ratchet STATE store wiped and recreated empty
 *   while the PLAIN floor file survives — by building a fresh store instance over the SAME map.
 */
class InMemoryRatchetStateStore(
    private val floors: MutableMap<String, Long> = mutableMapOf(),
) : RatchetStateStore {
    private val states = mutableMapOf<String, RatchetConversationState>()
    private val mutexes = mutableMapOf<String, Mutex>()

    override suspend fun load(convId: String): RatchetConversationState? = states[convId]

    override suspend fun save(state: RatchetConversationState) {
        states[state.convId] = state
    }

    override suspend fun mutexFor(convId: String): Mutex =
        synchronized(mutexes) { mutexes.getOrPut(convId) { Mutex() } }

    override suspend fun loadCounterFloor(convId: String, direction: Byte): Long =
        floors[floorKey(convId, direction)] ?: 0L

    override suspend fun saveCounterFloor(convId: String, direction: Byte, value: Long) {
        val key = floorKey(convId, direction)
        // Monotonic: never lower an existing floor (a rewind re-opens the nonce-reuse window).
        if (value > (floors[key] ?: 0L)) floors[key] = value
    }

    override suspend fun delete(convId: String) {
        states.remove(convId)
        // Floors kept under this conversation id are cleared on a re-pair; a root's floors are not (see interface doc).
        floors.keys.removeAll { it.startsWith("$convId:") }
    }

    private fun floorKey(convId: String, direction: Byte) = "$convId:$direction"
}
