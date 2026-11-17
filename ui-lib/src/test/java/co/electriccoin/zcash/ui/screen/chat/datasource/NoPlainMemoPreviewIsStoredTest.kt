package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * ZC-167 (T7b triage 13 Oct, SMALL work order): `ZchatPreferences` can store a message's text in clear.
 *
 * At 828ed7c0a the interface declares `addUnroutableMessage(UnroutableMessageData)`, whose implementation writes the
 * memo's preview (`memoPreview`), the sender hash and the chat id as plain JSON into the unencrypted preferences file
 * `zchat_unroutable_messages`. Nothing in the app calls it (2.12.4 declares it and never calls it either), so the
 * only effect of the code is that a later caller could put message text on disk unencrypted.
 *
 * Wanted: the type and its four functions are gone, so no code can write a plain memo preview; the file's name and
 * the `clear()` line that empties it stay, so a file an old build left on a phone is still wiped by Reset and Destroy
 * (compat-2124: nothing reads its data).
 *
 * Reads the source: a JVM test cannot prove that no caller exists any other way.
 */
class NoPlainMemoPreviewIsStoredTest {
    private val uiLibDir: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        repeat(SEARCH_DEPTH) {
            val current = dir ?: return@repeat
            listOf(current, current.resolve("ui-lib")).forEach { module ->
                if (module.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory) return@lazy module
            }
            dir = current.parentFile
        }
        fail("could not locate the ui-lib module from ${start.path}")
        throw IllegalStateException("unreachable")
    }

    private val preferences: String by lazy {
        val file = uiLibDir.resolve(PREFERENCES_FILE)
        if (!file.isFile) fail("expected ${file.path}")
        file.readText()
    }

    @Test
    fun `no code of the app can write a memo preview in clear`() {
        assertFalse("ZchatPreferences.kt still writes a memo preview", "memoPreview" in preferences)
        assertFalse("ZchatPreferences.kt still declares UnroutableMessageData", "UnroutableMessageData" in preferences)
        val functions =
            listOf("addUnroutableMessage", "getUnroutableMessages", "removeUnroutableMessage", "getUnroutableMessageCount")
                .filter { it in preferences }
        assertEquals("ZchatPreferences.kt still has the unroutable-message functions", emptyList<String>(), functions)
    }

    @Test
    fun `control - a file an old build left is still emptied by clear`() {
        assertTrue("the old file's name is gone", "\"zchat_unroutable_messages\"" in preferences)
        assertTrue("clear() no longer empties the old file", "unroutableMsgPrefs.edit().clear().commit()" in preferences)
    }

    private companion object {
        const val SEARCH_DEPTH = 8
        const val PREFERENCES_FILE = "src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt"
    }
}
