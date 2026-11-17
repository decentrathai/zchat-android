package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException

/**
 * ZC-705 (store-opener design, revision 2a, rules S8 and S13, sections 3.4.3 and 5.2, class E; the code check's should
 * 7): one way to open an encrypted preferences file, `openEncryptedFile`. A file that has both key entries is opened
 * by the key's NAME, so no master-key call is made: that call (MasterKey.Builder(…).build()) makes a NEW Keystore key
 * whenever the Keystore says the name is absent, and today it runs before every open of every store. A new file still
 * gets the master key made first.
 *
 * The three library calls are replaced through the function's one seam parameter (`EncryptedFileCalls`, reached by
 * name through a proxy, [StoreOpenerApi.openEncryptedFile]); the file is a map. The wallet SDK's own opener still makes
 * the master-key call at every wallet start: this holds for the app's own openers only. JVM only: no phone check
 * deletes a Keystore key.
 */
class ExistingFileIsOpenedWithoutMakingAKeyTest {
    private val name = "zchat_e2e_keys_encrypted"

    private fun calls(
        phone: KeysetPhone,
        byName: (String, String) -> SharedPreferences = { file, _ -> phone.Library(file).open() },
    ) = StoreOpenerApi.FileCalls(
        createWithMasterKey = { file, _ -> phone.Library(file).open() },
        createByKeyName = byName,
    )

    private fun openFile(
        phone: KeysetPhone,
        calls: StoreOpenerApi.FileCalls,
        keyName: String = DEFAULT_KEY,
    ): SharedPreferences = StoreOpenerApi.openEncryptedFile(KeysetPhoneContext(phone), name, keyName, calls)

    @Test
    fun `an existing file - no master-key call, the by-name create with the file's name and the key name`() {
        val phone = KeysetPhone()
        phone.put(name, Keysets.wrappedEntries() + ("AQ-1" to "AU-1"))
        val calls = calls(phone)

        openFile(phone, calls)

        assertEquals(listOf("createByKeyName:$name:$DEFAULT_KEY"), calls.asked)
    }

    @Test
    fun `a new file - the master-key call first, then create with it`() {
        val phone = KeysetPhone()
        val calls = calls(phone)

        openFile(phone, calls)

        assertEquals(listOf("masterKey:$DEFAULT_KEY", "createWithMasterKey:$name:master-key:$DEFAULT_KEY"), calls.asked)
    }

    @Test
    fun `a half-made file with one key entry - as a new file`() {
        val phone = KeysetPhone()
        phone.put(name, mapOf(Keysets.KEY_ENTRY to Keysets.wrapped(Keysets.NAMES_TEMPLATE)))
        val calls = calls(phone)

        openFile(phone, calls)

        assertEquals(listOf("masterKey:$DEFAULT_KEY", "createWithMasterKey:$name:master-key:$DEFAULT_KEY"), calls.asked)
    }

    @Test
    fun `the AI key name is handed to both branches`() {
        val existing = KeysetPhone().apply { put(name, Keysets.wrappedEntries()) }
        val existingCalls = calls(existing)
        openFile(existing, existingCalls, AI_KEY)
        assertEquals(listOf("createByKeyName:$name:$AI_KEY"), existingCalls.asked)

        val fresh = KeysetPhone()
        val freshCalls = calls(fresh)
        openFile(fresh, freshCalls, AI_KEY)
        assertEquals(listOf("masterKey:$AI_KEY", "createWithMasterKey:$name:master-key:$AI_KEY"), freshCalls.asked)
    }

