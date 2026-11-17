package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Regression guard for the ratchet nonce-reuse footgun.
 *
 * [EncryptedPrefsRatchetStateStore.load] must distinguish "no state stored" (fresh conversation
 * → null) from "state stored but unparseable" (corruption → throw). If a corrupt record were
 * collapsed to null, the caller re-inits the send counter to 0 and the next AES-GCM encryption
 * reuses nonce 0 under the same key — catastrophic. See [RatchetStateCorruptionException].
 */
class EncryptedPrefsRatchetStateStoreTest {

    @Test
    fun load_returns_null_when_no_state_was_ever_stored() = runTest {
        val store = EncryptedPrefsRatchetStateStore(FakePrefs(emptyMap()), FakePrefs(emptyMap()))
        assertNull(store.load("CONV0001"))
    }

    @Test
    fun load_round_trips_a_saved_state() = runTest {
        // Pre-seed the backing store with a valid serialization produced by save().
        val backing = mutableMapOf<String, Any?>()
        val store = EncryptedPrefsRatchetStateStore(FakePrefs(backing), FakePrefs(emptyMap()))
        store.save(
            RatchetConversationState(
                convId = "CONV0001",
                nextCounterA2B = 7L,
                nextCounterB2A = 3L,
                seenCountersA2B = setOf(0L, 1L, 2L),
                seenCountersB2A = setOf(0L),
            ),
        )
        val loaded = store.load("CONV0001")
        assertEquals(7L, loaded?.nextCounterA2B)
        assertEquals(3L, loaded?.nextCounterB2A)
        assertEquals(setOf(0L, 1L, 2L), loaded?.seenCountersA2B)
    }

    @Test
    fun load_defaults_high_water_to_zero_for_pre_high_water_blobs() = runTest {
        // Backward compat: a blob persisted BEFORE the maxSeen* high-water fields existed (only the
        // original keys) MUST still deserialize, defaulting the high-water to 0 — it must NOT throw
        // RatchetStateCorruptionException, which would wedge every established conversation on upgrade.
        val oldBlob = """{"convId":"CONV0001","nextA2B":7,"nextB2A":3,"seenA2B":[0,1,2],"seenB2A":[0]}"""
        val store = EncryptedPrefsRatchetStateStore(
            FakePrefs(mapOf("ratchet_state_CONV0001" to oldBlob)),
            FakePrefs(emptyMap()),
        )
        val loaded = store.load("CONV0001")
        assertEquals(7L, loaded?.nextCounterA2B)
        assertEquals(3L, loaded?.nextCounterB2A)
        assertEquals(0L, loaded?.maxSeenA2B)
        assertEquals(0L, loaded?.maxSeenB2A)
    }

    @Test
    fun load_throws_on_corrupt_state_instead_of_silently_resetting_to_counter_zero() = runTest {
        // A present-but-unparseable record must NOT degrade to null (which would reset counters).
        val store = EncryptedPrefsRatchetStateStore(
            FakePrefs(mapOf("ratchet_state_CONV0001" to "{ this is not valid json")),
            FakePrefs(emptyMap()),
        )
        assertFailsWith<RatchetStateCorruptionException> { store.load("CONV0001") }
    }

    @Test
    fun counter_floor_round_trips_and_lives_only_in_the_separate_plain_floor_prefs() = runTest {
        // D8: the floor must be written to the SEPARATE (plain) floor prefs — never to the
        // encrypted state prefs — so the Tink/keystore self-heal that clears the encrypted state
        // file cannot also clear the floor.
        val stateBacking = mutableMapOf<String, Any?>()
        val floorBacking = mutableMapOf<String, Any?>()
        val store = EncryptedPrefsRatchetStateStore(FakePrefs(stateBacking), FakePrefs(floorBacking))

        assertEquals(0L, store.loadCounterFloor("CONV0001", 0x00), "unset floor must read as 0")
        store.saveCounterFloor("CONV0001", 0x00, 6L)
        assertEquals(6L, store.loadCounterFloor("CONV0001", 0x00))
        assertEquals(0L, store.loadCounterFloor("CONV0001", 0x01), "other direction stays unset")
        assertEquals(emptyMap<String, Any?>(), stateBacking, "floor must NOT touch the encrypted state prefs")

        // Simulate the localized corruption self-heal: encrypted state cleared, floor file survives.
        store.save(
            RatchetConversationState(
                convId = "CONV0001",
                nextCounterA2B = 6L,
                nextCounterB2A = 0L,
                seenCountersA2B = emptySet(),
                seenCountersB2A = emptySet(),
            ),
        )
        stateBacking.clear()
        assertNull(store.load("CONV0001"), "state is gone after the wipe")
        assertEquals(6L, store.loadCounterFloor("CONV0001", 0x00), "floor must survive the state wipe")
    }

