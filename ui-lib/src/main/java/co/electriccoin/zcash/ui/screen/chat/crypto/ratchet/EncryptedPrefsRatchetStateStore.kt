package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Production [RatchetStateStore] backed by Android [SharedPreferences] (typically
 * EncryptedSharedPreferences via Tink). Serializes [RatchetConversationState] as JSON.
 *
 * Thread safety: callers must hold [mutexFor] around load→mutate→save sequences.
 * The mutex is per-conversation to avoid cross-conversation contention.
 *
 * @param prefs Main ratchet state (counters + high-water marks), ENCRYPTED at rest.
 * @param floorPrefs Durable counter floors — a PLAIN SharedPreferences file BY DESIGN
 *   ("zchat_ratchet_counter_floor"): the keystore/Tink corruption self-heal in
 *   ZchatPreferences.createEncryptedPrefs wipes the affected ENCRYPTED store and recreates it
 *   empty, so a floor kept inside any encrypted store would die with the state it protects.
 *   Counters are not secret (they are visible on the wire in every E2E1 ciphertext), so plain
 *   storage is correct here — do NOT "harden" this by encrypting it.
 *   See [RatchetStateStore.loadCounterFloor] for why the floor exists (D8, AES-GCM nonce reuse).
 */
class EncryptedPrefsRatchetStateStore(
    private val prefs: SharedPreferences,
    private val floorPrefs: SharedPreferences,
) : RatchetStateStore {

    private val mutexes = mutableMapOf<String, Mutex>()

    // Dispatchers.IO: EncryptedSharedPreferences read/commit is synchronous disk (+ fsync) I/O and these
    // suspend fns are called on the send path from a Main-immediate viewModelScope — running the commit on
    // Main janks frames and trips a StrictMode diskWrite violation (a hard crash in this project's debug
    // builds). Both are already suspend, so no caller changes are needed.
    override suspend fun load(convId: String): RatchetConversationState? = withContext(Dispatchers.IO) {
        // Key genuinely absent → fresh conversation; null is the correct answer.
        val json = prefs.getString(key(convId), null) ?: return@withContext null
        try {
            fromJson(JSONObject(json))
        } catch (e: Exception) {
            // SECURITY: state EXISTS but won't parse (corruption / partial write / schema drift).
            // Returning null here would let loadOrInit() reset the counters to 0, and the next
            // encrypt() would reuse nonce 0 under the same key → catastrophic AES-GCM nonce reuse.
            // Fail closed so the caller refuses to send rather than silently resetting the ratchet.
            throw RatchetStateCorruptionException(convId, e)
        }
    }

    override suspend fun save(state: RatchetConversationState) = withContext(Dispatchers.IO) {
        // CRITICAL: must be .commit() (synchronous), NOT .apply() (async).
        // If app crashes after encrypt() advances the counter but before the state
        // flushes to disk, the sender would re-use the same counter on restart →
        // same GCM nonce + same key = catastrophic nonce reuse.
        //
        // Fail CLOSED on a failed persist: commit() returns false on a write error (disk full, backend
        // failure). Ignoring it lets encrypt() hand the ciphertext for counter N to the sender while the
        // advanced counter never reaches disk — on restart the same (key, nonce) is reused for the next
        // send, the exact GCM nonce reuse this .commit() exists to prevent. Throw so the send aborts.
        val persisted = prefs.edit().putString(key(state.convId), toJson(state).toString()).commit()
        if (!persisted) {
            throw IOException("Failed to persist ratchet state for ${state.convId}")
        }
    }

    override suspend fun mutexFor(convId: String): Mutex =
        synchronized(mutexes) { mutexes.getOrPut(convId) { Mutex() } }

    override suspend fun loadCounterFloor(convId: String, direction: Byte): Long = withContext(Dispatchers.IO) {
        floorPrefs.getLong(floorKey(convId, direction), 0L)
    }

    override suspend fun saveCounterFloor(convId: String, direction: Byte, value: Long) = withContext(Dispatchers.IO) {
        // Monotonic raise only — a lowered floor re-opens the nonce-reuse window this store exists
        // to close. Skipping the no-op write also avoids a redundant fsync per message.
        if (value <= floorPrefs.getLong(floorKey(convId, direction), 0L)) return@withContext
        // .commit() (synchronous), same fail-closed discipline as save(): if the floor can't reach
        // disk, throw so encrypt() aborts instead of handing out a ciphertext whose counter could
        // be rolled back and reused after a state wipe.
        val persisted = floorPrefs.edit().putLong(floorKey(convId, direction), value).commit()
        if (!persisted) {
            throw IOException("Failed to persist ratchet counter floor for $convId")
        }
    }

    override suspend fun delete(convId: String) = withContext(Dispatchers.IO) {
        // .commit() (synchronous) so a crash right after a reset can't resurrect stale counters.
        prefs.edit().remove(key(convId)).commit()
        // Floors are scoped to the CURRENT root. delete() backs the explicit "Reset Encryption"
        // re-pair, after which a FRESH root is established and counter 0 is safe again — a floor
        // retained here would start the fresh conversation above 0 (beyond the peer's fresh
        // MAX_SKIP window if large), wedging it. Clear both directions (0x00 = A2B, 0x01 = B2A).
        // Only the floors kept under this conversation id: a root's floors (E2ERatchet.floorKey) stay, and a
        // new root has a different key (see RatchetStateStore.delete).
        floorPrefs.edit()
            .remove(floorKey(convId, 0x00))
            .remove(floorKey(convId, 0x01))
            .commit()
        Unit
    }

    private fun key(convId: String) = "ratchet_state_$convId"

    private fun floorKey(convId: String, direction: Byte) = "floor_${convId}_$direction"

    private fun toJson(state: RatchetConversationState): JSONObject = JSONObject().apply {
        put("convId", state.convId)
        put("nextA2B", state.nextCounterA2B)
        put("nextB2A", state.nextCounterB2A)
        put("seenA2B", JSONArray(state.seenCountersA2B.toList()))
        put("seenB2A", JSONArray(state.seenCountersB2A.toList()))
        put("maxSeenA2B", state.maxSeenA2B)
        put("maxSeenB2A", state.maxSeenB2A)
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
            // optLong defaults to 0 for blobs persisted before the high-water field existed, so old
            // records deserialize unchanged (0 == a fresh conversation's window base). MUST stay a
            // tolerant read: a hard getLong() here would throw on every pre-existing blob and — via
            // load()'s catch — surface as RatchetStateCorruptionException, wedging established chats.
            maxSeenA2B = obj.optLong("maxSeenA2B", 0L),
            maxSeenB2A = obj.optLong("maxSeenB2A", 0L),
        )
    }
}
