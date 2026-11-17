package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStoreException

/**
 * ZC-705 (register ZC-705, medium; store-opener design, revision 2a, sections 3.4.1, 3.4.2 and 5.2, class B): a NEW
 * store file whose key entry came out of the encryption library unencrypted (its own fallback when the Keystore check
 * fails while it makes the file) is never kept: it is put back as it was (removed, or emptied when it was there and
 * empty) and the try fails, so the opener's retries and its count of failed starts run as for any temporary error.
 * In the main process only. A file that held anything before the call is never touched.
 *
 * Today the opener takes whatever the library returns: the first store, with its key material in the clear on disk.
 * The library is a stand-in ([KeysetPhone.Library]) that writes key sets made with the real library; the files are
 * maps ([KeysetPhone]); case 7 runs on a real temporary folder. All names are synthetic.
 */
class NewStoreWithCleartextKeysetIsRefusedTest {
    private val name = "zchat_held_group_accept_encrypted"

    private fun phoneEnv(
        phone: KeysetPhone,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        mapOf<String, Any?>(
            REMOVE to { file: String -> phone.remove(file) },
            EXISTS to { file: String -> phone.exists(file) },
        ) + extra

    private fun open(
        phone: KeysetPhone,
        process: StoreOpenerApi.Process = StoreOpenerApi.newProcess(),
        userVisible: Boolean = true,
        env: Map<String, Any?> = phoneEnv(phone),
        open: () -> SharedPreferences,
    ): SharedPreferences = StoreOpenerApi.open(process, name, phone::plain, userVisible = userVisible, env = env, open = open)

    private fun assertNoPlainEntry(
        phone: KeysetPhone,
        file: String,
    ) {
        for (entry in listOf(Keysets.KEY_ENTRY, Keysets.VALUE_ENTRY)) {
            val value = phone.entries(file)[entry] as? String
            assertTrue("$file has no $entry", value != null)
            assertFalse("$file keeps an unprotected $entry on disk", value!!.startsWith("08"))
        }
    }

    private fun count(phone: KeysetPhone): Int? = phone.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)[name] as? Int

    // (1) FAILS today: the first store is returned with its key material in the clear.
    @Test
    fun `1a - a new file made in the clear is refused and made again - the second store is returned`() {
        val phone = KeysetPhone()
        val library = phone.Library(name, plainLeft = 1)

        val store = open(phone, env = emptyMap()) { library.open() }

        assertEquals("the store made in the clear was returned", 2, (store as KeysetPhone.StoreHandle).serial)
        assertNoPlainEntry(phone, name)
    }

    @Test
    fun `1b - with the app's remove function the refused file is removed once, then made again protected`() {
        val phone = KeysetPhone()
        val library = phone.Library(name, plainLeft = 1)

        val store = open(phone) { library.open() }

        assertEquals("the store made in the clear was returned", 2, (store as KeysetPhone.StoreHandle).serial)
        assertEquals("the refused new file was not removed once", listOf(name), phone.removed)
        assertNoPlainEntry(phone, name)
        assertEquals("a count of failed starts was written for a store that opened", null, count(phone))
    }

    // (2)
    @Test
    fun `2 - every try comes out in the clear - the typed error, no file, nothing else touched, one count`() {
        for (userVisible in listOf(true, false)) {
            val phone = KeysetPhone()
            phone.put("zchat_e2e_keys_encrypted", Keysets.wrappedEntries() + ("AQ-old" to "AU-old"))
            val before = phone.snapshot()
            val library = phone.Library(name, plainLeft = 99)

            val outcome = runCatching { open(phone, userVisible = userVisible) { library.open() } }

            val error = outcome.exceptionOrNull()
            assertTrue(
                "userVisible=$userVisible: a store made in the clear was returned: $outcome",
                error is EncryptedStoreUnavailableException,
            )
            assertFalse("userVisible=$userVisible: the refusal reads as a lost key", isPermanentKeyLoss(error!!))
            assertFalse("userVisible=$userVisible: a refused new file is still there", phone.exists(name))
            val others = phone.snapshot() - name - ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME
            assertEquals("userVisible=$userVisible: another file changed", before, others)
            assertEquals("userVisible=$userVisible: a copy was kept of a file that held nothing", emptyList<String>(), phone.copies())
            assertEquals("userVisible=$userVisible: the count of failed starts", if (userVisible) 1 else null, count(phone))
        }
    }