    @Test
    fun `an absent key - a temporary error that clears nothing, and at the third counted start the store is set aside`() {
        val phone = KeysetPhone()
        val original = Keysets.wrappedEntries() + mapOf("AQ-1" to "AU-1", "AQ-2" to "AU-2")
        phone.put(name, original)
        val calls = calls(phone) { _, _ -> throw InvalidKeyException("synthetic: the Keystore cannot load the key") }

        for (start in 1..2) {
            val outcome =
                runCatching {
                    StoreOpenerApi.open(StoreOpenerApi.newProcess(), name, phone::plain) {
                        StoreOpenerApi.openEncryptedFile(KeysetPhoneContext(phone), name, DEFAULT_KEY, calls)
                    }
                }
            val error = outcome.exceptionOrNull()
            assertTrue("start $start: not the typed error: $outcome", error is EncryptedStoreUnavailableException)
            assertFalse("start $start: an absent key reads as a lost key", isPermanentKeyLoss(error!!))
            assertEquals("start $start: something was cleared", original, phone.entries(name))
            assertEquals("start $start: the count", start, phone.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)[name])
        }
        assertFalse("a master-key call was made for an existing file", calls.asked.any { it.startsWith("masterKey") })

        StoreOpenerApi.open(StoreOpenerApi.newProcess(), name, phone::plain) {
            StoreOpenerApi.openEncryptedFile(KeysetPhoneContext(phone), name, DEFAULT_KEY, calls)
        }

        assertEquals("the kept copy does not hold every entry", original, phone.entries(phone.copies().single()))
        assertTrue("the store made after the set-aside did not go the new-file way", calls.asked.last().startsWith("createWithMasterKey"))
        assertEquals("masterKey:$DEFAULT_KEY", calls.asked[calls.asked.size - 2])
    }

    @Test
    fun `source - the library's create and the master-key call live only behind openEncryptedFile`() {
        val sources = StoreOpenerApi.mainSources().filterKeys { it.startsWith("ui-lib/src/main/") }
        val opener = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/EncryptedStoreOpener.kt"
        // The three stores of ZC-733-A follow in that batch: the known exceptions of this one.
        val laterBatch =
            setOf(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ContactBookImpl.kt",
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/AvatarStore.kt",
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiPreferences.kt",
            )
        val create = Regex("""EncryptedSharedPreferences\s*\.\s*create\(""")
        val masterKey = Regex("""MasterKey\s*\.\s*Builder\(""")
        val outside =
            sources
                .filter { (path, code) -> path != opener && path !in laterBatch }
                .filter { (_, code) -> create.containsMatchIn(code) || masterKey.containsMatchIn(code) }
                .keys
        assertEquals("the library's create or the master-key call outside openEncryptedFile", emptySet<String>(), outside)

        val code = sources[opener] ?: error("no $opener")
        assertTrue("there is no openEncryptedFile", "fun openEncryptedFile(" in code)
        val implementation = code.substringAfter("object LibraryEncryptedFileCalls", "").substringBefore("\n}")
        assertTrue("the real implementation behind openEncryptedFile is not LibraryEncryptedFileCalls", implementation.isNotEmpty())
        val rest = code.replace(implementation, "")
        assertFalse("the opener calls the library's create outside the implementation", create.containsMatchIn(rest))
        assertFalse("the opener makes a master key outside the implementation", masterKey.containsMatchIn(rest))
        val byName = StoreOpenerApi.declaration(implementation, "createByKeyName")
        assertFalse("the by-name branch makes a master key", masterKey.containsMatchIn(byName) || "masterKey(" in byName)

        val preferences = sources.getValue("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt")
        assertTrue(
            "createEncryptedPrefs does not open through openEncryptedFile",
            StoreOpenerApi.declaration(preferences, "createEncryptedPrefs").contains("openEncryptedFile(context, name)"),
        )
        val bundles = sources.getValue("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ChatWalletOwnershipStores.kt")
        val bundleOpen = bundles.substringAfter("class EncryptedSetAsideBundles", "").substringAfter("private fun open(", "")
        assertTrue(
            "the bundle open does not open through openEncryptedFile",
            bundleOpen.substringBefore("override fun").contains("openEncryptedFile("),
        )
    }

    private companion object {
        const val DEFAULT_KEY = "_androidx_security_master_key_"
        const val AI_KEY = "zchat_ai_master_key"
    }
}
