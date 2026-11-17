package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException

/**
 * ZC-705 (store-opener design, revision 2a, rule S4, section 3.4.4; the orchestrator's decision of 12 Oct; class H):
 * the ONE exception to "a new store is never kept with an unprotected key entry", and its fence.
 *
 * Why: no phone may end, after the update, with a wallet it cannot open that it could open before. A Keystore that
 * makes keys and cannot load them makes the library fall back at every try, and the chat storage needs seven new
 * stores at the first 2.13.0 start after 2.12.4. The rule: when a call for a CHAT STORE (its environment says
 * keepPlainAfterLimit, which createEncryptedPrefs alone sets) reaches the opener's limit BY ITS COUNT and the store it
 * then makes comes out unprotected again, the store is kept, counted and logged, and the other new chat stores of that
 * process are kept at their first such try. Never a set-aside bundle, a call with the default environment or the
 * recovery-phrase store; never after a lost key until the limit is reached by the count; the next process is strict.
 *
 * Today the first start already returns the unprotected store. Starts are separate processes ([StoreOpenerApi]).
 */
class NewChatStoreIsKeptAfterThreeRefusedStartsTest {
    private val name = "zchat_group_members_encrypted"

    private fun chatEnv(phone: KeysetPhone): Map<String, Any?> =
        mapOf(
            "removeStoreFile" to { file: String -> phone.remove(file) },
            "storeFileExists" to { file: String -> phone.exists(file) },
            "keepPlainAfterLimit" to true,
        )

    private fun start(
        phone: KeysetPhone,
        process: StoreOpenerApi.Process,
        store: String,
        env: Map<String, Any?> = chatEnv(phone),
        open: () -> SharedPreferences,
    ): Result<SharedPreferences> = runCatching { StoreOpenerApi.open(process, store, phone::plain, env = env, open = open) }

