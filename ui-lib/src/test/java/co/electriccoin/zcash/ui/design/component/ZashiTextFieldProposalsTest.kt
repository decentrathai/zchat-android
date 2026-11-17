package co.electriccoin.zcash.ui.design.component

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import co.electriccoin.zcash.ui.design.util.AmountEditResult
import co.electriccoin.zcash.ui.design.util.AmountEditor
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.design.util.AmountView
import co.electriccoin.zcash.ui.design.util.SendAmountEditor
import co.electriccoin.zcash.ui.design.util.StringResource
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 m6 (money-path rule 5, amount input), ZC-1772 and ZC-1773: the filter inside ZashiTextField that decides which
 * proposals of the BasicTextField reach the owner of the field. Main Send's ZEC and USD fields use that path (the
 * EnhancedTextFieldState overload, held by [AmountTextFieldSelection]).
 *
 * ZC-1772 (medium; Fable's review of m5, finding 1): ZashiTextField keeps `lastInnerState` (the last proposal it reported)
 * and reports a proposal only when its text or its selection differs from that. After a key the amount machine does not take,
 * the holder shows the old text with the old caret (p), but `lastInnerState` still holds the refused proposal's caret (p + 1).
 * A tap one place to the right (p + 1) is then "no change", is never reported, and the caret snaps back to p. In money terms:
 * USD 1234, caret after the 1, "." is refused, the user taps after the 2 and types 5: the field must make 12534 where the
 * user's caret was; with the swallowed tap the 5 lands after the 1 and makes 15234, a different amount from the one the user
 * saw and chose. Every real caret move must reach the holder.
 *
 * ZC-1773: nothing on the JVM drove that path (Compose cannot run here). This test drives it through a plain class that the
 * fix must create, because the filter lives inside a private composable:
 *
 *     package co.electriccoin.zcash.ui.design.component
 *     class ZashiTextFieldProposals {                       // public, no-argument constructor
 *         fun displayed(text: String, start: Int, end: Int)  // what BasicTextField is given on this composition
 *         fun propose(text: String, start: Int, end: Int): InnerTextFieldState?   // null: nothing changed, report nothing
 *     }
 *
 * `displayed` is called on every composition with the text and selection that the BasicTextField gets (for main Send:
 * the holder's valueFor(text)); `propose` is called from onValueChange and returns the state to hand to
 * EnhancedTextFieldState.onValueChange, or null. TextFieldInternal must use this class instead of its own `lastInnerState`
 * comparison (the last test reads the source). The class is found by reflection so that this module still compiles at the
 * RED commit; every test here fails with "ZashiTextFieldProposals is missing" until the fix creates it.
 *
 * Tests named `pin_` state the behaviour that must stay as it is for every ZashiTextField user.
 */
class ZashiTextFieldProposalsTest {
    private val en: Locale = Locale.US
    private val es: Locale = Locale("es", "ES")

    /** The class the fix creates, called by reflection. */
    private class Proposals {
        private val type: Class<*> =
            runCatching { Class.forName("co.electriccoin.zcash.ui.design.component.ZashiTextFieldProposals") }.getOrNull()
                ?: fail(
                    "ZC-1772: ZashiTextFieldProposals is missing: the filter of ZashiTextField.onValueChange is still " +
                        "inside the composable and cannot be driven on the JVM"
                )
        private val instance: Any = type.getDeclaredConstructor().newInstance()
        private val int = Int::class.javaPrimitiveType
        private val displayedMethod = type.getMethod("displayed", String::class.java, int, int)
        private val proposeMethod = type.getMethod("propose", String::class.java, int, int)

        fun displayed(
            text: String,
            start: Int,
            end: Int
        ) {
            displayedMethod.invoke(instance, text, start, end)
        }

        fun propose(
            text: String,
            start: Int,
            end: Int
        ): InnerTextFieldState? = proposeMethod.invoke(instance, text, start, end) as InnerTextFieldState?
    }

    /**
     * A fake main-Send field: the real holder and amount machine behind the new filter, wired as ZashiTextField and
     * [AmountTextFieldSelection.textFieldState] wire them (every composition: displayed(valueFor(text)); a proposal that the
     * filter reports goes to the holder as TextFieldValue(its text, its range)).
     */
    private class Field(
        val editor: AmountEditor
    ) {
        val filter = Proposals()

        // QA2 m8 (W-137 m8): the holder is made with the text a state shows and edits the state it is handed.
        val holder = AmountTextFieldSelection<AmountView> { it.shown }
        var view: AmountView = editor.empty()
        var edits = 0
        val reported = mutableListOf<IntRange>()

        init {
            composed()
        }

        val value: TextFieldValue get() = holder.valueFor(view.shown)
        val text: String get() = value.text
        val caret: IntRange get() = value.selection.min..value.selection.max

        /** A composition: the BasicTextField is given the holder's value. */
        fun composed() {
            filter.displayed(value.text, value.selection.start, value.selection.end)
        }

        /** BasicTextField proposes ([newText], [start]..[end]); true when the filter let it through to the holder. */
        fun propose(
            newText: String,
            start: Int,
            end: Int = start
        ): Boolean {
            val report = filter.propose(newText, start, end)
            if (report != null) {
                val reportedText = (report.value as StringResource.ByString).value
                holder.onComposition(view)
                holder.onValueChange(
                    TextFieldValue(reportedText, report.getTextRange(reportedText)),
                    edit = { state, changed, selectionBefore ->
                        edits++
                        reported += selectionBefore
                        val next = editor.edit(state, changed, selectionBefore)
                        AmountEditResult(next, taken = next.handle !== state.handle)
                    },
                    onState = { view = it }
                )
            }
            composed()
            return report != null
        }

        /** The user taps (or drags the handles) to [start]..[end] without changing the text. */
        fun place(
            start: Int,
            end: Int = start
        ): Boolean = propose(text, start, end)

        /** [keys] arrive one by one at the selection the field shows, as the keyboard does. */
        fun typed(keys: String) {
            for (key in keys) {
                val selection = value.selection
                val shown = text
                propose(shown.substring(0, selection.min) + key + shown.substring(selection.max), selection.min + 1)
            }
        }

        fun pasted(clip: String) {
            val selection = value.selection
            val shown = text
            propose(shown.substring(0, selection.min) + clip + shown.substring(selection.max), selection.min + clip.length)
        }
    }

    // ---- ZC-1772 / ZC-1773: a refused key, then a tap one place to the right ----

    @Test
    fun zc1772_a_tap_one_place_right_of_a_refused_key_reaches_the_holder_and_the_next_digit_lands_there() {
        for ((locale, mark) in listOf(en to ".", es to ",")) {
            val field = Field(SendAmountEditor(locale, AmountUnit.USD))
            field.typed("1234")
            field.place(1)
            field.typed(mark)
            assertEquals("1234", field.text, "$locale: precondition, the decimal mark is not taken in a whole-dollar USD field")
            assertEquals(1..1, field.caret, "$locale: precondition, the refused key left the caret after the 1 (ZC-1767)")
            val told = field.place(2)
            assertTrue(told, "$locale: ZC-1772: the user's tap after the 2 was not reported to the holder")
            assertEquals(2..2, field.caret, "$locale: ZC-1772: the caret the user tapped is not the caret shown (${field.caret})")
            field.typed("5")
            assertEquals("12534", field.text, "$locale: 5 typed after the tap must land after the 2")
            assertEquals("12534", field.view.settled, "$locale: the amount that counts")
        }
    }

    @Test
    fun zc1772_a_tap_to_the_place_a_refused_proposal_had_moved_the_caret_to_is_reported() {
        val field = Field(SendAmountEditor(en, AmountUnit.USD))
        field.typed("1234")
        field.place(1)
        field.typed(".")
        assertEquals(1..1, field.caret, "precondition: the dot is refused, the caret stays after the 1")
        val edits = field.edits
        assertTrue(field.place(2), "ZC-1772: a real caret move 1 -> 2 after a refused key is dropped as 'no change'")
        assertEquals(edits, field.edits, "a caret move is not an edit")
        assertEquals(2..2, field.caret)
    }

    @Test
    fun zc1772_the_same_after_a_refused_key_over_a_selection() {
        val field = Field(SendAmountEditor(en, AmountUnit.USD))
        field.typed("12.34")
        field.place(1, 2)
        field.typed(".")
        assertEquals("12.34", field.text, "precondition: a second dot replacing the 2 is not taken")
        assertEquals(1..2, field.caret, "precondition: the selection the user had is kept (ZC-1767)")
        assertTrue(field.place(2), "ZC-1772: the user tapped a caret at 2, where the refused key had put it, and it was not reported")
        assertEquals(2..2, field.caret, "the selection 1..2 came back instead of the caret the user tapped")
        field.typed("7")
        assertEquals("127.34", field.text, "7 goes in at the caret the user tapped, it does not replace the old selection")
    }

    // ---- what must stay as it is ----

    @Test
    fun pin_a_select_all_paste_is_still_read_as_a_paste() {
        // 15234.5, select all, paste "1,234.5" (QA2 m4, ZC-1759): the machine must be told the whole text was selected
        val field = Field(SendAmountEditor(en, AmountUnit.ZEC))
        field.typed("15234.5")
        assertTrue(field.place(0, field.text.length), "selecting all is a selection change and is reported")
        field.pasted("1,234.5")
        assertEquals(listOf(0..7), field.reported.takeLast(1), "the machine is told the whole text was selected")
        assertTrue(field.view.settled != "15234.5", "the old amount 15234.5 still counts after a select-all paste of 1,234.5")
    }

    @Test
    fun pin_a_select_all_paste_after_a_refused_key_is_still_read_as_a_paste() {
        val field = Field(SendAmountEditor(en, AmountUnit.USD))
        field.typed("1234")
        field.place(1)
        field.typed(".")
        field.place(0, field.text.length)
        field.pasted("987")
        assertEquals(listOf(0..4), field.reported.takeLast(1), "the machine is told the whole text was selected")
        assertEquals("987", field.view.settled)
    }

    @Test
    fun pin_ordinary_typing_reports_every_key_once_and_the_caret_follows() {
        val field = Field(SendAmountEditor(en, AmountUnit.USD))
        field.typed("1234")
        assertEquals("1234", field.text)
        assertEquals(4..4, field.caret)
        assertEquals(4, field.edits, "one edit per key")
        assertEquals(listOf(0..0, 1..1, 2..2, 3..3), field.reported, "each key reports the caret before it")
        field.place(1)
        field.typed("5")
        assertEquals("15234", field.text)
        assertEquals(2..2, field.caret)
        assertEquals("15234", field.view.settled)
    }

    @Test
    fun pin_a_proposal_with_the_text_and_selection_already_shown_is_not_reported() {
        val field = Field(SendAmountEditor(en, AmountUnit.USD))
        field.typed("1234")
        field.place(2)
        assertTrue(!field.place(2), "the caret already shown is not a change")
        assertEquals(2..2, field.caret)
    }

    @Test
    fun pin_two_identical_proposals_before_the_next_composition_are_reported_once() {
        val filter = Proposals()
        filter.displayed("1234", 4, 4)
        val first = filter.propose("1234", 2, 2)
        assertNotNull(first, "the first move to 2 is a change")
        assertEquals((first.value as StringResource.ByString).value, "1234")
        assertEquals(TextSelection.ByTextRange(TextRange(2)), first.selection)
        assertNull(filter.propose("1234", 2, 2), "the same proposal again, with no composition between, is no change")
    }

    @Test
    fun pin_a_changed_text_is_always_reported_with_the_proposed_selection() {
        val filter = Proposals()
        filter.displayed("1234", 1, 1)
        val report = filter.propose("15234", 2, 2)
        assertNotNull(report, "a changed text is reported")
        assertEquals("15234", (report.value as StringResource.ByString).value)
        assertEquals(TextRange(2), report.getTextRange("15234"))
    }

    // ---- wiring ----

    @Test
    fun the_composable_filters_through_the_class_and_no_longer_compares_with_lastInnerState() {
        val source =
            AmountReadingProbe.source("ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/component/ZashiTextField.kt")
        assertTrue(
            source.contains("ZashiTextFieldProposals"),
            "ZC-1772: TextFieldInternal does not use ZashiTextFieldProposals, the fix would not reach the screen"
        )
        assertTrue(
            !Regex("""lastInnerState\.getTextRange\(""").containsMatchIn(source),
            "ZC-1772: TextFieldInternal still compares the proposal's selection with lastInnerState's range"
        )
        // QA2 m7, ZC-1777: the wiring itself, not only the name. One filter per field, kept across compositions and not reset
        // by a key (a keyed remember would drop the last reported proposal on the very recomposition it must survive); fed
        // by a SideEffect with what the BasicTextField is given; asked from onValueChange with the proposal's text and
        // selection, and only a non-null answer reaches the owner.
        assertEquals(
            1,
            Regex("""ZashiTextFieldProposals\(\)""").findAll(source).count(),
            "ZC-1777: TextFieldInternal must build exactly one ZashiTextFieldProposals"
        )
        assertTrue(
            Regex("""remember\s*\{\s*ZashiTextFieldProposals\(\)\s*\}""").containsMatchIn(source),
            "ZC-1777: the filter must be an unkeyed remember { ZashiTextFieldProposals() }"
        )
        assertTrue(
            Regex(
                """SideEffect\s*\{\s*proposals\s*\.displayed\(\s*textFieldValue\.text,\s*textFieldValue\.selection\.start,""" +
                    """\s*textFieldValue\.selection\.end\s*\)\s*\}"""
            ).containsMatchIn(source),
            "ZC-1777: a SideEffect must call proposals.displayed(textFieldValue.text, selection.start, selection.end)"
        )
        assertTrue(
            Regex(
                """proposals\s*\.propose\(\s*newTextFieldValueState\.text,\s*newTextFieldValueState\.selection\.start,""" +
                    """\s*newTextFieldValueState\.selection\.end\s*\)\s*\?\.let\s*\{\s*state\.onValueChange\(it\)\s*\}"""
            ).containsMatchIn(source),
            "ZC-1777: onValueChange must report only what proposals.propose(text, selection.start, selection.end) returns"
        )
    }

    @Test
    fun pin_ZashiTextFieldProposals_has_no_state_that_is_never_read() {
        val source =
            AmountReadingProbe.source(
                "ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/component/ZashiTextFieldProposals.kt"
            )
        val text =
            source
                .lines()
                .filterNot { line -> listOf("*", "/*", "//").any { line.trimStart().startsWith(it) } }
                .joinToString("\n")
        for (match in Regex("""private var (\w+)""").findAll(text)) {
            val name = match.groupValues[1]
            val uses = Regex("""\b$name\b""").findAll(text).count()
            val writes = Regex("""\b$name\s*=(?!=)""").findAll(text).count()
            // one use is the declaration; a write is not a read
            assertTrue(uses - 1 - writes > 0, "ZC-1777: ZashiTextFieldProposals.$name is written but never read (dead state)")
        }
    }
}
