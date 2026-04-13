package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import android.content.SharedPreferences
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

/**
 * Production [RatchetStateStore] backed by Android [SharedPreferences] (typically
 * EncryptedSharedPreferences via Tink). Serializes [RatchetConversationState] as JSON.
 *
 * Thread safety: callers must hold [mutexFor] around load→mutate→save sequences.
 * The mutex is per-conversation to avoid cross-conversation contention.
 */
class EncryptedPrefsRatchetStateStore(
    private val prefs: SharedPreferences,
) : RatchetStateStore {

    private val mutexes = mutableMapOf<String, Mutex>()

    override suspend fun load(convId: String): RatchetConversationState? {
        val json = prefs.getString(key(convId), null) ?: return null
        return try {
            fromJson(JSONObject(json))
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun save(state: RatchetConversationState) {
        prefs.edit().putString(key(state.convId), toJson(state).toString()).apply()
    }

    override suspend fun mutexFor(convId: String): Mutex =
        synchronized(mutexes) { mutexes.getOrPut(convId) { Mutex() } }

    private fun key(convId: String) = "ratchet_state_$convId"

    private fun toJson(state: RatchetConversationState): JSONObject = JSONObject().apply {
        put("convId", state.convId)
        put("nextA2B", state.nextCounterA2B)
        put("nextB2A", state.nextCounterB2A)
        put("seenA2B", JSONArray(state.seenCountersA2B.toList()))
        put("seenB2A", JSONArray(state.seenCountersB2A.toList()))
    }

    private fun fromJson(obj: JSONObject): RatchetConversationState {
        val seenA2B = mutableSetOf<Long>()
        val arrA2B = obj.optJSONArray("seenA2B")
        if (arrA2B != null) for (i in 0 until arrA2B.length()) seenA2B.add(arrA2B.getLong(i))

        val seenB2A = mutableSetOf<Long>()
        val arrB2A = obj.optJSONArray("seenB2A")
        if (arrB2A != null) for (i in 0 until arrB2A.length()) seenB2A.add(arrB2A.getLong(i))

        return RatchetConversationState(
            convId = obj.getString("convId"),
            nextCounterA2B = obj.getLong("nextA2B"),
            nextCounterB2A = obj.getLong("nextB2A"),
            seenCountersA2B = seenA2B,
            seenCountersB2A = seenB2A,
        )
    }
}
