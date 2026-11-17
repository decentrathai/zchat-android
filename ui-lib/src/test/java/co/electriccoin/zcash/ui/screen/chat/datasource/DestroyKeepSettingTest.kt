package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * DESTROY-ADV-W (register ZC-722; owner W-41, W-43, W-47, W-52; Destroy Advanced spec revision 2c, 8 and 10.2) —
 * the saved choice of what Destroy All may keep: one entry, `destroy_keep_v1` (comma-separated values, today only
 * `contacts`), in the encrypted chat-key store that already holds the Destroy PIN.
 *
 *  - an empty, a cleared and a 2.12.4-shaped store answer the empty set (an upgraded install destroys everything);
 *  - a saved choice reads back; an unknown or a broken value reads as empty; a failed commit answers false and leaves
 *    the old value; reading writes nothing (the FD-5 rule);
 *  - THE CHOICE LIVES WITH THE PIN (2b): with no Destroy PIN the choice reads empty and cannot be saved; clearing the
 *    PIN removes it in the same write; `setDestroySetup(pin, keep)` writes the PIN and the choice in ONE commit, and a
 *    commit that fails leaves the old PIN and the old choice.
 *
 * The real [ZchatPreferencesImpl] code runs over in-memory stores ([realPreferences]). The three new methods are
 * reached by name, so this file compiles before they exist. PINs are test literals.
 */
class DestroyKeepSettingTest {
    private class Stores(
        val prefs: ZchatPreferencesImpl,
        val plain: MemorySharedPreferences,
        val e2e: FailingPreferences,
        val files: Map<String, MemorySharedPreferences>,
    )

    private fun stores(): Stores {
        val (prefs, files) = realPreferences()
        val e2e = FailingPreferences(files.getValue("e2ePrefs"))
        ZchatPreferencesImpl::class.java
            .getDeclaredField("e2ePrefs")
            .apply { isAccessible = true }
            .set(prefs, e2e)
        return Stores(prefs, files.getValue("prefs"), e2e, files)
    }

    @Test
    fun `a fresh store, a cleared store and a 2_12_4 store answer the empty set`() =
        runBlocking {
            val fresh = stores()
            assertEquals(emptySet<String>(), getKeep(fresh.prefs))

            val cleared = stores()
            cleared.prefs.setDestroyPin("1357")
            assertTrue(setKeep(cleared.prefs, setOf("contacts")))
            cleared.prefs.clearAll()
            assertEquals("clearAll() removes the choice", emptySet<String>(), getKeep(cleared.prefs))
            assertFalse("clearAll() removes the entry", cleared.e2e.contains(ENTRY))

            // 2.12.4 left a Destroy PIN in the plain store and no choice anywhere.
            val old = stores()
            old.plain
                .edit()
                .putString("destroy_pin", "legacyhash")
                .commit()
            assertEquals("an upgraded install destroys everything", emptySet<String>(), getKeep(old.prefs))
        }

    @Test
    fun `a saved choice reads back, an unknown or broken value reads empty, and reading writes nothing`() =
        runBlocking {
            val s = stores()
            s.prefs.setDestroyPin("1357")
            assertTrue(setKeep(s.prefs, setOf("contacts")))
            assertEquals(setOf("contacts"), getKeep(s.prefs))
            assertEquals("the entry is in the encrypted chat-key store", "contacts", s.e2e.getString(ENTRY, null))

            s.e2e.writes = 0
            repeat(3) { assertEquals(setOf("contacts"), getKeep(s.prefs)) }
            assertEquals("three reads commit and apply nothing", 0, s.e2e.writes)

            s.e2e
                .edit()
                .putString(ENTRY, "contacts,wallet")
                .commit()
            assertEquals("an unknown value reads as keep nothing", emptySet<String>(), getKeep(s.prefs))
            s.e2e
                .edit()
                .putString(ENTRY, "photos")
                .commit()
            assertEquals(emptySet<String>(), getKeep(s.prefs))
            s.e2e
                .edit()
                .putBoolean(ENTRY, true)
                .commit()
            assertEquals("a broken value reads as keep nothing", emptySet<String>(), getKeep(s.prefs))
        }

    @Test
    fun `every plain file is as before - the choice is only in the encrypted store`() =
        runBlocking {
            val s = stores()
            s.prefs.setDestroyPin("1357")
            val before = s.files.filterKeys { it != "e2ePrefs" }.mapValues { it.value.snapshot() }
            assertTrue(setKeep(s.prefs, setOf("contacts")))
            val after = s.files.filterKeys { it != "e2ePrefs" }.mapValues { it.value.snapshot() }
            assertEquals("a plain or another store changed", before, after)
        }

