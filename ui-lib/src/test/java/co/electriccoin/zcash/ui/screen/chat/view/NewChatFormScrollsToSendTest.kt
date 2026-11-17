package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZC-979 / ZC-632 (register; found by T6b's phone step for ZC-632 on the HONOR in Spanish and the Seeker in English, both
 * runs) — at 360 dp wide and font scale 1.3 the New Chat form does not scroll: its Column fills the screen, the contacts
 * list takes `weight(1f)`, and once the fields, the mode captions and the composer card are taller than the screen the
 * card's lower part, with the Send button, lies beyond the bottom edge where nothing can bring it back.
 *
 * Wanted (this module has no JVM Compose renderer, so the layout is pinned in source form; the instrumented
 * NewChatSendIsReachableAtSmallScreenTest renders it at 360 x 640 dp and font 1.3 on a device, and the phone check
 * repeats it): the form's Column scrolls (verticalScroll) and keeps imePadding (the keyboard still pushes the form up);
 * nothing in the form takes a weight (a weight means nothing in a scrolling column); the contacts list is bounded in
 * height so it can live inside the scrolling form; the Send button is inside the scrolling form.
 */
class NewChatFormScrollsToSendTest {
    private val form: String by lazy {
        val source =
            SourceScan.stripComments(
                File(ChatResourceText.repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ZchatComposeView.kt")
                    .readText()
            )
        val start = source.indexOf("private fun ComposeReadyView(")
        val end = source.indexOf("// Add Contact Dialog").takeIf { it > start } ?: source.indexOf("AddContactDialog(", start)
        assertTrue("fixture: ComposeReadyView", start >= 0 && end > start)
        source.substring(start, end)
    }

    @Test
    fun the_form_scrolls_and_keeps_the_keyboard_padding() {
        val column = form.indexOf("Column(")
        val modifier = form.substring(column, form.indexOf(") {", column))
        assertTrue("the New Chat form does not scroll", ".verticalScroll(" in modifier)
        assertTrue("the New Chat form lost its keyboard padding", ".imePadding()" in modifier)
    }

    @Test
    fun nothing_in_the_form_takes_a_weight() {
        assertEquals("a weight in the form (it cannot scroll with one)", 0, Regex("""\.weight\(""").findAll(form).count())
    }

    @Test
    fun the_contacts_list_is_bounded_so_it_can_live_in_the_scrolling_form() {
        val list = form.indexOf("LazyColumn(")
        assertTrue("fixture: the contacts list", list >= 0)
        val modifier = form.substring(list, form.indexOf(") {", list))
        assertTrue("the contacts list has no height bound", ".heightIn(" in modifier)
    }

    @Test
    fun the_send_button_is_inside_the_scrolling_form() {
        val scroll = form.indexOf(".verticalScroll(")
        val send = form.indexOf("R.string.new_chat_send_message")
        assertTrue("fixture: the Send button", send >= 0)
        assertTrue("the Send button is outside the scrolling form", scroll in 0 until send)
        assertFalse("the Send button sits in a bottom bar the keyboard covers", "bottomBar" in form)
    }
}
