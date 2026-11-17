package co.electriccoin.zcash.ui.screen.advancedsettings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F10 (fix-all 2.13.0, chat copy #16): the Advanced Settings "Restore old chats" row, as AdvancedSettingsVM wires it.
 * What the row does is tested on RestoreOldChats (ChatCopyOfferBackoffTest, case 13); this pins the wiring the
 * adversarial review asked for, which no JVM test can reach through the view model's Koin-built use cases:
 *  - the row is shown only while RestoreOldChats.isAvailable() says a copy is held, and that read (the marks file and
 *    every copy file) runs in a coroutine, never inside the state builder on the main thread (review break 6);
 *  - a tap only opens a confirm, and RestoreOldChats.retryAndRestart() — which restarts ZCHAT, a force stop from
 *    inside the running app — runs only from that confirm (review break 7);
 *  - the view shows the confirm with its own texts.
 * Fails at 7d8799d29: there is no such row.
 */
class RestoreOldChatsRowWiringTest {
    private val vm = source("ui/screen/advancedsettings/AdvancedSettingsVM.kt")
    private val view = source("ui/screen/advancedsettings/AdvancedSettingsView.kt")
    private val state = source("ui/screen/advancedsettings/AdvancedSettingsState.kt")

    @Test
    fun the_view_model_is_given_restore_old_chats() {
        assertTrue(
            "AdvancedSettingsVM is not given RestoreOldChats",
            Regex("""private val restoreOldChats: RestoreOldChats""").containsMatchIn(vm)
        )
        assertTrue("the row's title is not the new string", vm.contains("R.string.advanced_settings_restore_old_chats)"))
        assertTrue("the row's subtitle is not the new string", vm.contains("R.string.advanced_settings_restore_old_chats_subtitle"))
    }

    @Test
    fun whether_the_row_is_shown_is_read_off_the_state_builder() {
        val builder = functionBody(vm, "createState")
        assertFalse("createState reads the kept copies (main thread)", builder.contains("isAvailable("))
        assertTrue("the row is not shown only while a copy is held", builder.contains(".takeIf { showRestoreOldChats }"))
        assertTrue("isAvailable() is never read", vm.contains("restoreOldChats.isAvailable()"))
    }

    @Test
    fun the_restart_runs_only_from_the_confirm() {
        val calls = Regex("""restoreOldChats\.retryAndRestart\(\)""").findAll(vm).count()
        assertTrue("retryAndRestart is called $calls times", calls == 1)
        assertTrue(
            "retryAndRestart is not in the confirm handler",
            functionBody(vm, "onRestoreOldChatsConfirm").contains("restoreOldChats.retryAndRestart()")
        )
        assertFalse("the row's tap restarts at once", functionBody(vm, "onRestoreOldChatsClick").contains("retryAndRestart"))
        assertTrue("the state carries no confirm", state.contains("restoreOldChatsConfirm"))
        assertTrue("the view does not show the confirm", view.contains("state.restoreOldChatsConfirm"))
        for (key in listOf("confirm_title", "confirm_text", "confirm_button", "cancel_button")) {
            assertTrue(
                "the confirm does not show advanced_settings_restore_old_chats_$key",
                view.contains("R.string.advanced_settings_restore_old_chats_$key")
            )
        }
    }

    /** The text of the function [name] in [source]: from its declaration to the next declaration at the same depth. */
    private fun functionBody(
        source: String,
        name: String
    ): String {
        val start = Regex("""fun $name\(""").find(source)?.range?.first ?: throw AssertionError("there is no fun $name")
        val next = Regex("""\n    (private |internal |override )?fun """).find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, next)
    }

    private fun source(relative: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val module =
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
                ?: throw AssertionError("could not locate the ui-lib module from ${start.path}")
        val file = module.resolve("src/main/java/co/electriccoin/zcash/$relative")
        if (!file.isFile) throw AssertionError("missing ${file.path}")
        return file.readText()
    }
}