    @Test
    fun `a failed commit answers false and leaves the old value`() =
        runBlocking {
            val s = stores()
            s.prefs.setDestroyPin("1357")
            assertTrue(setKeep(s.prefs, setOf("contacts")))
            s.e2e.failCommits = true
            assertFalse("a commit that failed", setKeep(s.prefs, emptySet()))
            s.e2e.failCommits = false
            assertEquals("the old choice stays", setOf("contacts"), getKeep(s.prefs))
        }

    @Test
    fun `with no Destroy PIN the choice reads empty and cannot be saved`() =
        runBlocking {
            val s = stores()
            s.e2e
                .edit()
                .putString(ENTRY, "contacts")
                .commit()
            assertEquals("a choice without a PIN does not count", emptySet<String>(), getKeep(s.prefs))
            s.e2e
                .edit()
                .remove(ENTRY)
                .commit()
            s.e2e.writes = 0
            assertFalse("no choice is saved without a PIN", setKeep(s.prefs, setOf("contacts")))
            assertEquals("nothing was written", 0, s.e2e.writes)
            assertFalse(s.e2e.contains(ENTRY))
        }

    @Test
    fun `clearing the Destroy PIN removes the choice in the same write`() =
        runBlocking {
            val s = stores()
            s.prefs.setDestroyPin("1357")
            assertTrue(setKeep(s.prefs, setOf("contacts")))
            s.e2e.writes = 0
            s.prefs.clearDestroyPin()
            assertFalse("the PIN is gone", s.prefs.hasDestroyPin())
            assertFalse("the choice is gone with it", s.e2e.contains(ENTRY))
            assertEquals("one write removes both", 1, s.e2e.writes)
        }

    @Test
    fun `setDestroySetup writes the PIN and the choice in one commit`() =
        runBlocking {
            val s = stores()
            s.e2e.writes = 0
            assertTrue(setup(s.prefs, "2468", setOf("contacts")))
            assertEquals("one commit", 1, s.e2e.writes)
            assertTrue("the PIN verifies", s.prefs.verifyDestroyPin("2468"))
            assertEquals(setOf("contacts"), getKeep(s.prefs))

            assertTrue(setup(s.prefs, "2468", emptySet()))
            assertEquals("a set-up with nothing ticked leaves the empty set", emptySet<String>(), getKeep(s.prefs))
        }

    @Test
    fun `a set-up whose commit fails leaves the old PIN and the old choice`() =
        runBlocking {
            val s = stores()
            assertTrue(setup(s.prefs, "2468", setOf("contacts")))
            s.e2e.failCommits = true
            assertFalse("a failed commit answers false", setup(s.prefs, "9999", emptySet()))
            s.e2e.failCommits = false
            assertTrue("the old PIN still verifies", s.prefs.verifyDestroyPin("2468"))
            assertFalse("the new PIN does not", s.prefs.verifyDestroyPin("9999"))
            assertEquals("the old choice still reads", setOf("contacts"), getKeep(s.prefs))
        }

    // ---- the new methods, by name ---------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun getKeep(prefs: ZchatPreferences): Set<String> = call(prefs, "getDestroyKeep") as Set<String>

    private fun setKeep(
        prefs: ZchatPreferences,
        keep: Set<String>,
    ): Boolean = call(prefs, "setDestroyKeep", keep) as Boolean

    private suspend fun setup(
        prefs: ZchatPreferences,
        pin: String,
        keep: Set<String>,
    ): Boolean {
        val method =
            prefs.javaClass.methods.firstOrNull { it.name == "setDestroySetup" && it.parameterCount == 3 }
                ?: throw AssertionError("ZchatPreferences has no setDestroySetup(pin, keep)")
        return suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            try {
                method.invoke(prefs, pin, keep, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Boolean
    }

    private fun call(
        target: Any,
        name: String,
        vararg args: Any,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("ZchatPreferences has no $name: the choice of what Destroy All keeps is not stored")
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** Counts every commit or apply; with [failCommits] a commit answers false and changes nothing. */
    private class FailingPreferences(
        private val inner: MemorySharedPreferences
    ) : SharedPreferences by inner {
        var writes = 0

        @Volatile
        var failCommits = false

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
                    return if (failCommits) false else editor.commit()
                }

                override fun apply() {
                    writes++
                    if (!failCommits) editor.apply()
                }
            }
        }
    }

    private companion object {
        const val ENTRY = "destroy_keep_v1"
    }
}
