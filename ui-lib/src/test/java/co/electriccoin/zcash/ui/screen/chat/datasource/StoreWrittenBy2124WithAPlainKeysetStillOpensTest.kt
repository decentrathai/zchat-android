package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.KeysetHandle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStoreException

/**
 * ZC-705 (store-opener design, revision 2a, rule S6, section 5.2, class D; compat-2124 rules 1 and 3). A GUARD: every
 * case passes before and after the fix.
 *
 * ZChat 2.12.4 cleared a chat store and made it again at the first Keystore error, inside the same call, so a new file
 * was made exactly when the Keystore had just failed: on such a phone the library's own fallback can have left a
 * store whose key sets are in the clear AND that holds data. A phone updated in place must open it as before, with
 * every entry, and this build must not change a byte of it.
 *
 * [Frozen2124] is 2.12.4's opener; the store is then opened by this build's opener; the file is a real file in a
 * temporary folder. The key sets are made with the encryption library in the test.
 */
class StoreWrittenBy2124WithAPlainKeysetStillOpensTest {
    private val name = "zchat_e2e_keys_encrypted"
    private val written = linkedMapOf("contact-1" to "key-material-1", "contact-2" to "key-material-2", "contact-3" to "key-material-3")

    private class Arrived(
        val phone: KeysetPhone,
        val folder: File,
    )

    private fun arriveAs2124Left(): Arrived {
        val folder = Files.createTempDirectory("zchat-zc705-2124").toFile().apply { deleteOnExit() }
        val phone = KeysetPhone(folder)
        val store = Frozen2124(KeysetPhoneContext(phone)).open(name)
        val editor = store.edit()
        written.forEach { (key, value) -> editor.putString(key, value) }
        assertTrue("fixture: 2.12.4's store did not take the entries", editor.commit())
        return Arrived(phone, folder)
    }

    @Test
    fun `it arrives as 2-12-4 left it - both key entries plain, and the entries are there`() {
        val arrived = arriveAs2124Left()
        val entries = arrived.phone.entries(name)
        assertTrue("fixture: the key entry is not plain", (entries[Keysets.KEY_ENTRY] as String).startsWith("08"))
        assertTrue("fixture: the value entry is not plain", (entries[Keysets.VALUE_ENTRY] as String).startsWith("08"))
        assertEquals("fixture: the entries", written, decrypt(entries))
    }

