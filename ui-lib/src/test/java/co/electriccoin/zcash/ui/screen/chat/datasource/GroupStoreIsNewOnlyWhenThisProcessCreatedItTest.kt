package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files

/**
 * KI-25 pair 3, ZC-598 (KI-25-SPEC 3.6b D1, condition 2; section 4.3 class 22) — "this process created the encrypted
 * group file", on a real temporary folder: `openGroupStoreAndTellIfNew(folder, name, open, exists)` (a small file in
 * the datasource package, reached by name) tests that neither `<name>.xml` nor `<name>.xml.bak` is there as its last
 * step before it calls `open`, and throws `EncryptedStoreUnavailableException` when the file is not there after `open`
 * returned. [exists] is the file test (the store passes `File::exists`); a case makes it throw.
 *
 * At the base there is no such function. All names are synthetic.
 */
class GroupStoreIsNewOnlyWhenThisProcessCreatedItTest {
    private val folder: File = Files.createTempDirectory("zchat-ki25-group-store").toFile().apply { deleteOnExit() }

    private fun creating(): () -> SharedPreferences =
        {
            File(folder, "$NAME.xml").writeText("<map />")
            MemorySharedPreferences()
        }

    /** The function's answer: (the store, whether it is new). */
    private fun open(
        open: () -> SharedPreferences,
        exists: (File) -> Boolean = File::exists,
    ): Pair<SharedPreferences, Boolean> {
        val kt =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.datasource.OpenGroupStoreKt") }.getOrNull()
                ?: throw AssertionError("KI-25 pair 3 (ZC-598): no such operation: openGroupStoreAndTellIfNew (OpenGroupStore.kt)")
        val method =
            kt.declaredMethods.firstOrNull { it.name == "openGroupStoreAndTellIfNew" && it.parameterCount == 4 }
                ?: throw AssertionError("KI-25 pair 3 (ZC-598): no such operation: openGroupStoreAndTellIfNew(folder, name, open, exists)")
        val result =
            try {
                method.invoke(null, folder, NAME, open, exists)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }!!

        fun get(name: String): Any? =
            result.javaClass.methods
                .first { it.name == name && it.parameterCount == 0 }
                .invoke(result)
        return (get("getPrefs") as SharedPreferences) to (get("isNew") as Boolean)
    }

    @Test
    fun `a - the file is absent and open creates it - new`() {
        assertTrue("a store this call created is not new", open(creating()).second)
    }

    @Test
    fun `b - the xml is there - not new`() {
        File(folder, "$NAME.xml").writeText("<map />")
        assertFalse(open(creating()).second)
    }

    @Test
    fun `c - only the xml bak is there - not new`() {
        File(folder, "$NAME.xml.bak").writeText("<map />")
        assertFalse(open(creating()).second)
    }

    @Test
    fun `d - a file test that throws - not new, and nothing escapes`() {
        var calls = 0
        val throwingOnce = { file: File ->
            calls++
            if (calls == 1) throw SecurityException("synthetic: the file test throws")
            file.exists()
        }
        assertFalse("a file test that threw counted as absent", open(creating(), throwingOnce).second)
    }

    @Test
    fun `e - an open that creates nothing - EncryptedStoreUnavailableException`() {
        try {
            open({ MemorySharedPreferences() })
            fail("a store object was returned although its file is not on the disk")
        } catch (e: EncryptedStoreUnavailableException) {
            // expected
        }
    }

    @Test
    fun `f - two calls on one folder, one after the other - only the first is new`() {
        assertTrue(open(creating()).second)
        assertFalse("a second builder on the same folder was told the store is new", open(creating()).second)
    }

    @Test
    fun `g - source - the group store's property is opened through it, after the key store, and absent is the last step before open`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        val chat = File(dir ?: error("repo root not found"), "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource")
        val store = File(chat, "ZchatPreferences.kt").readText()
        val call = store.indexOf("openGroupStoreAndTellIfNew(")
        assertTrue("the group store is not opened through openGroupStoreAndTellIfNew", call >= 0)
        assertTrue("it is not called with the group store's name", store.substring(call, call + 200).contains("GROUP_INFO_PREFS_NAME_ENC"))
        assertTrue("the group store is opened before the key store", call > store.indexOf("private val e2ePrefs"))

        val file = File(chat, "OpenGroupStore.kt")
        assertTrue("no OpenGroupStore.kt", file.isFile)
        val text = file.readText()
        val body = text.substring(text.indexOf("fun openGroupStoreAndTellIfNew("))
        val absent = body.indexOf(".xml.bak")
        val opened = body.indexOf("open()")
        assertTrue("the .xml.bak test is missing", absent >= 0)
        assertTrue("the absent test does not come before open()", opened > absent)
        assertEquals(
            "something other than the absent test stands between it and open()",
            0,
            Regex("""\bexists\(""").findAll(body.substring(absent, opened)).count(),
        )
        assertTrue("the function does not throw EncryptedStoreUnavailableException", body.contains("EncryptedStoreUnavailableException("))
    }

    private companion object {
        const val NAME = "zchat_group_info_encrypted"
    }
}
