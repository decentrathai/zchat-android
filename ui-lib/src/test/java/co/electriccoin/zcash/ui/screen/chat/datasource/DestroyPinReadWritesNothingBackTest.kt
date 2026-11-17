package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FD-5 (fix-all 2.13.0, train T4b2; fold SPEC "FD-5" fix step 3, R5-2 in JVM form): reading whether a Destroy PIN
 * is set must never write the encrypted store.
 *
 * The chat list reads `hasDestroyPin()` on every recomposition, also while Destroy All is running. The getter of the
 * Destroy store runs the one-time v3 migration, and when its marker is missing (a fresh install, or right after the
 * wipe's `clearAll()`) the migration commits the marker even when there is nothing to copy. On Android any commit
 * or apply writes the whole cached map (the Tink keysets plus the marker) to disk when the file is missing, so a
 * read after the Destroy file sweep brought `zchat_e2e_keys_encrypted.xml` back (Fold, 28 Sep).
 *
 * Runs the real [ZchatPreferencesImpl] code over in-memory stores ([realPreferences]); the encrypted store is
 * wrapped so every commit or apply is counted as one disk write. The PINs and hashes are test literals.
 */
class DestroyPinReadWritesNothingBackTest {
    private class Stores(
        val prefs: ZchatPreferencesImpl,
        val plain: MemorySharedPreferences,
        val e2e: CountingPreferences,
    )

    private fun stores(): Stores {
        val (prefs, files) = realPreferences()
        val e2e = CountingPreferences(files.getValue("e2ePrefs"))
        ZchatPreferencesImpl::class.java
            .getDeclaredField("e2ePrefs")
            .apply { isAccessible = true }
            .set(prefs, e2e)
        return Stores(prefs, files.getValue("prefs"), e2e)
    }

    @Test
    fun `reading hasDestroyPin on a fresh store writes nothing`() {
        val s = stores()

        repeat(3) { assertFalse(s.prefs.hasDestroyPin()) }
        assertFalse(s.prefs.hasRemoteKillPhrase())

        assertEquals("a read with nothing to migrate committed the encrypted store", 0, s.e2e.writes)
    }

    @Test
    fun `reading hasDestroyPin after the wipe writes nothing back`() =
        runBlocking {
            val s = stores()
            s.prefs.setDestroyPin("1357")
            s.prefs.clearAll()
            s.e2e.writes = 0

            assertFalse(s.prefs.hasDestroyPin())
            assertFalse(s.prefs.hasDestroyPin())

            assertEquals(
                "a read after clearAll() committed the encrypted store; after the Destroy file sweep that recreates the file",
                0,
                s.e2e.writes
            )
        }

    /** Guard (green before the fix): a pre-v3 PIN hash in the plain store is still copied, not only read through. */
    @Test
    fun `a legacy Destroy PIN in the plain store is still copied into the encrypted store`() {
        val s = stores()
        s.plain
            .edit()
            .putString("destroy_pin", "fd5-legacy-hash")
            .putBoolean("destroy_pin_format_v2", true)
            .commit()

        assertTrue(s.prefs.hasDestroyPin())
        s.plain
            .edit()
            .remove("destroy_pin")
            .remove("destroy_pin_format_v2")
            .commit()

        assertTrue("the legacy PIN was read through, not copied", s.prefs.hasDestroyPin())
        assertEquals("fd5-legacy-hash", s.e2e.getString("destroy_pin", null))
    }

    /**
     * Guard (green before the fix): once a PIN is set in the encrypted store, a legacy copy that shows up in the plain
     * store later (a downgrade and upgrade again) is never copied over it; the same for the remote-kill phrase.
     */
    @Test
    fun `a legacy copy never replaces a PIN or phrase set later`() =
        runBlocking {
            val s = stores()
            s.prefs.setDestroyPin("2468")
            s.prefs.setRemoteKillPhrase("fd5-synthetic-phrase")
            val pinHash = s.e2e.getString("destroy_pin", null)
            val phraseHash = s.e2e.getString("remote_kill_phrase_hash", null)

            s.plain
                .edit()
                .putString("destroy_pin", "fd5-legacy-hash")
                .putString("remote_kill_phrase_hash", "fd5-legacy-phrase-hash")
                .commit()

            assertTrue(s.prefs.hasDestroyPin())
            assertTrue(s.prefs.hasRemoteKillPhrase())
            assertEquals(pinHash, s.e2e.getString("destroy_pin", null))
            assertEquals(phraseHash, s.e2e.getString("remote_kill_phrase_hash", null))
            assertTrue(s.prefs.verifyDestroyPin("2468"))
        }

    /** Counts every commit or apply: on Android each one writes the file, and recreates it when it is missing. */
    private class CountingPreferences(
        private val inner: MemorySharedPreferences
    ) : SharedPreferences by inner {
        var writes = 0

        override fun edit(): SharedPreferences.Editor {
            val editor = inner.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(
                    key: String,
                    value: String?
                ) = apply { editor.putString(key, value) }

                override fun putStringSet(
                    key: String,
                    values: MutableSet<String>?
                ) = apply { editor.putStringSet(key, values) }

                override fun putInt(
                    key: String,
                    value: Int
                ) = apply { editor.putInt(key, value) }

                override fun putLong(
                    key: String,
                    value: Long
                ) = apply { editor.putLong(key, value) }

                override fun putFloat(
                    key: String,
                    value: Float
                ) = apply { editor.putFloat(key, value) }

                override fun putBoolean(
                    key: String,
                    value: Boolean
                ) = apply { editor.putBoolean(key, value) }

                override fun remove(key: String) = apply { editor.remove(key) }

                override fun clear() = apply { editor.clear() }

                override fun commit(): Boolean {
                    writes++
                    return editor.commit()
                }

                override fun apply() {
                    writes++
                    editor.apply()
                }
            }
        }
    }
}
