package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.walletstartup.RecordingPrefs
import org.junit.After
import org.junit.Test
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * R4-5, round-3 report section 4 item 8 — the set-aside bundles of [ChatWalletOwnership] are chat stores too, and
 * they must follow the same rule as the live ones (R3-3): only a start the user sees may count a launch towards
 * recreating one or recreate it.
 *
 * A bundle ("zchat_set_aside_<tag>") holds ANOTHER recovery phrase's whole chat history — every message, contact
 * and chat key that was set aside when this phone's wallet changed. [EncryptedSetAsideBundles] opened it with the
 * default of [openEncryptedStore], so a background start (boot, a scheduled job) that touched a bundle while the
 * Keystore was not answering counted a launch, and the third such start emptied the bundle and recreated it
 * without the user ever seeing the start-up safety screen.
 */
class SetAsideBundleBackgroundStartTest {
    private val bundleName = SET_ASIDE_PREFS_PREFIX + "a".repeat(64)
    private val setAsideChats: Map<String, Any?> =
        mapOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-1",
            "store:zchat_preferences" to "AUz9-encrypted-bundle",
            "__complete__" to "AUz9-encrypted-complete",
        )

    private val startWasUserVisible = ChatStoreStart.userVisible

    @After
    fun restoreStartFlag() {
        ChatStoreStart.userVisible = startWasUserVisible
    }

    private class Files {
        val byName = LinkedHashMap<String, RecordingPrefs>()

        fun file(name: String): SharedPreferences = byName.getOrPut(name) { RecordingPrefs(allowWrites = true) }

        fun entries(name: String): Map<String, Any?> = byName[name]?.entries.orEmpty()

        fun copies(): List<String> = byName.keys.filter { it.contains(UNREADABLE_STORE_COPY_MARKER) }
    }

    private fun phone(): Files = Files().apply { (file(bundleName) as RecordingPrefs).entries.putAll(setAsideChats) }

    /** Opens the bundle exactly as [EncryptedSetAsideBundles] does: the production default decides userVisible. */
    private fun openBundle(
        files: Files,
        open: () -> SharedPreferences,
    ) = runCatching { openSetAsideBundle(name = bundleName, open = open, plainPrefs = files::file) }

    @Test
    fun a_background_start_never_counts_toward_recreating_a_set_aside_bundle() {
        ChatStoreStart.userVisible = false
        val files = phone()
        val broken: () -> SharedPreferences = { throw KeyStoreException("the master key exists but is unusable") }

        repeat(5) { index ->
            val outcome = openBundle(files, broken)
            assertTrue(
                outcome.exceptionOrNull() is EncryptedStoreUnavailableException,
                "background start ${index + 1}: expected it to stop, got $outcome",
            )
            assertEquals(setAsideChats, files.entries(bundleName), "background start ${index + 1} emptied the set-aside chats")
            assertEquals(emptyList(), files.copies(), "background start ${index + 1} recreated the bundle")
        }
    }

    @Test
    fun a_background_start_never_recreates_a_bundle_whose_key_is_gone() {
        ChatStoreStart.userVisible = false
        val files = phone()

        val outcome =
            openBundle(files) {
                if (files.entries(bundleName).isNotEmpty()) throw AEADBadTagException("tag mismatch") else RecordingPrefs()
            }

        assertTrue(outcome.exceptionOrNull() is EncryptedStoreUnavailableException, "expected the background start to stop, got $outcome")
        assertEquals(setAsideChats, files.entries(bundleName), "a background start recreated the set-aside bundle")
        assertEquals(emptyList(), files.copies())
    }

    // Pin: holds before and after the fix.
    @Test
    fun a_start_the_user_sees_still_gets_its_three_chances_and_then_keeps_a_copy() {
        ChatStoreStart.userVisible = true
        val files = phone()
        val broken: () -> SharedPreferences = { throw KeyStoreException("the master key exists but is unusable") }

        repeat(2) { index ->
            val outcome = openBundle(files, broken)
            assertTrue(outcome.exceptionOrNull() is EncryptedStoreUnavailableException, "launch ${index + 1}: got $outcome")
            assertEquals(setAsideChats, files.entries(bundleName), "launch ${index + 1} emptied the set-aside chats")
        }

        val third =
            openBundle(files) {
                if (files.entries(bundleName).isNotEmpty()) throw KeyStoreException("still unusable") else RecordingPrefs()
            }

        assertTrue(third.isSuccess, "the third launch must recreate the bundle, got $third")
        assertEquals(1, files.copies().size)
        assertEquals(setAsideChats, files.entries(files.copies().single()))
    }

    // Pin: holds before and after the fix.
    @Test
    fun a_background_start_opens_a_healthy_bundle() {
        ChatStoreStart.userVisible = false
        val files = phone()
        val encrypted = RecordingPrefs()

        val outcome = openBundle(files) { encrypted }

        assertSame(encrypted, outcome.getOrNull())
        assertEquals(setAsideChats, files.entries(bundleName))
    }
}
