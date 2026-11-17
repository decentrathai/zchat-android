package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-705 (register ZC-705, medium; store-opener design, revision 2a, rule S5, sections 3.4.1 and 5.2, class C): a store
 * file that EXISTED before the call is never removed, emptied or rewritten because of its key entries, whatever they
 * are. An unprotected key entry in it (written by 2.12.4, or by an earlier start, or left by a kill between the
 * library's write and the guard's undo) is counted once per process by the store's kind and logged; the store is used
 * as before. A bundle is never named by its file name (it ends in the wallet's owner tag).
 *
 * The first and the last case are controls that pass before the fix. All names are synthetic.
 */
class ExistingStoreWithAPlainKeysetIsKeptAndCountedTest {
    private val name = "zchat_e2e_keys_encrypted"

    private fun phoneEnv(phone: KeysetPhone): Map<String, Any?> =
        mapOf(
            "removeStoreFile" to { file: String -> phone.remove(file) },
            "storeFileExists" to { file: String -> phone.exists(file) },
            "keepPlainAfterLimit" to true,
        )

    private fun openOnce(
        phone: KeysetPhone,
        process: StoreOpenerApi.Process,
        store: String = name,
    ): KeysetPhone.StoreHandle {
        val library = phone.Library(store, plainLeft = 99)
        return StoreOpenerApi.open(process, store, phone::plain, env = phoneEnv(phone)) { library.open() } as KeysetPhone.StoreHandle
    }

    // (1) Control: no write, no remove.
    @Test
    fun `1 - plain key entries and three data entries - opened with every byte as it was, nothing written or removed`() {
        val phone = KeysetPhone()
        val before = Keysets.plainEntries() + mapOf("AQ-1" to "AU-1", "AQ-2" to "AU-2", "AQ-3" to "AU-3")
        phone.put(name, before)

        val store = openOnce(phone, StoreOpenerApi.newProcess())

        assertEquals("not the store over the existing file", 1, store.serial)
        assertEquals("an entry changed", before, phone.entries(name))
        assertEquals("the file saw a write", 0, phone.writesTo(name))
        assertEquals("a file was removed", emptyList<String>(), phone.removed)
        assertEquals("a count of failed starts was written", null, phone.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)[name])
        assertEquals("a copy was kept", emptyList<String>(), phone.copies())
    }

    // (2)
    @Test
    fun `2 - counted once per process by its kind, after one open and after three`() {
        val phone = KeysetPhone()
        phone.put(name, Keysets.plainEntries() + ("AQ-1" to "AU-1"))
        val process = StoreOpenerApi.newProcess()

        openOnce(phone, process)
        assertEquals("one open", 1, StoreOpenerApi.plainKeysetCount(process, name))
        repeat(2) { openOnce(phone, process) }
        assertEquals("three opens of the same store in one process", 1, StoreOpenerApi.plainKeysetCount(process, name))
        assertEquals("the guard logged the same store more than once", 1, StoreOpenerApi.guardLines(process).size)
    }

    // (3)
    @Test
    fun `3 - a bundle is logged as a set-aside bundle, never by its owner tag`() {
        val tag = "c3".repeat(32)
        val bundle = "zchat_set_aside_$tag"
        val phone = KeysetPhone()
        phone.put(bundle, Keysets.plainEntries() + ("AQ-1" to "AU-1"))
        val process = StoreOpenerApi.newProcess()

        openOnce(phone, process, bundle)

        val text = StoreOpenerApi.guard("keptAsItIsLine", StoreOpenerApi.kindOf(bundle)) as String
        assertTrue("the guard's text does not say a set-aside bundle: $text", text.contains("a set-aside bundle"))
        assertFalse("the guard's text holds the owner tag", text.contains(tag))
        val lines = StoreOpenerApi.guardLines(process)
        assertEquals("the guard's lines of the open: $lines", listOf(text), lines)
        assertEquals(1, StoreOpenerApi.plainKeysetCount(process, "a set-aside bundle"))
    }

    // (4)
    @Test
    fun `4 - a file made in the clear by an earlier start, with no data, is kept and counted and not removed`() {
        val phone = KeysetPhone()
        val before = Keysets.plainEntries()
        phone.put(name, before)
        val process = StoreOpenerApi.newProcess()

        val store = openOnce(phone, process)

        assertEquals(1, store.serial)
        assertEquals("the file changed", before, phone.entries(name))
        assertEquals("the file was removed", emptyList<String>(), phone.removed)
        assertEquals("not counted", 1, StoreOpenerApi.plainKeysetCount(process, name))
    }

    // (5) Control.
    @Test
    fun `5 - protected already - no count, no line, no write`() {
        val phone = KeysetPhone()
        phone.put(name, Keysets.wrappedEntries() + ("AQ-1" to "AU-1"))
        val process = StoreOpenerApi.newProcess()

        openOnce(phone, process)

        assertEquals("the file saw a write", 0, phone.writesTo(name))
        assertEquals("a file was removed", emptyList<String>(), phone.removed)
        if (StoreOpenerApi.classOrNull("KeysetGuard") != null) {
            assertEquals("a protected store was counted", 0, StoreOpenerApi.plainKeysetCount(process, name))
            assertEquals("a protected store was logged", emptyList<String>(), StoreOpenerApi.guardLines(process))
        }
    }
}
