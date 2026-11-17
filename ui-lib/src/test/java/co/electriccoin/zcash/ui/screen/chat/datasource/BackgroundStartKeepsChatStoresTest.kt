package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.walletstartup.RecordingPrefs
import org.junit.Test
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * R3-3 (2), round-2 report section 4 item 9 — background starts (boot, scheduled jobs) must not move a chat store
 * towards being recreated without the user seeing the start-up safety screen.
 *
 * KR-1 counts the launches in a row a Keystore error stopped, and on the third it treats the error as permanent:
 * it keeps a copy and recreates the store (the chats look empty). A background start opens the same stores but
 * shows nothing, so two background starts plus one launch, or three background starts, recreated the store
 * before the user had seen any safety screen. A background start now never counts and never clears: it stops,
 * and the store is left for a start the user sees.
 */
class BackgroundStartKeepsChatStoresTest {
    private val storeName = "zchat_e2e_keys_encrypted"
    private val chatKeysOnDisk: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-1",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "keyset-ciphertext-2",
            "AQx1-encrypted-key" to "AUz9-encrypted-value",
        )

    private class Files {
        val byName = LinkedHashMap<String, RecordingPrefs>()

        fun file(name: String): SharedPreferences = byName.getOrPut(name) { RecordingPrefs(allowWrites = true) }

        fun entries(name: String): Map<String, Any?> = byName[name]?.entries.orEmpty()

        fun copies(): List<String> = byName.keys.filter { it.contains(UNREADABLE_STORE_COPY_MARKER) }
    }

    private fun phone(): Files = Files().apply { (file(storeName) as RecordingPrefs).entries.putAll(chatKeysOnDisk) }

    private fun open(
        files: Files,
        userVisible: Boolean,
        open: () -> SharedPreferences,
    ) = runCatching {
        openEncryptedStore(name = storeName, open = open, plainPrefs = files::file, sleep = {}, userVisible = userVisible)
    }

    @Test
    fun background_starts_never_count_toward_recreating_a_chat_store() {
        val files = phone()
        val broken: () -> SharedPreferences = { throw KeyStoreException("the master key exists but is unusable") }

        repeat(5) { index ->
            val outcome = open(files, userVisible = false, open = broken)
            assertTrue(
                outcome.exceptionOrNull() is EncryptedStoreUnavailableException,
                "background start ${index + 1}: expected it to stop, got $outcome",
            )
            assertEquals(chatKeysOnDisk, files.entries(storeName), "background start ${index + 1} cleared the chat keys")
            assertEquals(emptyList(), files.copies(), "background start ${index + 1} set the chat keys aside")
        }

        // The user's own launches still get the full three chances, each one showing the safety screen.
        repeat(2) { index ->
            val outcome = open(files, userVisible = true, open = broken)
            assertTrue(outcome.exceptionOrNull() is EncryptedStoreUnavailableException, "launch ${index + 1}: got $outcome")
            assertEquals(chatKeysOnDisk, files.entries(storeName), "launch ${index + 1} cleared the chat keys")
        }
    }

    @Test
    fun a_background_start_never_clears_a_store_whose_key_is_gone() {
        val files = phone()

        val outcome =
            open(files, userVisible = false) {
                if (files.entries(storeName).isNotEmpty()) throw AEADBadTagException("tag mismatch") else RecordingPrefs()
            }

        assertTrue(outcome.exceptionOrNull() is EncryptedStoreUnavailableException, "expected the background start to stop, got $outcome")
        assertEquals(chatKeysOnDisk, files.entries(storeName), "a background start recreated the chat store")
        assertEquals(emptyList(), files.copies())
    }

    // Pin: holds before and after the fix.
    @Test
    fun a_background_start_opens_a_healthy_store() {
        val files = phone()
        val encrypted = RecordingPrefs()

        val outcome = open(files, userVisible = false) { encrypted }

        assertSame(encrypted, outcome.getOrNull())
        assertEquals(chatKeysOnDisk, files.entries(storeName))
    }
}