    @Test
    fun `this build opens it in place - no copy, no count, nothing removed, every byte as 2-12-4 left it`() {
        val arrived = arriveAs2124Left()
        val phone = arrived.phone
        val file = File(arrived.folder, "$name.xml")
        val bytes = file.readBytes()
        val entries = phone.entries(name)
        val library = phone.Library(name)

        val store =
            StoreOpenerApi.open(
                StoreOpenerApi.newProcess(),
                name,
                phone::plain,
                env =
                    mapOf(
                        "removeStoreFile" to { f: String -> phone.remove(f) },
                        "storeFileExists" to { f: String -> phone.exists(f) },
                        "keepPlainAfterLimit" to true,
                    ),
            ) { library.open() }

        assertEquals("not the store over 2.12.4's file", 1, (store as KeysetPhone.StoreHandle).serial)
        assertEquals("a copy was kept", emptyList<String>(), phone.copies())
        assertEquals("a count of failed starts", null, phone.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)[name])
        assertEquals("a file was removed", emptyList<String>(), phone.removed)
        assertArrayEquals("a byte of 2.12.4's file changed", bytes, file.readBytes())
        assertEquals("an entry changed", entries, phone.entries(name))
        assertEquals("the entries no longer decrypt with the key sets in the file", written, decrypt(phone.entries(name)))
    }

    @Test
    fun `the seven store names of 2-12-4 and the default key name are still what this build opens`() {
        val code = StoreOpenerApi.mainSource("ui/screen/chat/datasource/ZchatPreferences.kt")
        for ((constant, value) in NAMES_2124) {
            val declared = Regex("""const val $constant = "${Regex.escape(value)}"""").containsMatchIn(code)
            assertTrue("$constant is no longer \"$value\"", declared)
            assertTrue("this build no longer opens $constant with createEncryptedPrefs", "createEncryptedPrefs(context, $constant)" in code)
        }
        assertTrue("the ratchet store is no longer opened", "createEncryptedPrefs(context, \"zchat_ratchet_state\")" in code)

        val declaration = StoreOpenerApi.declaration(code, "createEncryptedPrefs")
        assertTrue("createEncryptedPrefs names a key of its own", "_master_key" !in declaration && "masterKeyName" !in declaration)
        assertTrue(
            "createEncryptedPrefs passes a key name",
            Regex("""MasterKey\s*\.Builder\(\s*context\s*,""").find(declaration) == null &&
                Regex("""openEncryptedFile\(\s*context\s*,\s*name\s*,""").find(declaration) == null,
        )
        val opener = StoreOpenerApi.mainSource("ui/screen/chat/datasource/EncryptedStoreOpener.kt")
        if ("fun openEncryptedFile(" in opener) {
            assertTrue(
                "openEncryptedFile's default key is not the library's default name",
                StoreOpenerApi
                    .declaration(opener, "openEncryptedFile")
                    .contains("masterKeyName: String = MasterKey.DEFAULT_MASTER_KEY_ALIAS"),
            )
        }
    }

    /** The entries of a store file, decrypted with the two key sets read from the same file (both in the clear). */
    @Suppress("DEPRECATION") // getPrimitive(Class): the call the library itself makes in this version
    private fun decrypt(entries: Map<String, Any?>): Map<String, String> {
        val names = read(entries[Keysets.KEY_ENTRY] as String).getPrimitive(DeterministicAead::class.java)
        val values = read(entries[Keysets.VALUE_ENTRY] as String).getPrimitive(Aead::class.java)
        return entries
            .filterKeys { it != Keysets.KEY_ENTRY && it != Keysets.VALUE_ENTRY }
            .map { (key, value) ->
                String(names.decryptDeterministically(Keysets.unhex(key), name.toByteArray())) to
                    String(values.decrypt(Keysets.unhex(value as String), Keysets.unhex(key)))
            }.toMap()
    }

    private fun read(hex: String): KeysetHandle = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(Keysets.unhex(hex)))

    /**
     * `createEncryptedPrefs` of `ZchatPreferences.kt@v2.12.4:1490-1532`, copied word for word (comment lines left
     * out). Its two library names, `MasterKey` and `EncryptedSharedPreferences`, are the stand-ins nested here: the
     * create fails once with a Keystore error and, at the second call, writes the store's two key sets unencrypted (the
     * library's own fallback) and returns a store that encrypts with them.
     */
    @Suppress(
        "ktlint:standard:multiline-expression-wrapping",
        "ktlint:standard:chain-method-continuation",
        "ktlint:standard:function-signature",
    )
    private class Frozen2124(
        private val context: Context,
    ) {
        fun open(name: String): SharedPreferences = createEncryptedPrefs(context, name)

        private fun createEncryptedPrefs(context: Context, name: String): SharedPreferences {
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                return EncryptedSharedPreferences.create(
                    context,
                    name,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                val recoverable = e is java.security.GeneralSecurityException || e is java.io.IOException
                if (!recoverable) {
                    throw IllegalStateException(
                        "CRITICAL: Cannot create encrypted storage for $name. " +
                            "E2E keys cannot be stored safely. Device may not support Android Keystore.",
                        e
                    )
                }
                Log.e("ZchatPreferences", "Encrypted store $name corrupt (${e.javaClass.simpleName}), clearing and retrying", e)
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    name,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
        }

        private class MasterKey {
            enum class KeyScheme { AES256_GCM }

            class Builder(
                @Suppress("unused") private val context: Context
            ) {
                fun setKeyScheme(
                    @Suppress("unused") scheme: KeyScheme
                ): Builder = this

                fun build(): MasterKey = MasterKey()
            }
        }

        private object EncryptedSharedPreferences {
            enum class PrefKeyEncryptionScheme { AES256_SIV }

            enum class PrefValueEncryptionScheme { AES256_GCM }

            private var calls = 0

            @Suppress("UNUSED_PARAMETER", "DEPRECATION")
            fun create(
                context: Context,
                name: String,
                masterKey: MasterKey,
                keyScheme: PrefKeyEncryptionScheme,
                valueScheme: PrefValueEncryptionScheme,
            ): SharedPreferences {
                calls++
                if (calls % 2 == 1) throw KeyStoreException("the Keystore check failed while the file was made")
                // The library's fallback: the two key sets go into the file unencrypted.
                val names = Keysets.newHandle(Keysets.NAMES_TEMPLATE)
                val values = Keysets.newHandle(Keysets.VALUES_TEMPLATE)
                val file = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                file
                    .edit()
                    .putString(Keysets.KEY_ENTRY, Keysets.plainOf(names))
                    .putString(Keysets.VALUE_ENTRY, Keysets.plainOf(values))
                    .commit()
                return Encrypting(
                    file,
                    name,
                    names.getPrimitive(DeterministicAead::class.java),
                    values.getPrimitive(Aead::class.java),
                )
            }
        }

        /** The returned store: entry names and values are encrypted with the file's own key sets, as the library does. */
        private class Encrypting(
            private val file: SharedPreferences,
            private val fileName: String,
            private val names: DeterministicAead,
            private val values: Aead,
        ) : SharedPreferences by file {
            override fun edit(): SharedPreferences.Editor = EncryptingEditor(file.edit())

            private inner class EncryptingEditor(
                private val inner: SharedPreferences.Editor,
            ) : SharedPreferences.Editor by inner {
                override fun putString(
                    key: String,
                    value: String?
                ): SharedPreferences.Editor =
                    apply {
                        val encryptedName = Keysets.hex(names.encryptDeterministically(key.toByteArray(), fileName.toByteArray()))
                        val encryptedValue = values.encrypt(value.orEmpty().toByteArray(), Keysets.unhex(encryptedName))
                        inner.putString(encryptedName, Keysets.hex(encryptedValue))
                    }
            }
        }
    }

    private companion object {
        /** The seven encrypted chat stores of 2.12.4 (`ZchatPreferences.kt@v2.12.4:1403-1448`) with their names. */
        val NAMES_2124 =
            listOf(
                "E2E_PREFS_NAME" to "zchat_e2e_keys_encrypted",
                "GROUP_KEYS_PREFS_NAME" to "zchat_group_keys_encrypted",
                "DECRYPTED_TEXT_PREFS_NAME" to "zchat_decrypted_text_encrypted",
                "MESSAGE_REQUEST_PREFS_NAME" to "zchat_message_requests_encrypted",
                "GROUP_MSG_PREFS_NAME_ENC" to "zchat_group_messages_encrypted",
                "PENDING_MSG_PREFS_NAME_ENC" to "zchat_pending_messages_encrypted",
            )
    }
}
