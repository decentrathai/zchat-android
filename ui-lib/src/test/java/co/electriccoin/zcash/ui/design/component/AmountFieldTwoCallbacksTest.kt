package co.electriccoin.zcash.ui.design.component

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import co.electriccoin.zcash.ui.design.util.AmountEditResult
import co.electriccoin.zcash.ui.design.util.AmountEditor
import co.electriccoin.zcash.ui.design.util.AmountView
import co.electriccoin.zcash.ui.design.util.ChatAmountEditor
import co.electriccoin.zcash.ui.design.util.FieldAmountEditor
import co.electriccoin.zcash.ui.design.util.SendAmountEditor
import co.electriccoin.zcash.ui.design.util.StringResource
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA2 m7 (money-path rule 5, amount input), ZC-1776 (medium; GPT's review of m6, confirmed by Fable): two IME callbacks
 * arrive before the field recomposes. USD 12, the user selects the final 2 and the keyboard sends two proposals,
 * "19" and then "12" (a correction), both with the caret at 2. The second text equals the text the composition shows, so
 * both layers used to treat it as "nothing changed":
 *
 * - [ZashiTextFieldProposals] compared a proposal with the text the field SHOWS and with the last range, so the second
 *   proposal ("12", caret 2) was dropped and the owner kept 19. Required: it compares a proposal with the last REPORTED
 *   proposal (text and range); [ZashiTextFieldProposals.displayed] resets that baseline to what the field shows.
 * - [AmountTextFieldSelection.onValueChange] is given the text the composition showed (`shown`, stale after the first
 *   callback) and skipped the edit because `new.text == shown`. Required: it processes an edit against the latest value it
 *   reported (text and selection), not against the composition's stale `shown`; the machine is told the selection before
 *   each edit as the holder last reported it. The signature stays (shown, new, onEdit); a composition is a call of
 *   [AmountTextFieldSelection.valueFor], as at the call sites.
 *
 * The number field (ZashiNumberTextField) is tested in [NumberFieldKeptTextCaretTest]. Tests named `pin_` already pass at
 * 734e3ef68 and keep what the fix must not change.
 */
class AmountFieldTwoCallbacksTest {
    private val en: Locale = Locale.US

    // ---- the filter ----

    @Test
    fun zc1776_a_second_proposal_equal_to_the_shown_text_is_reported_when_the_first_one_differed() {
        for ((start, end) in listOf(1 to 2, 2 to 2)) {
            val filter = ZashiTextFieldProposals()
            filter.displayed("12", start, end)
            val first = filter.propose("19", 2, 2)
            assertNotNull(first, "$start..$end: 19 differs from what is shown")
            val second = filter.propose("12", 2, 2)
            assertNotNull(second, "$start..$end: ZC-1776: the correction back to 12 equals the shown text and was dropped, 19 stays")
            assertEquals("12", second.value.plain())
            assertEquals(TextRange(2), second.getTextRange("12"))
        }
    }

    @Test
    fun pin_a_proposal_back_to_the_shown_range_is_reported_after_a_different_one() {
        val filter = ZashiTextFieldProposals()
        filter.displayed("1234", 2, 2)
        assertNotNull(filter.propose("1234", 3, 3), "a move to 3 is a change")
        // a text change in between, then back to the text and range that are shown
        assertNotNull(filter.propose("12345", 5, 5), "a text change is a change")
        assertNotNull(filter.propose("1234", 2, 2), "back to the text and caret the field shows differs from the last report")
    }

    @Test
    fun pin_the_third_proposal_equal_to_the_last_reported_one_is_not_reported_again() {
        val filter = ZashiTextFieldProposals()
        filter.displayed("12", 1, 2)
        filter.propose("19", 2, 2)
        filter.propose("12", 2, 2)
        assertNull(filter.propose("12", 2, 2), "the same proposal again, with no composition between, is reported once")
    }

    @Test
    fun pin_a_composition_resets_the_baseline_to_what_the_field_shows() {
        val filter = ZashiTextFieldProposals()
        filter.displayed("12", 1, 2)
        assertNotNull(filter.propose("19", 2, 2))
        // the owner refused 19; the field is shown 12 with the old selection again
        filter.displayed("12", 1, 2)
        assertNotNull(filter.propose("19", 2, 2), "the same refused proposal is a change again after the field was shown 12")
        filter.displayed("12", 1, 2)
        assertNull(filter.propose("12", 1, 2), "the text and selection the field shows are no change")
    }

    @Test
    fun pin_a_proposal_equal_to_what_is_shown_is_not_reported() {
        val filter = ZashiTextFieldProposals()
        filter.displayed("1234", 2, 2)
        assertNull(filter.propose("1234", 2, 2))
        assertNotNull(filter.propose("1234", 3, 3))
    }

    // ---- the holder, called with the stale text of the composition ----

    /** A field whose composition happens only on [composed]; the call sites pass that composition's text as `shown`. */
    private class Deferred(
        val editor: AmountEditor
    ) {
        // QA2 m8 (W-137 m8): the holder is made with the text a state shows and edits the state it is handed.
        val holder = AmountTextFieldSelection<AmountView> { it.shown }
        var view: AmountView = editor.empty()
        var seen: String = ""
        val reported = mutableListOf<IntRange>()

        private fun edit(
            state: AmountView,
            changed: String,
            selectionBefore: IntRange
        ): AmountEditResult<AmountView> {
            reported += selectionBefore
            val next = editor.edit(state, changed, selectionBefore)
            return AmountEditResult(next, taken = next.handle !== state.handle)
        }

        init {
            composed()
        }

        val value: TextFieldValue get() = holder.valueFor(view.shown)
        val caret: IntRange get() = value.selection.min..value.selection.max

        fun composed() {
            seen = view.shown
            holder.onComposition(view)
            holder.valueFor(seen)
        }

        /** The keyboard proposes ([newText], [start]..[end]) while the composition still shows [seen]. */
        fun callback(
            newText: String,
            start: Int,
            end: Int = start
        ) {
            holder.onValueChange(TextFieldValue(newText, TextRange(start, end)), ::edit) { view = it }
        }

        fun typed(keys: String) {
            for (key in keys) {
                val selection = value.selection
                val shown = value.text
                callback(shown.substring(0, selection.min) + key + shown.substring(selection.max), selection.min + 1)
                composed()
            }
        }

        fun place(
            start: Int,
            end: Int = start
        ) {
            callback(value.text, start, end)
            composed()
        }
    }

    private fun fields(): List<Pair<String, () -> Deferred>> =
        listOf(
            "send" to { Deferred(SendAmountEditor(en, AmountUnit.USD)) },
            "chat" to { Deferred(ChatAmountEditor(en, AmountUnit.USD)) },
            "field" to { Deferred(FieldAmountEditor(en, AmountUnit.USD)) }
        )

    private fun eachField(check: (String, Deferred) -> Unit) {
        val failures =
            fields().mapNotNull { (name, make) ->
                try {
                    check(name, make())
                    null
                } catch (e: AssertionError) {
                    e.message ?: name
                }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun zc1776_the_holder_takes_the_second_callback_before_recomposition_and_the_correction_wins() {
        eachField { name, field ->
            field.typed("12")
            field.place(1, 2)
            field.callback("19", 2)
            field.callback("12", 2)
            assertEquals("12", field.view.settled, "$name: ZC-1776: the correction to 12 was skipped, the amount is ${field.view.settled}")
            assertEquals("12", field.view.shown, name)
            assertEquals(listOf(1..2, 2..2), field.reported.takeLast(2), "$name: the machine gets the last reported selection")
            field.composed()
            assertEquals("12", field.value.text, name)
            assertEquals(2..2, field.caret, "$name: the caret of the last callback")
        }
    }

    @Test
    fun zc1776_the_second_of_two_typed_keys_before_recomposition_is_edited_against_the_first() {
        eachField { name, field ->
            field.callback("1", 1)
            field.callback("12", 2)
            assertEquals("12", field.view.settled, name)
            assertEquals(listOf(0..0, 1..1), field.reported, "$name: the second key is a key at the caret the first one left (1), not at 0")
        }
    }

    @Test
    fun pin_one_callback_per_composition_is_edited_against_the_composition() {
        eachField { name, field ->
            field.typed("12")
            field.place(1, 2)
            field.callback("19", 2)
            field.composed()
            field.callback("12", 2)
            field.composed()
            assertEquals("12", field.view.settled, name)
            assertEquals(2..2, field.caret, name)
        }
    }

    // ---- the whole chain: filter and holder, no composition between the callbacks ----

    /** BasicTextField, the filter and the holder wired as ZashiTextField and [AmountTextFieldSelection.textFieldState] wire them. */
    private class Chain(
        val editor: AmountEditor
    ) {
        val filter = ZashiTextFieldProposals()

        // QA2 m8 (W-137 m8): the holder is made with the text a state shows and edits the state it is handed.
        val holder = AmountTextFieldSelection<AmountView> { it.shown }
        var view: AmountView = editor.empty()
        private var seen = ""
        val reported = mutableListOf<IntRange>()

        private fun edit(
            state: AmountView,
            changed: String,
            selectionBefore: IntRange
        ): AmountEditResult<AmountView> {
            reported += selectionBefore
            val next = editor.edit(state, changed, selectionBefore)
            return AmountEditResult(next, taken = next.handle !== state.handle)
        }

        init {
            composed()
        }

        val value: TextFieldValue get() = holder.valueFor(view.shown)
        val caret: IntRange get() = value.selection.min..value.selection.max

        fun composed() {
            seen = view.shown
            holder.onComposition(view)
            val shown = holder.valueFor(seen)
            filter.displayed(shown.text, shown.selection.start, shown.selection.end)
        }

        fun callback(
            newText: String,
            start: Int,
            end: Int = start
        ) {
            val report = filter.propose(newText, start, end) ?: return
            val text = report.value.plain()
            holder.onValueChange(TextFieldValue(text, report.getTextRange(text)), ::edit) { view = it }
        }

        fun typed(keys: String) {
            for (key in keys) {
                val selection = value.selection
                val shown = value.text
                callback(shown.substring(0, selection.min) + key + shown.substring(selection.max), selection.min + 1)
                composed()
            }
        }
    }

    @Test
    fun zc1776_both_callbacks_before_recomposition_through_the_filter_and_the_holder_leave_12() {
        val failures =
            listOf(
                "send" to { Chain(SendAmountEditor(en, AmountUnit.USD)) },
                "chat" to { Chain(ChatAmountEditor(en, AmountUnit.USD)) },
                "field" to { Chain(FieldAmountEditor(en, AmountUnit.USD)) }
            ).mapNotNull { (name, make) ->
                try {
                    val field = make()
                    field.typed("12")
                    field.callback("12", 1, 2)
                    field.composed()
                    assertEquals(1..2, field.caret, "$name: precondition, the final 2 is selected")
                    field.callback("19", 2)
                    field.callback("12", 2)
                    field.composed()
                    assertEquals("12", field.view.settled, "$name: ZC-1776: 19 stays; the correction never reached the machine")
                    assertEquals("12", field.value.text, "$name: the field shows ${field.value.text}")
                    assertEquals(2..2, field.caret, name)
                    null
                } catch (e: AssertionError) {
                    e.message ?: name
                }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}

private fun StringResource.plain(): String = (this as StringResource.ByString).value