    private fun count(
        phone: KeysetPhone,
        store: String = name,
    ): Int? = phone.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)[store] as? Int

    private fun plainOnDisk(
        phone: KeysetPhone,
        store: String,
    ): Boolean = listOf(Keysets.KEY_ENTRY, Keysets.VALUE_ENTRY).all { (phone.entries(store)[it] as? String)?.startsWith("08") == true }

    private fun assertRefused(
        outcome: Result<SharedPreferences>,
        what: String,
    ) {
        assertTrue(
            "$what: a store that came out unprotected was returned: $outcome",
            outcome.exceptionOrNull() is EncryptedStoreUnavailableException,
        )
    }

    // (1) and (2)
    @Test
    fun `1 and 2 - two starts refuse the new chat store, the third keeps it`() {
        val phone = KeysetPhone()
        val library = phone.Library(name, plainLeft = 99)

        for (n in 1..2) {
            assertRefused(start(phone, StoreOpenerApi.newProcess(), name) { library.open() }, "start $n")
            assertFalse("start $n: the refused file is there", phone.exists(name))
            assertEquals("start $n: the count", n, count(phone))
        }

        val third = StoreOpenerApi.newProcess()
        val outcome = start(phone, third, name) { library.open() }

        assertTrue("the third start did not keep the store: $outcome", outcome.isSuccess)
        assertTrue("the kept store's key entries are not the unprotected ones on disk", plainOnDisk(phone, name))
        assertEquals("the guard's counter", 1, StoreOpenerApi.plainKeysetCount(third, name))
        assertEquals("the count of failed starts is still there", null, count(phone))
        val lines = StoreOpenerApi.guardLines(third).filter { it.contains("kept after three starts in a row") }
        assertEquals("one error-level text says it was kept after three starts: ${StoreOpenerApi.guardLines(third)}", 1, lines.size)
        assertTrue("the text does not name the store's kind", lines.single().contains(name))
        assertEquals("a copy was kept", emptyList<String>(), phone.copies())
    }

    // (3) and (4)
    @Test
    fun `3 and 4 - the other new chat stores of that process follow at their first try, the next process is strict`() {
        val phone = KeysetPhone()
        phone.put(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME, mapOf(name to 2))
        val process = StoreOpenerApi.newProcess()
        val first = phone.Library(name, plainLeft = 99)
        assertTrue("fixture: the third start did not keep the store", start(phone, process, name) { first.open() }.isSuccess)

        for (other in listOf("zchat_group_drafts_encrypted", "zchat_nostr_reactions_encrypted")) {
            val library = phone.Library(other, plainLeft = 99)
            val outcome = start(phone, process, other) { library.open() }
            assertTrue("$other was not kept in the same process: $outcome", outcome.isSuccess)
            assertEquals("$other: not kept at its first try", 1, library.calls)
            assertEquals("$other: not counted", 1, StoreOpenerApi.plainKeysetCount(process, other))
        }

        val next = "zchat_held_group_accept_encrypted"
        val outcome = start(phone, StoreOpenerApi.newProcess(), next) { phone.Library(next, 99).open() }
        assertRefused(outcome, "the next process")
        assertEquals("the next process: the count", 1, count(phone, next))
        assertFalse("the next process: the file is there", phone.exists(next))
    }

    // (5)
    @Test
    fun `5 - never for a set-aside bundle, the default environment or the recovery-phrase store`() {
        val bundle = "zchat_set_aside_" + "7e".repeat(32)
        val bundlePhone = KeysetPhone()
        val bundleLibrary = bundlePhone.Library(bundle, plainLeft = 99)
        val defaultPhone = KeysetPhone()
        val defaultLibrary = defaultPhone.Library(name, plainLeft = 99)
        val phrasePhone = KeysetPhone()
        val phrase = "co.electriccoin.zcash.encrypted"
        val phraseLibrary = phrasePhone.Library(phrase, plainLeft = 99)

        for (n in 1..3) {
            val viaBundle =
                runCatching {
                    StoreOpenerApi.openSetAsideBundle(StoreOpenerApi.newProcess(), bundle, bundlePhone::plain, userVisible = true) {
                        bundleLibrary.open()
                    }
                }
            assertRefused(viaBundle, "the bundle, start $n")
            val viaDefault = start(defaultPhone, StoreOpenerApi.newProcess(), name, env = emptyMap()) { defaultLibrary.open() }
            assertRefused(viaDefault, "the default environment, start $n")
            if (n == 3) {
                for ((what, outcome) in listOf("the bundle" to viaBundle, "the default environment" to viaDefault)) {
                    assertTrue(
                        "$what: the third start does not end 'cannot create encrypted storage': ${outcome.exceptionOrNull()?.message}",
                        outcome
                            .exceptionOrNull()
                            ?.message
                            .orEmpty()
                            .contains("Cannot create encrypted storage"),
                    )
                }
                assertFalse("the bundle was kept unprotected", plainOnDisk(bundlePhone, bundle))
                assertFalse("a store of the default environment was kept unprotected", plainOnDisk(defaultPhone, name))
            }

            val viaPhrase =
                runCatching {
                    StoreOpenerApi.openPhraseStoreGuarded(
                        StoreOpenerApi.newProcess(),
                        phrase,
                        phrasePhone::plain,
                        env = chatEnv(phrasePhone),
                    ) { phraseLibrary.open() }
                }
            assertEquals(
                "the recovery-phrase store, start $n: ${viaPhrase.exceptionOrNull()}",
                "NewStoreNotMadeException",
                viaPhrase.exceptionOrNull()?.javaClass?.simpleName,
            )
        }
    }

    // (6)
    @Test
    fun `6 - after a set-aside at the third start the store made after it is kept, and the copy holds every old entry`() {
        val phone = KeysetPhone()
        val original = Keysets.wrappedEntries() + mapOf("AQ-1" to "AU-1", "AQ-2" to "AU-2")
        phone.put(name, original)
        phone.put(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME, mapOf(name to 2))
        val library = phone.Library(name, plainLeft = 99)

        val outcome =
            start(phone, StoreOpenerApi.newProcess(), name) {
                if (phone.entries(name).keys.any { it.startsWith("AQ-") }) throw KeyStoreException("synthetic: the Keystore is silent")
                library.open()
            }

        assertTrue("the store made after the set-aside was not kept: $outcome", outcome.isSuccess)
        assertTrue(plainOnDisk(phone, name))
        assertEquals("the kept copy", original, phone.entries(phone.copies().single()))
    }

    // (7)
    @Test
    fun `7 - after a lost key the new file is refused, and kept only at the third counted start after it`() {
        val phone = KeysetPhone()
        val original = Keysets.wrappedEntries() + mapOf("AQ-1" to "AU-1")
        phone.put(name, original)
        val library = phone.Library(name, plainLeft = 99)
        val open = {
            if (phone.entries(name).keys.any { it.startsWith("AQ-") }) throw AEADBadTagException("synthetic: the key is gone")
            library.open()
        }

        val lost = start(phone, StoreOpenerApi.newProcess(), name, open = open)
        assertRefused(lost, "the start with the lost key")
        assertEquals("the copy", original, phone.entries(phone.copies().single()))
        assertEquals("the store made after the copy was not emptied again", emptyMap<String, Any?>(), phone.entries(name))

        for (n in 1..2) {
            assertRefused(start(phone, StoreOpenerApi.newProcess(), name, open = open), "counted start $n after the lost key")
            assertEquals(n, count(phone))
        }
        val third = start(phone, StoreOpenerApi.newProcess(), name, open = open)
        assertTrue("not kept at the third counted start after the lost key: $third", third.isSuccess)
        assertTrue(plainOnDisk(phone, name))
    }

    // (8)
    @Test
    fun `8 - source - one call site sets keepPlainAfterLimit, and the app's environment leaves it false`() {
        val sources = StoreOpenerApi.mainSources()
        val sets =
            sources.flatMap { (path, code) -> Regex("""keepPlainAfterLimit\s*=\s*true""").findAll(code).map { path to it.range.first } }
        val preferences = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt"
        assertEquals("keepPlainAfterLimit = true is set elsewhere: $sets", listOf(preferences), sets.map { it.first })
        assertTrue(
            "it is not set in createEncryptedPrefs",
            StoreOpenerApi.declaration(sources.getValue(preferences), "createEncryptedPrefs").contains("keepPlainAfterLimit = true"),
        )
        val opener = sources.getValue("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/EncryptedStoreOpener.kt")
        assertTrue(
            "appStoreOpenEnv does not leave it false by default",
            StoreOpenerApi.declaration(opener, "appStoreOpenEnv").contains("keepPlainAfterLimit: Boolean = false"),
        )
    }
}