    @Test
    fun counter_floor_is_monotonic_and_never_lowered() = runTest {
        // A lowered floor re-opens the nonce-reuse window — saveCounterFloor must only raise.
        val store = EncryptedPrefsRatchetStateStore(
            FakePrefs(mutableMapOf<String, Any?>()),
            FakePrefs(mutableMapOf<String, Any?>()),
        )
        store.saveCounterFloor("CONV0001", 0x00, 10L)
        store.saveCounterFloor("CONV0001", 0x00, 3L)
        assertEquals(10L, store.loadCounterFloor("CONV0001", 0x00), "floor must never be lowered")
        store.saveCounterFloor("CONV0001", 0x00, 11L)
        assertEquals(11L, store.loadCounterFloor("CONV0001", 0x00))
    }

    @Test
    fun delete_clears_state_and_both_direction_floors_for_that_conversation_only() = runTest {
        // delete() backs the explicit "Reset Encryption" re-pair: the NEXT root is fresh, so
        // counter 0 is safe again — and a retained floor would push the fresh conversation's first
        // counters above 0 (beyond the peer's fresh MAX_SKIP window if large): a permanent wedge.
        val store = EncryptedPrefsRatchetStateStore(
            FakePrefs(mutableMapOf<String, Any?>()),
            FakePrefs(mutableMapOf<String, Any?>()),
        )
        store.save(
            RatchetConversationState(
                convId = "CONV0001",
                nextCounterA2B = 7L,
                nextCounterB2A = 0L,
                seenCountersA2B = emptySet(),
                seenCountersB2A = emptySet(),
            ),
        )
        store.saveCounterFloor("CONV0001", 0x00, 7L)
        store.saveCounterFloor("CONV0001", 0x01, 4L)
        store.saveCounterFloor("CONV0002", 0x00, 9L) // unrelated conversation

        store.delete("CONV0001")

        assertNull(store.load("CONV0001"))
        assertEquals(0L, store.loadCounterFloor("CONV0001", 0x00), "send floor must be cleared on re-pair")
        assertEquals(0L, store.loadCounterFloor("CONV0001", 0x01), "receive floor must be cleared on re-pair")
        assertEquals(9L, store.loadCounterFloor("CONV0002", 0x00), "other conversations' floors untouched")
    }

    /**
     * Minimal in-test [SharedPreferences]: `getString`/`getLong` are exercised by `load()` /
     * `loadCounterFloor()`, and `edit()` returns an editor that writes back into [backing].
     */
    private class FakePrefs(initial: Map<String, Any?>) : SharedPreferences {
        private val backing: MutableMap<String, Any?> =
            if (initial is MutableMap) initial else initial.toMutableMap()

        override fun getString(key: String, defValue: String?): String? = backing[key] as? String ?: defValue

        override fun edit(): SharedPreferences.Editor = FakeEditor(backing)

        override fun getAll(): MutableMap<String, *> = backing
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = defValue
        override fun getLong(key: String, defValue: Long): Long = backing[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun contains(key: String): Boolean = backing.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class FakeEditor(private val backing: MutableMap<String, Any?>) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { backing[key] = value }
        override fun commit(): Boolean = true
        override fun apply() = Unit
        override fun remove(key: String): SharedPreferences.Editor = apply { backing.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { backing.clear() }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { backing[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
    }
}