    // (3)
    @Test
    fun `3 - the store made after a set-aside comes out in the clear - it is emptied again and the call fails`() {
        val phone = KeysetPhone()
        val original = Keysets.wrappedEntries() + mapOf("AQ-1" to "AU-1", "AQ-2" to "AU-2")
        phone.put(name, original)
        phone.put(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME, mapOf(name to 2))
        val library = phone.Library(name, plainLeft = 99)

        val outcome =
            runCatching {
                open(phone) {
                    if (phone.entries(name).keys.any { it.startsWith("AQ-") }) throw KeyStoreException("the Keystore did not answer")
                    library.open()
                }
            }

        assertTrue(
            "the recreated store made in the clear was returned: $outcome",
            outcome.exceptionOrNull() is EncryptedStoreUnavailableException,
        )
        assertTrue("the file is not there", phone.exists(name))
        assertEquals("the file is not empty, as the opener left it before the try", emptyMap<String, Any?>(), phone.entries(name))
        assertEquals("the kept copy does not hold every entry", original, phone.entries(phone.copies().single()))
    }

    // (4) Guard: holds before and after the fix (the next class covers it in full).
    @Test
    fun `4 - a file that held anything before the call is never removed and none of its entries changes`() {
        val half = mapOf(Keysets.KEY_ENTRY to Keysets.plain(Keysets.NAMES_TEMPLATE))
        val states =
            listOf(
                Keysets.plainEntries() + ("AQ-1" to "AU-1"),
                Keysets.plainEntries(),
                half,
            )
        for (state in states) {
            val phone = KeysetPhone()
            phone.put(name, state)
            val library = phone.Library(name, plainLeft = 99)

            open(phone) { library.open() }

            assertEquals("${state.keys}: the remove function was called", emptyList<String>(), phone.removed)
            state.forEach { (key, value) -> assertEquals("${state.keys}: $key changed", value, phone.entries(name)[key]) }
        }
    }

    // (5)
    @Test
    fun `5 - not the main process - nothing is removed or written, the store is returned and counted`() {
        val phone = KeysetPhone()
        val library = phone.Library(name, plainLeft = 1)
        val process = StoreOpenerApi.newProcess()

        val store = open(phone, process = process, env = phoneEnv(phone, mapOf(MAY to false))) { library.open() }

        assertEquals("not the store the library made", 1, (store as KeysetPhone.StoreHandle).serial)
        assertEquals("a file was removed outside the main process", emptyList<String>(), phone.removed)
        assertEquals("something but the library wrote the file", 1, phone.writesTo(name))
        assertEquals("the store kept outside the main process was not counted", 1, StoreOpenerApi.plainKeysetCount(process, name))
    }

    // (6)
    @Test
    fun `6 - ZC-706's function decides whether the guard may change files`() {
        val context = KeysetPhoneContext(KeysetPhone(), packageName = PACKAGE_NAME)
        val cases =
            listOf<Pair<String, (android.content.Context) -> String?>>(
                "the main process" to { _ -> PACKAGE_NAME },
                "the crash-report process" to { _ -> "$PACKAGE_NAME:crash" },
                "a reader that throws" to { _ -> throw IllegalStateException("synthetic: no process name") },
                "a reader that answers null" to { _ -> null },
            )
        for ((case, reader) in cases) {
            val env = StoreOpenerApi.appStoreOpenEnv(context, reader)
            assertEquals(case, case == "the main process", StoreOpenerApi.envField(env, MAY))
        }

        val opener = StoreOpenerApi.mainSource("ui/screen/chat/datasource/EncryptedStoreOpener.kt")
        val reader = Regex("""try\s*\{\s*ProcessNameCompat\.getProcessName\(""").containsMatchIn(opener)
        assertTrue("the default reader is not ProcessNameCompat.getProcessName inside a catch", reader)
        assertTrue(
            "appStoreOpenEnv does not ask MainProcess.isMain(",
            StoreOpenerApi.declaration(opener, "appStoreOpenEnv").contains("MainProcess.isMain("),
        )
        val guard = StoreOpenerApi.mainSource("ui/screen/chat/datasource/KeysetGuard.kt")
        assertEquals("KeysetGuard.kt removes a file itself", 0, Regex("""deleteSharedPreferences\(|\.delete\(\)""").findAll(guard).count())
        val removes = Regex("""deleteSharedPreferences\(""").findAll(opener).map { it.range.first }.toList()
        assertEquals("the opener removes a store file in more than one place", 1, removes.size)
        assertTrue(
            "the opener's one remove is not the app environment's remove function",
            StoreOpenerApi.declaration(opener, "appStoreOpenEnv").contains("deleteSharedPreferences("),
        )
    }

