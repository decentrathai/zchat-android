package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import com.google.crypto.tink.shaded.protobuf.InvalidProtocolBufferException
import org.junit.Test
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * KR-1 (P2c): chat keys live in EncryptedSharedPreferences stores whose Tink keyset is wrapped by an
 * Android Keystore master key. The self-heal in [openEncryptedStore] used to clear the store on ANY
 * GeneralSecurityException/IOException — including a temporary Keystore error (the Keystore is known
 * to be flaky right after an upgrade or a reboot). That erased the user's E2E keys, group keys and
 * message history for good.
 *
 * Required behaviour:
 *  - a temporary Keystore error never clears a store: it is retried, and if it persists the app
 *    stops (the data stays on disk for the next launch) instead of wiping;
 *  - only a key that is permanently gone (the keyset no longer decrypts or no longer parses), or a
 *    failure that survives several launches, lets the store be recreated, and even then an exact copy
 *    of every stored entry is kept in another file first — never a plain delete;
 *  - if that copy cannot be written, nothing is cleared.
 *
 * Launches are simulated by calling [openEncryptedStore] again over the same file registry.
 */
class EncryptedStoreKeepsDataOnKeystoreErrorTest {
    private val storeName = "zchat_e2e_keys_encrypted"

    private val original: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-1",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-2",
            "AQx1-encrypted-key" to "AUz9-encrypted-value",
        )

    private fun registryWithStore(): FakeRegistry =
        FakeRegistry().apply {
            file(storeName).backing.putAll(original)
        }

    @Test
    fun `a temporary Keystore error that clears on retry opens the store without clearing it`() {
        val files = registryWithStore()
        val encrypted = FakePrefs()
        var attempts = 0

        val result =
            openEncryptedStore(
                name = storeName,
                open = {
                    attempts++
                    if (attempts == 1) throw KeyStoreException("Keystore busy") else encrypted
                },
                plainPrefs = files::file,
            )

        assertSame(encrypted, result)
        assertEquals(original, files.file(storeName).backing, "a temporary Keystore error cleared the chat keys")
    }

    @Test
    fun `a Keystore error that lasts the whole launch stops the launch and keeps the data`() {
        val files = registryWithStore()

        val outcome =
            runCatching {
                openEncryptedStore(
                    name = storeName,
                    open = { throw KeyStoreException("the master key exists but is unusable") },
                    plainPrefs = files::file,
                )
            }

        assertEquals(original, files.file(storeName).backing, "a temporary Keystore error cleared the chat keys")
        assertTrue(outcome.exceptionOrNull() is IllegalStateException, "expected the launch to stop, got $outcome")
    }

    @Test
    fun `a ProviderException from the Keystore is retried rather than treated as fatal`() {
        val files = registryWithStore()
        val encrypted = FakePrefs()
        var attempts = 0

        val result =
            openEncryptedStore(
                name = storeName,
                open = {
                    attempts++
                    if (attempts == 1) throw ProviderException("Keystore operation failed") else encrypted
                },
                plainPrefs = files::file,
            )

        assertSame(encrypted, result)
        assertEquals(original, files.file(storeName).backing)
    }

    @Test
    fun `a Keystore error that survives several launches recreates the store only after copying it`() {
        val files = registryWithStore()
        val encrypted = FakePrefs()
        var keystoreBroken = true
        val open: () -> SharedPreferences = {
            if (keystoreBroken) throw KeyStoreException("the master key exists but is unusable") else encrypted
        }

        // Launches 1 and 2: stop, keep everything.
        repeat(2) {
            val outcome = runCatching { openEncryptedStore(name = storeName, open = open, plainPrefs = files::file) }
            assertEquals(original, files.file(storeName).backing, "launch ${it + 1} cleared the chat keys")
            assertTrue(outcome.exceptionOrNull() is IllegalStateException, "expected launch ${it + 1} to stop, got $outcome")
        }

        // Launch 3: the failure is treated as permanent — recreate, but only after keeping a copy.
        // (The recreated store's own open succeeds once the old keyset is out of the way.)
        var cleared = false
        val result =
            openEncryptedStore(
                name = storeName,
                open = {
                    if (files.file(storeName).backing.isEmpty()) {
                        cleared = true
                        keystoreBroken = false
                    }
                    open()
                },
                plainPrefs = files::file,
            )

        assertTrue(cleared, "the store was never recreated, the app would stop on every launch")
        assertSame(encrypted, result)
        assertTrue(
            files.copiesOf(original, except = storeName),
            "the store was cleared without keeping a recoverable copy of its entries"
        )
    }

    @Test
    fun `a keyset that no longer decrypts is recreated in the same launch after a copy is kept`() {
        val files = registryWithStore()
        val encrypted = FakePrefs()

        val result =
            openEncryptedStore(
                name = storeName,
                open = {
                    if (files.file(storeName).backing.isNotEmpty()) throw AEADBadTagException("tag mismatch") else encrypted
                },
                plainPrefs = files::file,
            )

        assertSame(encrypted, result)
        assertTrue(
            files.copiesOf(original, except = storeName),
            "the store was cleared without keeping a recoverable copy of its entries"
        )
    }

    @Test
    fun `a keyset that no longer parses is recreated in the same launch after a copy is kept`() {
        val files = registryWithStore()
        val encrypted = FakePrefs()

        val result =
            openEncryptedStore(
                name = storeName,
                open = {
                    if (files.file(storeName).backing.isNotEmpty()) {
                        throw InvalidProtocolBufferException("Protocol message had invalid UTF-8.")
                    } else {
                        encrypted
                    }
                },
                plainPrefs = files::file,
            )

        assertSame(encrypted, result)
        assertTrue(
            files.copiesOf(original, except = storeName),
            "the store was cleared without keeping a recoverable copy of its entries"
        )
    }

    @Test
    fun `when the copy cannot be written nothing is cleared`() {
        val files = registryWithStore().apply { failCommitsExceptFor = setOf(storeName) }

        val outcome =
            runCatching {
                openEncryptedStore(
                    name = storeName,
                    open = {
                        if (files.file(storeName).backing.isNotEmpty()) throw AEADBadTagException("tag mismatch") else FakePrefs()
                    },
                    plainPrefs = files::file,
                )
            }

        assertEquals(original, files.file(storeName).backing, "the store was cleared although its copy failed")
        assertTrue(outcome.exceptionOrNull() is IllegalStateException, "expected the launch to stop, got $outcome")
    }

    // Pin (holds before and after the fix): a healthy store is opened as-is, nothing is copied.
    @Test
    fun `a healthy store is opened untouched`() {
        val files = registryWithStore()
        val encrypted = FakePrefs()

        val result = openEncryptedStore(name = storeName, open = { encrypted }, plainPrefs = files::file)

        assertSame(encrypted, result)
        assertEquals(original, files.file(storeName).backing)
        assertTrue(files.names().none { files.file(it).backing == original && it != storeName })
    }

    /** File-name → in-memory prefs, standing in for Context.getSharedPreferences. */
    private class FakeRegistry {
        private val files = linkedMapOf<String, FakePrefs>()

        /** Commits to every file NOT in this set report failure (null = all commits succeed). */
        var failCommitsExceptFor: Set<String>? = null

        fun file(name: String): FakePrefs =
            files.getOrPut(name) {
                FakePrefs { failCommitsExceptFor?.let { name !in it } ?: false }
            }

        fun names(): Set<String> = files.keys.toSet()

        fun copiesOf(entries: Map<String, Any?>, except: String): Boolean =
            files.any { (name, prefs) -> name != except && prefs.backing == entries }
    }

    private class FakePrefs(
        private val commitFails: () -> Boolean = { false }
    ) : SharedPreferences {
        val backing = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(backing)

        override fun getString(key: String, defValue: String?): String? = backing[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (backing[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String, defValue: Int): Int = backing[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = backing[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = backing[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean = backing[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = backing.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private class FakeEditor(
            private val prefs: FakePrefs
        ) : SharedPreferences.Editor {
            private val puts = linkedMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String, value: String?) = apply { puts[key] = value }

            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values?.toSet() }

            override fun putInt(key: String, value: Int) = apply { puts[key] = value }

            override fun putLong(key: String, value: Long) = apply { puts[key] = value }

            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }

            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }

            override fun remove(key: String) = apply { removes += key }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                if (prefs.commitFails()) return false
                if (clear) prefs.backing.clear()
                removes.forEach { prefs.backing.remove(it) }
                prefs.backing.putAll(puts)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
