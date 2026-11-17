package co.electriccoin.zcash.ui.screen.advancedsettings

import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZC-709 (store-opener design, revision 2a, section 3.5, class C, its part beside the Settings row's tests): the
 * Advanced Settings row "Restore old chats" asks for the copies the user may ask for again (held ones AND declined
 * ones that hold chats, KeptChatStoreCopies.askable()), and its action offers them again (reoffer()). Today it asks
 * held() and calls retryHeld(), so a copy declined with "Continue without them" is never offered again.
 */
class DismissedChatCopyRowTest {
    private val row = StoreOpenerApi.mainSource("ui/screen/advancedsettings/RestoreOldChats.kt")

    @Test
    fun `the row is shown for the askable copies`() {
        val available = row.substringAfter("fun isAvailable(").substringBefore("fun retryAndRestart(")
        assertTrue("isAvailable() does not ask askable(): $available", Regex("""\.askable\(\)""").containsMatchIn(available))
    }

    @Test
    fun `the row's action offers them again`() {
        val action = row.substringAfter("fun retryAndRestart(")
        assertTrue("retryAndRestart() does not call reoffer(): $action", Regex("""\.reoffer\(\)""").containsMatchIn(action))
    }

    @Test
    fun `the row's comment says it brings back a declined copy too`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        val root = dir ?: error("repo root not found")
        val text = File(root, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/advancedsettings/RestoreOldChats.kt").readText()
        assertTrue("the KDoc does not say a declined copy is offered again", text.contains("declined"))
    }
}
