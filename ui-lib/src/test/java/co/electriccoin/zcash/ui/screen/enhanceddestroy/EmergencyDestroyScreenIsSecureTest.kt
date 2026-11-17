package co.electriccoin.zcash.ui.screen.enhanceddestroy

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * ZC-164 (T7b triage 13 Oct, SMALL work order): the Emergency Destroy screen can be captured in a screenshot and shows
 * its content in the recent-apps preview.
 *
 * Every other sensitive screen asks for the window's secure flag through `SecureScreen()` while it is shown (the chat
 * list, the compose screen, the restore screens). At 828ed7c0a `AndroidEnhancedDestroy()` never does, so the screen
 * that sets up and runs the wipe of the wallet and its chats is the one left open to a screenshot.
 *
 * Wanted: `AndroidEnhancedDestroy()` calls `SecureScreen()` under the same `shouldSecureScreen` switch as the other
 * screens. A Composable cannot be run in a JVM test, so this reads the source; the device test
 * SensitiveScreenInvariantTest stays as it is.
 */
class EmergencyDestroyScreenIsSecureTest {
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

    @Test
    fun `the Emergency Destroy screen asks for the secure flag while it is shown`() {
        val body = functionBody(source(SCREEN_FILE), "fun AndroidEnhancedDestroy()")

        assertTrue("AndroidEnhancedDestroy() never calls SecureScreen(): the screen can be captured", "SecureScreen()" in body)
        assertTrue(
            "AndroidEnhancedDestroy() does not use the shouldSecureScreen switch of the other screens",
            Regex("""if\s*\(\s*shouldSecureScreen\s*\)\s*\{\s*SecureScreen\(\)\s*}""").containsMatchIn(body),
        )
    }

    @Test
    fun `control - the chat list asks for it the same way`() {
        val source = source(CHAT_LIST_FILE)

        assertTrue(Regex("""if\s*\(\s*shouldSecureScreen\s*\)\s*\{\s*SecureScreen\(\)\s*}""").containsMatchIn(source))
    }

    private fun source(path: String): String {
        val file = uiLibDir.resolve(path)
        if (!file.isFile) fail("expected ${file.path}")
        return file.readText()
    }

    /** The text of the function that starts with [signature], up to its closing brace. */
    private fun functionBody(
        source: String,
        signature: String,
    ): String {
        val start = source.indexOf(signature)
        if (start < 0) fail("$signature is not in the file")
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        fail("$signature has no closing brace")
        throw IllegalStateException("unreachable")
    }

    private companion object {
        const val SEARCH_DEPTH = 8
        const val SCREEN_FILE = "src/main/java/co/electriccoin/zcash/ui/screen/enhanceddestroy/AndroidEnhancedDestroy.kt"
        const val CHAT_LIST_FILE = "src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt"
    }
}
