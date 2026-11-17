package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-412 (fix-all 2.13.0, train T5b; t5b-add SPECS §5) — the mode dialogs' text does not scroll.
 *
 * At e79fc3d46 ModeSecurityNoteDialog puts the note in the Material 3 AlertDialog text slot as a plain Text with no
 * scroll. That slot is height-limited, so on a 360 dp wide screen at font 1.3 the Open note (chat_mode_note_open_body)
 * is cut after "… así que tu contacto debería", "Para volver: ⋮ → Modo de conversación → Shielded" is never shown and a
 * swipe does nothing (RH-P7 r1/r2 FAIL, Seeker debug es-ES, 1 Oct). ConversationModeIntroDialog,
 * ModeSwitchRequestDialog and ConversationModePickerDialog in the same file have the same shape.
 *
 * Wanted (read from ConversationModeUi.kt, comments stripped): each of the four `text = { … }` slots holds exactly one
 * `Column(Modifier.verticalScroll(rememberScrollState())) { … }` around its whole content. Unchanged: the note still
 * shows stringResource(bodyRes) at 13.sp with chat_mode_note_got_it, and no text in the four slots is smaller than
 * 13.sp (app-texts rule 7: never a smaller font; the wording is SR-D's).
 */
class ModeDialogsScrollAtLargeFontTest {
    private val source: String by lazy { SourceScan.stripComments(ChatResourceText.source(CONVERSATION_MODE_UI)) }

    @Test
    fun the_security_note_text_scrolls() = assertSlotScrolls("ModeSecurityNoteDialog")

    @Test
    fun the_mode_intro_text_scrolls() = assertSlotScrolls("ConversationModeIntroDialog")

    @Test
    fun the_switch_request_text_scrolls() = assertSlotScrolls("ModeSwitchRequestDialog")

    @Test
    fun the_mode_picker_text_scrolls() = assertSlotScrolls("ConversationModePickerDialog")

    @Test
    fun the_security_note_keeps_its_text_font_and_button() {
        val body = function("ModeSecurityNoteDialog")
        val slot = textSlot(body, "ModeSecurityNoteDialog")
        assertTrue(
            NOTE_TEXT.containsMatchIn(slot),
            "the note must still show stringResource(bodyRes) at 13.sp:\n$slot",
        )
        assertTrue(
            NOTE_BUTTON.containsMatchIn(body),
            "the note's button must still be chat_mode_note_got_it",
        )
        assertTrue(
            "title = { Text(stringResource(titleRes), fontWeight = FontWeight.Bold) }" in body,
            "the note's title must be unchanged",
        )
    }

    @Test
    fun no_text_in_the_four_slots_is_smaller_than_13_sp() {
        for (name in DIALOGS) {
            val slot = textSlot(function(name), name)
            val sizes = FONT_SIZE.findAll(slot).map { it.groupValues[1].toInt() }.toList()
            assertTrue(sizes.isNotEmpty(), "$name: no fontSize found in its text slot")
            assertTrue(sizes.all { it >= 13 }, "$name: a text slot font is smaller than 13.sp: $sizes")
        }
    }

    @Test
    fun the_scan_flags_the_unscrolled_slot() {
        // The note's slot as it was at e79fc3d46, verbatim; the check must reject it, or the pin proves nothing.
        val old = "text = { Text(stringResource(bodyRes), fontSize = 13.sp, color = cc.textSecondary) }"
        assertTrue(scrollProblem(old) != null, "the unscrolled slot must be flagged")
        val fixed =
            """
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(bodyRes), fontSize = 13.sp, color = cc.textSecondary)
                }
            }
            """.trimIndent()
        assertEquals(null, scrollProblem(fixed))
        // A scrolling Column that leaves part of the slot outside it is flagged too.
        val partial =
            """
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) { Text("a") }
                Text("b")
            }
            """.trimIndent()
        assertTrue(scrollProblem(partial) != null, "content outside the scrolling Column must be flagged")
    }

    private fun assertSlotScrolls(name: String) {
        val slot = textSlot(function(name), name)
        val problem = scrollProblem(slot)
        assertEquals(null, problem, "$name: $problem\n$slot")
    }

    private fun function(name: String): String =
        assertNotNull(functionBody(source, name), "ConversationModeUi.kt has no fun $name")

    private fun textSlot(
        body: String,
        name: String,
    ): String {
        assertTrue("text = {" in body, "$name has no AlertDialog text slot")
        return SourceScan.braceBlock(body, "text = {")
    }

    private companion object {
        const val CONVERSATION_MODE_UI =
            "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ConversationModeUi.kt"

        val DIALOGS =
            listOf(
                "ModeSecurityNoteDialog",
                "ConversationModeIntroDialog",
                "ModeSwitchRequestDialog",
                "ConversationModePickerDialog",
            )

        val SCROLLING_COLUMN =
            Regex(
                """^Column\(\s*(?:modifier\s*=\s*)?Modifier\s*""" +
                    """\.verticalScroll\(\s*rememberScrollState\(\)\s*\)\s*\)\s*\{""",
            )

        val NOTE_TEXT =
            Regex("""Text\(\s*stringResource\(bodyRes\),\s*fontSize = 13\.sp,\s*color = cc\.textSecondary,?\s*\)""")

        val NOTE_BUTTON =
            Regex(
                """confirmButton = \{\s*Button\(onClick = onDismiss\)\s*\{\s*""" +
                    """Text\(stringResource\(R\.string\.chat_mode_note_got_it\)\)""",
            )

        val FONT_SIZE = Regex("""fontSize = (\d+)\.sp""")

        /** Null when the slot's whole content is one Column(Modifier.verticalScroll(rememberScrollState())) { … }. */
        fun scrollProblem(slot: String): String? {
            val inner =
                slot
                    .trim()
                    .removePrefix("text = {")
                    .removeSuffix("}")
                    .trim()
            if (!SCROLLING_COLUMN.containsMatchIn(inner)) {
                return "the text slot does not start with Column(Modifier.verticalScroll(rememberScrollState()))"
            }
            val column = SourceScan.braceBlock(inner, "Column(")
            if (column != inner) return "part of the text slot is outside the scrolling Column"
            return null
        }

        /** From `fun <name>(` to the next top-level declaration or annotation (as the amount-field scan). */
        fun functionBody(
            source: String,
            name: String,
        ): String? {
            val start = Regex("""(?m)^(?:internal |private )?fun $name\(""").find(source)?.range?.first ?: return null
            val next = Regex("""(?m)^(?:@|fun |internal |private )""").find(source, start + 1)?.range?.first
            return source.substring(start, next ?: source.length)
        }
    }
}
