package co.electriccoin.zcash.ui.design.component

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import co.electriccoin.zcash.ui.design.util.AmountEditResult
import co.electriccoin.zcash.ui.design.util.AmountEditor
import co.electriccoin.zcash.ui.design.util.AmountFieldState
import co.electriccoin.zcash.ui.design.util.AmountView
import co.electriccoin.zcash.ui.design.util.ChatAmountEditor
import co.electriccoin.zcash.ui.design.util.FieldAmountEditor
import co.electriccoin.zcash.ui.design.util.SendAmountEditor
import co.electriccoin.zcash.ui.screen.ai.acceptUsdAmountInput
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 m5 (money-path rule 5, amount input), the behaviour of [AmountTextFieldSelection] on the plain JVM.
 *
 * - ZC-1767 (high; Fable's review of m4, GPT finding 1): the holder moved the caret to where the field put it even when
 *   the amount machine kept the text it had (a key that is not taken, owner default 2 of OWNER_DECISIONS_AMOUNT_FIELDS).
 *   The USD field showing 1234 with the caret after the 1: "." is not taken, then "5" landed at 2 and made 12534 (a
 *   tenfold amount from two keys); the dollar field 12.34 with the caret after the 1: "," is not taken, then "0" made
 *   120.34 for 102.34. The holder keeps the selection the field had before the edit when the machine keeps its text, so the
 *   next key lands where the user's caret was. The rule "a key that cannot be taken is not taken" is not touched here.
 * - ZC-1768 (medium): the holder had only a source scan. [Field] below drives the compiled holder the way its four call
 *   sites do (value = valueFor(shown), onValueChange = onValueChange(shown, value) { edit with the selection before }),
 *   ported from Fable's HolderSim: a fake field applies each user action to the value the holder hands it, as
 *   BasicTextField does. Tests named `pin_` already pass at 178518e78 and keep the behaviour the m4 fix relies on.
 */
class AmountTextFieldSelectionTest {
    private val en: Locale = Locale.US

    /** The AI top-up custom USD field: acceptUsdAmountInput with the selection. */
    private class AiUsdEditor(
        override val locale: Locale
    ) : AmountEditor {
        override val unit: AmountUnit = AmountUnit.USD

        override fun empty(): AmountView = ChatAmountEditor.view(AmountFieldState())

        override fun edit(
            from: AmountView,
            newText: String,
            selectionBefore: IntRange?
        ): AmountView = ChatAmountEditor.view(acceptUsdAmountInput(from.handle as AmountFieldState, newText, locale, selectionBefore))

        override fun choose(
            from: AmountView,
            option: String
        ): AmountView = ChatAmountEditor.view((from.handle as AmountFieldState).choose(BigDecimal(option), unit.maxDecimals))
    }

    /** A fake text field over the real holder and one amount editor. */
    private class Field(
        val editor: AmountEditor
    ) {
        // QA2 m8 (W-137 m8): the holder is made with the text a state shows and edits the state it is handed.
        val holder = AmountTextFieldSelection<AmountView> { it.shown }
        var view: AmountView = editor.empty()
        var edits = 0
        val reported = mutableListOf<IntRange>()

        /** What the field shows now, as the call sites ask for it. */
        val value: TextFieldValue get() = holder.valueFor(view.shown)
        val text: String get() = value.text
        val caret: IntRange get() = value.selection.min..value.selection.max

        /** The field proposes [new] while it shows [view]'s text, as BasicTextField's onValueChange does. */
        fun propose(new: TextFieldValue) {
            holder.onComposition(view)
            holder.onValueChange(
                new,
                edit = { state, newText, selectionBefore ->
                    edits++
                    reported += selectionBefore
                    val next = editor.edit(state, newText, selectionBefore)
                    AmountEditResult(next, taken = next.handle !== state.handle)
                },
                onState = { view = it }
            )
        }

        fun place(
            start: Int,
            end: Int = start
        ) = propose(TextFieldValue(text, TextRange(start, end)))

        /** [keys] arrive at the selection the field shows now, one proposal each. */
        fun typed(keys: String) {
            for (key in keys) {
                val selection = value.selection
                val shown = text
                propose(
                    TextFieldValue(
                        shown.substring(0, selection.min) + key + shown.substring(selection.max),
                        TextRange(selection.min + 1)
                    )
                )
            }
        }

        fun pasted(clip: String) {
            val selection = value.selection
            val shown = text
            propose(
                TextFieldValue(
                    shown.substring(0, selection.min) + clip + shown.substring(selection.max),
                    TextRange(selection.min + clip.length)
                )
            )
        }
    }

    private fun usdFields(): List<Pair<String, Field>> =
        listOf(
            "chat" to Field(ChatAmountEditor(en, AmountUnit.USD)),
            "field" to Field(FieldAmountEditor(en, AmountUnit.USD)),
            "send" to Field(SendAmountEditor(en, AmountUnit.USD)),
            "ai" to Field(AiUsdEditor(en))
        )

    /** [check] on every USD field; every failing field is reported, not only the first. */
    private fun eachUsdField(check: (String, Field) -> Unit) {
        val failures =
            usdFields().mapNotNull { (name, field) ->
                try {
                    check(name, field)
                    null
                } catch (e: AssertionError) {
                    e.message ?: name
                }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ---- ZC-1767: a key that is not taken leaves the caret where it was ----

    @Test
    fun zc1767_a_dot_that_is_not_taken_does_not_move_the_caret_so_the_next_digit_lands_where_it_was() {
        eachUsdField { name, field ->
            field.typed("1234")
            field.place(1)
            field.typed(".")
            assertEquals("1234", field.text, "$name: precondition, the dot is not taken in a whole-dollar field with 3 decimals")
            assertEquals(1..1, field.caret, "$name: the caret moved to ${field.caret} although the text stayed 1234")
            field.typed("5")
            assertEquals("15234", field.text, "$name: 5 typed after the refused dot")
            assertEquals("15234", field.view.settled, "$name: the amount that counts (the unit-test of the report made it 12534)")
        }
    }

    @Test
    fun zc1767_a_comma_that_is_not_taken_does_not_move_the_caret_so_the_next_digit_lands_where_it_was() {
        eachUsdField { name, field ->
            field.typed("12.34")
            field.place(1)
            field.typed(",")
            assertEquals("12.34", field.text, "$name: precondition, the comma is not taken")
            assertEquals(1..1, field.caret, "$name: the caret moved to ${field.caret} although the text stayed 12.34")
            field.typed("0")
            assertEquals("102.34", field.text, "$name: 0 typed after the refused comma (the report made it 120.34)")
            assertEquals("102.34", field.view.settled, "$name: the amount that counts")
        }
    }

    @Test
    fun zc1767_a_third_cent_digit_that_is_not_taken_keeps_the_caret_where_it_was() {
        eachUsdField { name, field ->
            field.typed("12.34")
            field.place(3)
            field.typed("7")
            assertEquals("12.34", field.text, "$name: precondition, 7 right after the dot would be a third cent digit")
            assertEquals(3..3, field.caret, "$name: the caret moved to ${field.caret} although the text stayed 12.34")
        }
    }

    @Test
    fun zc1767_a_refused_key_over_a_selection_keeps_the_whole_selection() {
        eachUsdField { name, field ->
            field.typed("12.34")
            field.place(1, 2)
            field.typed(".")
            val kept = field.text == "12.34"
            assertTrue(kept, "$name: precondition, a second dot replacing the 2 is not taken (text ${field.text})")
            assertEquals(1..2, field.caret, "$name: the selection the user had was ${field.caret} after the refused key")
            field.typed("7")
            assertEquals("17.34", field.text, "$name: 7 replaces the selection the user still has")
        }
    }

    // ---- ZC-1768: the holder's behaviour on the JVM (pins unless named zc1767) ----

    @Test
    fun pin_a_key_the_machine_takes_leaves_the_caret_where_the_field_put_it() {
        eachUsdField { name, field ->
            field.typed("1234")
            field.place(1)
            field.typed("5")
            assertEquals("15234", field.text, name)
            assertEquals(2..2, field.caret, "$name: the caret follows a taken key")
        }
    }

    @Test
    fun pin_a_selection_only_change_keeps_the_new_caret_and_asks_for_no_edit() {
        eachUsdField { name, field ->
            field.typed("1234")
            val before = field.edits
            field.place(2)
            field.place(0, 4)
            field.place(3, 1)
            assertEquals(before, field.edits, "$name: a caret move is not an edit")
            assertEquals(1..3, field.caret, "$name: a reversed selection is kept")
            assertEquals("1234", field.text, name)
        }
    }

    @Test
    fun pin_the_machine_gets_the_selection_the_field_displayed_before_the_edit() {
        eachUsdField { name, field ->
            field.typed("1234")
            field.place(3, 1)
            field.reported.clear()
            field.typed("9")
            assertEquals(listOf(1..3), field.reported, "$name: start..end, smaller first")
            assertEquals("194", field.text, name)

            field.place(0)
            field.reported.clear()
            field.typed("8")
            assertEquals(listOf(0..0), field.reported, "$name: a caret is start == end")
        }
    }

    @Test
    fun pin_every_edit_is_reported_with_the_selection_the_field_showed_after_a_selection_only_change() {
        eachUsdField { name, field ->
            field.typed("1234")
            field.place(1)
            field.place(3)
            field.reported.clear()
            field.typed("5")
            assertEquals(listOf(3..3), field.reported, "$name: the last caret the field reported is the one before the edit")
        }
    }

    @Test
    fun pin_a_select_all_paste_that_shares_its_ends_with_the_old_text_is_not_one_typed_comma() {
        // 15234.5, select all, paste "1,234.5": the old amount must not stay (QA2 m4, ZC-1759)
        val field = Field(SendAmountEditor(en, AmountUnit.ZEC))
        field.typed("15234.5")
        field.place(0, field.text.length)
        field.pasted("1,234.5")
        assertEquals(listOf(0..7), field.reported.takeLast(1), "the machine is told the whole text was selected")
        assertTrue(field.view.settled != "15234.5", "the old amount 15234.5 still counts after a select-all paste of 1,234.5")
    }

    @Test
    fun pin_a_select_all_paste_of_the_same_text_is_no_edit() {
        val field = Field(ChatAmountEditor(en, AmountUnit.USD))
        field.typed("1234")
        val edits = field.edits
        field.place(0, 4)
        field.pasted("1234")
        assertEquals(edits, field.edits, "the text did not change, so the machine is not asked")
        assertEquals("1234", field.view.settled)
    }

    @Test
    fun pin_a_state_change_from_outside_the_field_shows_the_machines_text_with_a_selection_in_range() {
        val field = Field(ChatAmountEditor(en, AmountUnit.ZEC))
        field.pasted("1,000")
        assertNotNull(field.view.options, "precondition: 1,000 is a question")
        assertEquals(field.view.shown, field.text, "the holder shows the text of the open question")
        field.place(field.text.length)
        // the user taps a reading: the machine rewrites the text outside the field
        field.view = field.editor.choose(field.view, "1000")
        val value = field.value
        assertEquals(field.view.shown, value.text, "the holder must show the machine's text, not its last one")
        assertTrue(value.selection.min >= 0 && value.selection.max <= value.text.length, "selection ${field.caret} outside ${value.text}")
        assertEquals("1000", field.view.settled)
        // and the next key goes to the rewritten text
        field.place(value.text.length)
        field.typed("5")
        assertEquals("10005", field.view.settled)
    }

    @Test
    fun pin_a_text_emptied_by_the_machine_leaves_an_in_range_selection() {
        val field = Field(ChatAmountEditor(en, AmountUnit.ZEC))
        field.pasted("1,000")
        field.place(field.text.length)
        // a key on the open question empties it
        field.typed("7")
        val value = field.value
        assertEquals(field.view.shown, value.text)
        assertTrue(value.selection.max <= value.text.length && value.selection.min >= 0, "selection ${field.caret} outside [${value.text}]")
    }
}
