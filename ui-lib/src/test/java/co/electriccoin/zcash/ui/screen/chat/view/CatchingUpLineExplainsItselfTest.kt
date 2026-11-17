package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.SyncLine
import co.electriccoin.zcash.ui.screen.chat.model.SyncLineState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1266: "Catching up N%" on the chat list says nothing about what it means, and tapping it does nothing.
 *
 * Seams: `SyncLine.explainsOnTap(line: SyncLineState): Boolean` (by reflection, so the module's other tests compile before
 * it exists), and the texts `chat_list_sync_catching_up_title`, `chat_list_sync_catching_up_explained` (EN, ES). The tap
 * and the dialog are read from the source: a JVM test cannot compose `SyncStatusBar`. A phone check of the tap is owed.
 */
class CatchingUpLineExplainsItselfTest {
    private val explains = SyncLine::class.java.methods.firstOrNull { it.name == "explainsOnTap" }

    private val source by lazy {
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt")
    }

    private fun explainsOnTap(line: SyncLineState): Boolean =
        assertNotNull(explains, "SyncLine.explainsOnTap does not exist").invoke(SyncLine, line) as Boolean

    @Test
    fun only_the_catching_up_line_opens_the_explanation() {
        assertTrue(explainsOnTap(SyncLineState.CatchingUp(55)))
        for (line in listOf(SyncLineState.Synced, SyncLineState.Offline, SyncLineState.Stopped, SyncLineState.Connecting)) {
            assertFalse(explainsOnTap(line), "$line")
        }
    }

    @Test
    fun the_status_bar_makes_the_line_tappable_and_opens_the_explanation() {
        val start = source.indexOf("private fun SyncStatusBar(")
        val bar = source.substring(start, source.indexOf("\n@Composable", start))
        assertTrue("SyncLine.explainsOnTap(line)" in bar, "the status line never asks whether it explains on a tap")
        assertTrue("R.string.chat_list_sync_catching_up_explained" in source, "the explanation is not on the chat list")
        assertTrue("R.string.chat_list_sync_catching_up_title" in source, "the explanation has no title")
    }

    @Test
    fun the_explanation_is_in_both_languages_and_promises_no_time() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for (key in listOf("chat_list_sync_catching_up_title", "chat_list_sync_catching_up_explained")) {
            val english = assertNotNull(en[key], "no English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(english, spanish, "$key is not translated")
            assertEquals(english.count { it == '%' }, spanish.count { it == '%' }, "$key: placeholders differ")
        }
        val text = en.getValue("chat_list_sync_catching_up_explained").lowercase()
        for (promise in listOf("a few seconds", "a few minutes", "a few blocks", "in a moment")) {
            assertFalse(promise in text, "the explanation promises a time: $text")
        }
    }
}
