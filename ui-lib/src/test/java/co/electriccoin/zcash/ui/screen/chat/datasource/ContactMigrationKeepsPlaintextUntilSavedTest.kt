package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R2-5 (a) — the one-time move of the pre-#190 plaintext contact list into the encrypted store.
 *
 * At 69f69acde migrateLegacyPlaintextIfNeeded writes the encrypted copy with apply() (no failure channel) and then
 * removes the plaintext copy "regardless". When the encrypted write does not land (a Keystore error, a full disk,
 * the process killed before the async flush) the user's whole contact list is gone: the plaintext copy was
 * scrubbed and the encrypted copy never existed.
 *
 * Wanted: the encrypted write is committed synchronously and read back; the plaintext copy is removed only after
 * the encrypted copy is proven readable. Otherwise the plaintext stays and the next start migrates it again.
 *
 * The migration is driven through the real ContactBookImpl (its Keystore-backed store is never built: the private
 * migration step is called with a fake encrypted store). All contact data is synthetic.
 */
class ContactMigrationKeepsPlaintextUntilSavedTest {
    private companion object {
        const val LEGACY_FILE = "zchat_contact_book"
        const val KEY = "contacts"
        const val BLOB = """[{"address":"usyntheticcontactmigration01","name":"Synthetic Ann","addedAt":1700000000000}]"""
    }

    @Test
    fun `a failed encrypted commit keeps the plaintext contacts`() {
        val context = FakeContext(legacyBlob = BLOB)
        val encrypted = FakePrefs(commitResult = false)

        migrate(context, encrypted)

        assertEquals(BLOB, context.legacy.getString(KEY, null), "the only copy of the contact list was deleted")
    }

    @Test
    fun `a throwing encrypted write keeps the plaintext contacts and the next start migrates them`() {
        val context = FakeContext(legacyBlob = BLOB)

        runCatching { migrate(context, FakePrefs(throwOnPut = true)) }
        assertEquals(BLOB, context.legacy.getString(KEY, null), "the only copy of the contact list was deleted")

        val nextStart = FakePrefs()
        migrate(context, nextStart)
        assertEquals(BLOB, nextStart.getString(KEY, null), "the next start must migrate the kept contacts")
        assertNull(context.legacy.getString(KEY, null), "once the encrypted copy is proven, the plaintext goes")
    }

    @Test
    fun `an encrypted write that does not read back keeps the plaintext contacts`() {
        val context = FakeContext(legacyBlob = BLOB)
        val encrypted = FakePrefs(dropWrites = true)

        migrate(context, encrypted)

        assertEquals(BLOB, context.legacy.getString(KEY, null), "the plaintext copy went while the encrypted one is unreadable")
    }

    @Test
    fun `the encrypted write is committed synchronously, not applied`() {
        val context = FakeContext(legacyBlob = BLOB)
        val encrypted = FakePrefs()

        migrate(context, encrypted)

        assertFalse(encrypted.applyUsed, "apply() cannot report a failed write, so it cannot gate the scrub")
        assertTrue(encrypted.commitCalls > 0)
        assertEquals(BLOB, encrypted.getString(KEY, null))
        assertNull(context.legacy.getString(KEY, null), "control: a proven migration removes the plaintext copy")
    }

    @Test
    fun `control - contacts already in the encrypted store are never overwritten by the plaintext copy`() {
        val context = FakeContext(legacyBlob = BLOB)
        val newer = """[{"address":"usyntheticcontactmigration02","name":"Synthetic Bob","addedAt":1700000001000}]"""
        val encrypted = FakePrefs().also { it.backing[KEY] = newer }

        migrate(context, encrypted)

        assertEquals(newer, encrypted.getString(KEY, null))
        assertNull(context.legacy.getString(KEY, null))
    }

    private fun migrate(
        context: FakeContext,
        encrypted: SharedPreferences,
    ) {
        val book = ContactBookImpl(context)
        val method =
            ContactBookImpl::class.java
                .getDeclaredMethod("migrateLegacyPlaintextIfNeeded", SharedPreferences::class.java)
                .apply { isAccessible = true }
        try {
            method.invoke(book, encrypted)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private class FakeContext(
        legacyBlob: String,
    ) : ContextWrapper(null) {
        val legacy = FakePrefs().also { it.backing[KEY] = legacyBlob }

        override fun getSharedPreferences(
            name: String,
            mode: Int
        ): SharedPreferences {
            check(name == LEGACY_FILE) { "unexpected preferences file $name" }
            return legacy
        }

        override fun getApplicationContext(): Context = this
    }

    /**
     * A preferences file with real commit/apply semantics. The editor stages writes; commit() flushes them only
     * when it reports success. apply() cannot report anything, so on a failing store its writes are simply lost.
     * [dropWrites]: commit() reports success but nothing lands (an unreadable write).
     */
    private class FakePrefs(
        private val commitResult: Boolean = true,
        private val throwOnPut: Boolean = false,
        private val dropWrites: Boolean = false,
    ) : SharedPreferences {
        val backing = LinkedHashMap<String, Any?>()
        var applyUsed = false
            private set
        var commitCalls = 0
            private set

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(backing)

        override fun getString(
            key: String,
            defValue: String?
        ): String? = backing[key] as? String ?: defValue

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = defValues

        override fun getInt(
            key: String,
            defValue: Int
        ): Int = defValue

        override fun getLong(
            key: String,
            defValue: Long
        ): Long = defValue

        override fun getFloat(
            key: String,
            defValue: Float
        ): Float = defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean
        ): Boolean = defValue

        override fun contains(key: String): Boolean = backing.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val puts = LinkedHashMap<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clear = false

            override fun putString(
                key: String,
                value: String?
            ): SharedPreferences.Editor {
                if (throwOnPut) throw SecurityException("synthetic Keystore failure")
                return apply { puts[key] = value }
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ) = apply { puts[key] = values }

            override fun putInt(
                key: String,
                value: Int
            ) = apply { puts[key] = value }

            override fun putLong(
                key: String,
                value: Long
            ) = apply { puts[key] = value }

            override fun putFloat(
                key: String,
                value: Float
            ) = apply { puts[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean
            ) = apply { puts[key] = value }

            override fun remove(key: String) = apply { removes += key }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                commitCalls++
                if (!commitResult) return false
                flush()
                return true
            }

            override fun apply() {
                applyUsed = true
                if (commitResult) flush()
            }

            private fun flush() {
                if (dropWrites) return
                if (clear) backing.clear()
                removes.forEach { backing.remove(it) }
                backing.putAll(puts)
            }
        }
    }
}
