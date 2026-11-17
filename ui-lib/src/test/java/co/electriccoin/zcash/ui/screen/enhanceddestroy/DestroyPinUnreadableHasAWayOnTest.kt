package co.electriccoin.zcash.ui.screen.enhanceddestroy

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZC-1042-X1 (T7aD review b of 19 Oct, finding 4, P2) — with a Destroy PIN store that cannot be read:
 *  - the chat list reads hasDestroyPin() with no catch (AndroidChat), so the same fault that Emergency Destroy now
 *    survives throws there while the screen is drawn; it must fail closed as Emergency Destroy does (ask for the PIN);
 *  - the chat list's PIN check turns a throw into "Incorrect PIN"; it must say the PIN could not be checked;
 *  - the text that says so offers only "Try again"; it must name the way on when it keeps happening (uninstall ZCHAT,
 *    which removes it with everything in its own storage), EN and ES.
 */
class DestroyPinUnreadableHasAWayOnTest {
    private fun code(path: String): String {
        val file = File(ChatResourceText.repoRoot, path)
        assertTrue("fixture: $path", file.isFile)
        return SourceScan.stripComments(file.readText())
    }

    @Test
    fun `the chat list reads whether a PIN is set failing closed`() {
        val chat = code("$UI/screen/chat/AndroidChat.kt")
        assertFalse("AndroidChat reads hasDestroyPin() with no catch", "hasDestroyPin = zchatPreferences.hasDestroyPin()" in chat)
        assertTrue(
            "AndroidChat does not count a PIN store that cannot be read as a PIN that is set",
            "runCatching { zchatPreferences.hasDestroyPin() }.getOrDefault(true)" in chat,
        )
    }

    @Test
    fun `the chat list's PIN check says the PIN could not be checked, never incorrect`() {
        val list = code("$UI/screen/chat/view/ChatListView.kt")
        // A throw still counts as a failure, never a success (ChatListDestroyPinIsMaskedTest); its text is its own.
        val failed = list.indexOf("attempt.isFailure")
        assertTrue("a PIN check that throws still reads as a wrong PIN", failed >= 0)
        assertTrue(
            "the chat list does not say the PIN could not be checked",
            list.indexOf("R.string.destroy_pin_cannot_check", failed) in failed..(failed + 300),
        )
    }

    @Test
    fun `the text names the way on`() {
        val en = ChatResourceText.strings("values")["destroy_pin_cannot_check"] ?: throw AssertionError("no English text")
        val es = ChatResourceText.strings("values-es")["destroy_pin_cannot_check"] ?: throw AssertionError("no Spanish text")
        assertTrue("the English text names no way on: $en", "uninstall" in en.lowercase())
        assertTrue("the Spanish text names no way on: $es", "desinstala" in es.lowercase())
        assertNotEquals(en, es)
    }

    private companion object {
        const val UI = "ui-lib/src/main/java/co/electriccoin/zcash/ui"
    }
}
