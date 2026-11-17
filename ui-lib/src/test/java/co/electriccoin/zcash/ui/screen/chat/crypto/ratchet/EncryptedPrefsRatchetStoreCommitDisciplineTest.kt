package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Commit-discipline regression for [EncryptedPrefsRatchetStateStore] ("commit-not-apply").
 *
 * The ratchet's nonce-reuse safety depends on two properties of every persist on the send path:
 *  1. SYNCHRONOUS `.commit()`, never async `.apply()` — a crash after encrypt() but before the
 *     async flush would re-use the same (key, nonce) on restart.
 *  2. FAIL-CLOSED on a failed commit — `commit() == false` must surface as an exception so the
 *     send aborts, never be silently ignored (or hidden entirely by `apply()`, which cannot
 *     report failure at all).
 *
 * The fixture's editor stages writes and only flushes them on a successful `commit()`; `apply()`
 * flushes unconditionally and records that it was used — modeling the real Android semantics
 * where apply() "always succeeds" and never reports I/O failure.
 *
 * MUTATIONS THAT REDDEN THESE TESTS:
 *  - Replace `.commit()` with `.apply()` in save()/saveCounterFloor(): `applyUsed` flips true
 *    (reddening the commit-path tests) AND the failed-persist tests stop throwing (apply never
 *    reports failure) — red on assertFailsWith.
 *  - Keep `.commit()` but drop the `if (!persisted) throw IOException` check: the failed-persist
 *    tests stop throwing — red on assertFailsWith.
 */
class EncryptedPrefsRatchetStoreCommitDisciplineTest {

    private val state = RatchetConversationState(
        convId = "CONV0001",
        nextCounterA2B = 5L,
        nextCounterB2A = 0L,
        seenCountersA2B = emptySet(),
        seenCountersB2A = emptySet(),
    )

    @Test
    fun save_persists_via_synchronous_commit_never_apply() = runTest {
        val prefs = CommitSensingPrefs()
        val store = EncryptedPrefsRatchetStateStore(prefs, CommitSensingPrefs())

        store.save(state)

        // Mutation sensor: `.commit()` → `.apply()` flips applyUsed to true.
        assertFalse(prefs.applyUsed, "ratchet state must be persisted with synchronous commit(), not apply()")
        assertTrue(prefs.commitCalls > 0, "commit() must actually be invoked")
        assertEquals(5L, store.load("CONV0001")?.nextCounterA2B, "committed state must be readable back")
    }

    @Test
    fun save_fails_closed_when_commit_reports_failure() = runTest {
        val prefs = CommitSensingPrefs(commitResult = false)
        val store = EncryptedPrefsRatchetStateStore(prefs, CommitSensingPrefs())

        // Mutation: ignoring commit()'s false return (or switching to apply(), which cannot
        // report failure) makes save() return normally — this assertFailsWith goes red.
        assertFailsWith<IOException>("a failed commit must abort the send, not be silently ignored") {
            store.save(state)
        }
        // Fail-closed also means the failed write left nothing behind to read back.
        assertNull(store.load("CONV0001"), "a failed commit must not surface as persisted state")
    }

    @Test
    fun counter_floor_persists_via_synchronous_commit_never_apply() = runTest {
        val floorPrefs = CommitSensingPrefs()
        val store = EncryptedPrefsRatchetStateStore(CommitSensingPrefs(), floorPrefs)

        store.saveCounterFloor("CONV0001", 0x00, 6L)

        // Mutation sensor: `.commit()` → `.apply()` on the floor write flips applyUsed to true.
        assertFalse(floorPrefs.applyUsed, "counter floor must be persisted with synchronous commit(), not apply()")
        assertTrue(floorPrefs.commitCalls > 0, "commit() must actually be invoked for the floor")
        assertEquals(6L, store.loadCounterFloor("CONV0001", 0x00))
    }

    @Test
    fun counter_floor_fails_closed_when_commit_reports_failure() = runTest {
        val floorPrefs = CommitSensingPrefs(commitResult = false)
        val store = EncryptedPrefsRatchetStateStore(CommitSensingPrefs(), floorPrefs)

        // Mutation: swallowing the failed floor commit lets encrypt() hand out a ciphertext
        // whose counter could be rolled back after a state wipe (D8 nonce reuse) — red here.
        assertFailsWith<IOException>("a failed floor commit must throw so encrypt() aborts") {
            store.saveCounterFloor("CONV0001", 0x00, 6L)
        }
        assertEquals(0L, store.loadCounterFloor("CONV0001", 0x00), "failed commit must leave the floor unset")
    }

    /**
     * [SharedPreferences] fake with real commit/apply semantics: the editor STAGES writes and
     * only flushes them into [backing] when `commit()` succeeds; `apply()` flushes
     * unconditionally (Android's apply() has no failure channel) and records [applyUsed].
     * With [commitResult] = false, `commit()` returns false and flushes nothing.
     */
    private class CommitSensingPrefs(
        private val commitResult: Boolean = true,
    ) : SharedPreferences {
        private val backing = mutableMapOf<String, Any?>()
        var applyUsed = false
            private set
        var commitCalls = 0
            private set

        override fun getString(key: String, defValue: String?): String? = backing[key] as? String ?: defValue
        override fun getLong(key: String, defValue: Long): Long = backing[key] as? Long ?: defValue

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                also { staged[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                also { staged[key] = value }

            override fun remove(key: String): SharedPreferences.Editor = also { removed.add(key) }

            override fun clear(): SharedPreferences.Editor = also {
                removed.addAll(backing.keys)
            }

            override fun commit(): Boolean {
                commitCalls++
                if (!commitResult) return false
                flush()
                return true
            }

            override fun apply() {
                applyUsed = true
                flush() // apply() "always succeeds" — and never reports failure
            }

            private fun flush() {
                removed.forEach { backing.remove(it) }
                backing.putAll(staged)
            }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        }

        override fun getAll(): MutableMap<String, *> = backing
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun contains(key: String): Boolean = backing.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }
}
