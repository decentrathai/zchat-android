package co.electriccoin.zcash.ui.screen.chat.datasource

import androidx.security.crypto.MasterKey
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ZC-868 (register, high; review REVIEW_T7aR_2_20261020.md finding 2): the guard before a master-key call
 * (MasterKeyGuard) counts a kept copy of a store that could not be read (`<store>__unreadable_<ms>`, set aside by the
 * opener and kept for a later restore) as a store made with the key. A copy is set aside exactly because its key could
 * not open it, and no Reset deletes the AI store's copies: when the AI key is really gone and an AI copy holding both
 * key entries is on the phone, every new AI store fails for good (the AI tab never works again), and a claim of a
 * wallet whose set-aside chats hold AI data throws (that wallet cannot be restored). Before 2fbdfcb87 a new key was
 * made. The rule: only LIVE stores (no copy) count for "stores made with this key exist"; a live store keeps its
 * protection, a copy never blocks a new key.
 *
 * Files are on a KeysetPhone with a folder, so they are on disk as the app would find them; the guard's Keystore
 * question is its replaceable call, reached by name.
 */
class SetAsideCopiesNeverBlockANewKeyTest {
    private var pristine: Any? = null

    @After
    fun tearDown() {
        if (pristine != null) keystoreCall().set(MasterKeyGuard, pristine)
    }

    @Test
    fun `an AI copy with key entries and the AI key really gone - the new AI store makes its key`() {
        val (files, context) = phone()
        files.put(PHRASE_STORE, Keysets.wrappedEntries())
        files.put("$AI_STORE${COPY}1700000000000", Keysets.wrappedEntries())
        keystoreSaysAbsent()
        val calls = calls(files)

        val thrown = runCatching { StoreOpenerApi.openEncryptedFile(context, AI_STORE, AI_KEY, calls) }.exceptionOrNull()

        assertTrue("a kept AI copy blocked the new AI store for good: $thrown", thrown == null)
        assertTrue("the new AI store made no key: ${calls.asked}", calls.asked.any { it.startsWith("masterKey:") })
    }

    @Test
    fun `only kept copies under the default key - a new store makes the key`() {
        val (files, context) = phone()
        files.put("$CHAT_STORE${COPY}1700000000000", Keysets.wrappedEntries())
        keystoreSaysAbsent()
        val calls = calls(files)

        // Asked first: once the new store below is made it is a live store under the key, and the stand-in Keystore
        // still answers "absent", so the wallet's start would rightly stop then.
        assertTrue(
            "a kept copy stopped the wallet's start",
            runCatching { MasterKeyGuard.checkBeforeWalletStart(context) }.isSuccess,
        )
        val thrown = runCatching { StoreOpenerApi.openEncryptedFile(context, NEW_STORE, DEFAULT_KEY, calls) }.exceptionOrNull()

        assertTrue("a kept copy blocked a new key: $thrown", thrown == null)
        assertTrue(calls.asked.any { it.startsWith("masterKey:") })
    }

    @Test
    fun `guard - a live store beside a copy keeps its protection`() {
        for (live in listOf(PHRASE_STORE, CHAT_STORE)) {
            val (files, context) = phone()
            files.put(live, Keysets.wrappedEntries())
            files.put("$CHAT_STORE${COPY}1700000000000", Keysets.wrappedEntries())
            keystoreSaysAbsent()
            val calls = calls(files)

            val thrown = runCatching { StoreOpenerApi.openEncryptedFile(context, NEW_STORE, DEFAULT_KEY, calls) }.exceptionOrNull()

            assertFalse("a key was made beside the live store $live: ${calls.asked}", calls.asked.any { it.startsWith("masterKey:") })
            assertTrue("the open beside $live did not fail closed: $thrown", thrown is EncryptedStoreUnavailableException)
        }
    }

    // ------------------------------------------------------------------ fixture

    private fun phone(): Pair<KeysetPhone, KeysetPhoneContext> {
        val data = Files.createTempDirectory("zchat-zc868-copies").toFile().apply { deleteOnExit() }
        val files = KeysetPhone(File(data, "shared_prefs").apply { mkdirs() })
        return files to KeysetPhoneContext(files, dataDir = data)
    }

    private fun keystoreSaysAbsent() {
        val field = keystoreCall()
        if (pristine == null) pristine = field.get(MasterKeyGuard)
        field.set(MasterKeyGuard, { _: String -> false })
    }

    private fun keystoreCall() =
        MasterKeyGuard::class.java.declaredFields
            .first { it.name == "keystoreHasKey" }
            .apply { isAccessible = true }

    private fun calls(files: KeysetPhone): StoreOpenerApi.FileCalls =
        StoreOpenerApi.FileCalls(
            createWithMasterKey = { name, _ -> files.Library(name).open() },
            createByKeyName = { name, _ -> files.Library(name).open() },
        )

    private companion object {
        const val PHRASE_STORE = "co.electriccoin.zcash.encrypted"
        const val CHAT_STORE = "zchat_preferences"
        const val NEW_STORE = "zchat_contact_book_enc"
        const val AI_STORE = "zchat_ai_prefs"
        const val AI_KEY = "zchat_ai_master_key"
        const val COPY = "__unreadable_"
        const val DEFAULT_KEY = MasterKey.DEFAULT_MASTER_KEY_ALIAS
    }
}