    // (7)
    @Test
    fun `7 - through KI-25's openGroupStoreAndTellIfNew on a real folder`() {
        val group = "zchat_group_info_encrypted"

        fun run(
            plainLeft: Int,
            countBefore: Int?,
        ): Triple<Result<OpenedGroupStore>, KeysetPhone, File> {
            val folder = Files.createTempDirectory("zchat-zc705-group").toFile().apply { deleteOnExit() }
            val phone = KeysetPhone(folder)
            if (countBefore != null) phone.put(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME, mapOf(group to countBefore))
            val library = phone.Library(group, plainLeft = plainLeft)
            val env = phoneEnv(phone, mapOf(KEEP to true))
            val process = StoreOpenerApi.newProcess()
            val result =
                runCatching {
                    openGroupStoreAndTellIfNew(folder, group, {
                        StoreOpenerApi.open(process, group, phone::plain, env = env) { library.open() }
                    })
                }
            return Triple(result, phone, folder)
        }

        val (once, oncePhone, _) = run(plainLeft = 1, countBefore = null)
        assertTrue("refused once and then made: not new", once.getOrThrow().isNew)
        assertNoPlainEntry(oncePhone, group)

        val (thrice, _, thriceFolder) = run(plainLeft = 99, countBefore = null)
        assertTrue("refused three times: a store was returned", thrice.isFailure)
        assertFalse("refused three times: the file is there", File(thriceFolder, "$group.xml").exists())

        val (kept, keptPhone, _) = run(plainLeft = 99, countBefore = 2)
        assertTrue("at the third counted start the store kept by the exception is not new", kept.getOrThrow().isNew)
        val keptKey = keptPhone.entries(group)[Keysets.KEY_ENTRY] as String
        assertTrue("the store kept at the third start is not the one made in the clear", keptKey.startsWith("08"))
    }

    // (8)
    @Test
    fun `8 - a refused undo - the other way is tried once, the try fails and the next try keeps the file, counted once`() {
        val phone = KeysetPhone()
        phone.refuseRemove += name
        val library = phone.Library(name, plainLeft = 1)
        val process = StoreOpenerApi.newProcess()
        var made: Map<String, Any?>? = null

        val store =
            open(phone, process = process) {
                library.open().also {
                    // From here the system refuses to empty the file too.
                    phone.refuseCommit += name
                    if (made == null) made = phone.entries(name)
                }
            }

        assertEquals("the try whose undo was refused did not fail", 2, (store as KeysetPhone.StoreHandle).serial)
        assertEquals("the remove function was not tried once", listOf(name), phone.removed)
        assertEquals("the file is not as the library left it", made, phone.entries(name))
        assertEquals(
            "one error-level text says the new file could not be undone",
            1,
            StoreOpenerApi.guardLines(process).count { it.contains("could not be undone") },
        )
        assertEquals("the file kept on the next try was not counted once", 1, StoreOpenerApi.plainKeysetCount(process, name))
    }

    private companion object {
        const val KEEP = "keepPlainAfterLimit"
        const val MAY = "mayChangeFiles"
        const val REMOVE = "removeStoreFile"
        const val EXISTS = "storeFileExists"
        const val PACKAGE_NAME = "xyz.zsend.zchat.test"
    }
}
