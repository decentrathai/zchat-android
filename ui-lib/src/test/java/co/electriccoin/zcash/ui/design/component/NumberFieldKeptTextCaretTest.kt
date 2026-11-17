package co.electriccoin.zcash.ui.design.component

import androidx.compose.ui.text.TextRange
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.design.util.StringResource
import java.lang.reflect.InvocationTargetException
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 m7 (money-path rule 5, amount input), the number field (ZashiNumberTextField: Swap amount, Pay, slippage, restore
 * height) behind the filter [ZashiTextFieldProposals].
 *
 * ZC-1775 (high; Fable's review of m6, the ZC-1767 symptom in another field): `createTextFieldState` stored the proposal's
 * selection after EVERY proposal, also when the number machine kept its text. 12.34 with the caret after the 1: "." is not
 * taken (a second decimal mark), but the caret moved to 2, and "5" made 125.34 for 152.34 (the same with "-" or a letter
 * in 1234: 12534 for 15234). The required behaviour is the one of ZC-1767 for the amount holder: when the machine keeps the
 * text the field showed, the field keeps the selection it had before the edit, so the next key lands where the user's caret
 * was. A proposal that only moves the selection (same text) is still stored. The rule "a key that cannot be taken is not
 * taken" (owner default 2) is not touched.
 *
 * ZC-1776 for this field: two proposals before a recomposition are each processed against the latest state the field
 * reported, not against the stale `state` the composition captured.
 *
 * The plain-JVM seam. `createTextFieldState` is a private composable and cannot run here, so the fix must move its two
 * decisions into a public class with a no-argument constructor, which `createTextFieldState` keeps in an unkeyed
 * `remember { NumberFieldEditor() }`:
 *
 *     package co.electriccoin.zcash.ui.design.component
 *     class NumberFieldEditor {
 *         // each composition: the state the owner gave, and what ZashiTextField is given (value = the shown text,
 *         // selection coerced to it, as createTextFieldState builds it today). Resets the editor's "latest" to [state].
 *         fun shown(state: NumberTextFieldInnerState, locale: Locale): InnerTextFieldState
 *         // each report of ZashiTextField's filter: the state to hand to NumberTextFieldState.onValueChange. The edit is
 *         // made against the latest state (the one given to shown(), or the last one returned here since), with the
 *         // selection that state shows; text kept by the machine keeps that selection; a selection-only change stores the
 *         // proposal's selection.
 *         fun proposed(proposal: InnerTextFieldState, reading: NumberFieldReading, locale: Locale): NumberTextFieldInnerState
 *     }
 *
 * The class is found by reflection so that this module still compiles at the RED commit; every test that drives it fails
 * with "NumberFieldEditor is missing" until the fix creates it (the failure of the behaviour itself is shown by
 * review-fable/numbercaret-probe.txt: 12.34, caret 1, ".", "5" -> 125.34). The last test reads the source. Tests named
 * `pin_` state what must stay as it is.
 */
class NumberFieldKeptTextCaretTest {
    private class Editor {
        private val type: Class<*> =
            runCatching { Class.forName("co.electriccoin.zcash.ui.design.component.NumberFieldEditor") }.getOrNull()
                ?: fail(
                    "ZC-1775: NumberFieldEditor is missing: the proposal handling of createTextFieldState is inside a private " +
                        "composable and stores the proposal's selection after every proposal"
                )
        private val instance: Any = type.getDeclaredConstructor().newInstance()
        private val shownMethod =
            runCatching { type.getMethod("shown", NumberTextFieldInnerState::class.java, Locale::class.java) }.getOrNull()
                ?: fail("ZC-1775: NumberFieldEditor.shown(NumberTextFieldInnerState, Locale) is missing")
        private val proposedMethod =
            runCatching {
                type.getMethod("proposed", InnerTextFieldState::class.java, NumberFieldReading::class.java, Locale::class.java)
            }.getOrNull() ?: fail("ZC-1775: NumberFieldEditor.proposed(InnerTextFieldState, NumberFieldReading, Locale) is missing")

        private fun call(
            method: java.lang.reflect.Method,
            vararg args: Any?
        ): Any? =
            try {
                method.invoke(instance, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }

        fun shown(
            state: NumberTextFieldInnerState,
            locale: Locale
        ): InnerTextFieldState = call(shownMethod, state, locale) as InnerTextFieldState

        fun proposed(
            proposal: InnerTextFieldState,
            reading: NumberFieldReading,
            locale: Locale
        ): NumberTextFieldInnerState = call(proposedMethod, proposal, reading, locale) as NumberTextFieldInnerState
    }

    /** A number field as TextFieldInternal drives it: the filter, then the editor, the owner keeping what the editor returns. */
    private class NumberField(
        val reading: NumberFieldReading,
        val locale: Locale
    ) {
        private val editor = Editor()
        private val filter = ZashiTextFieldProposals()
        var owner = NumberTextFieldInnerState()
        var text = ""
        var caret: IntRange = 0..0
        val mark: String = if (locale.language == "es") "," else "."

        init {
            composed()
        }

        val amount: BigDecimal? get() = owner.amount

        /** A composition: the BasicTextField is given what the editor shows (a TextFieldValue coerces the selection). */
        fun composed() {
            val inner = editor.shown(owner, locale)
            text = (inner.value as StringResource.ByString).value
            val range = inner.getTextRange(text)
            val start = range.start.coerceIn(0, text.length)
            val end = range.end.coerceIn(0, text.length)
            caret = minOf(start, end)..maxOf(start, end)
            filter.displayed(text, start, end)
        }

        /** BasicTextField proposes ([newText], [start]..[end]); true when the filter let it through. */
        fun callback(
            newText: String,
            start: Int,
            end: Int = start
        ): Boolean {
            val report = filter.propose(newText, start, end) ?: return false
            owner = editor.proposed(report, reading, locale)
            return true
        }

        fun propose(
            newText: String,
            start: Int,
            end: Int = start
        ): Boolean = callback(newText, start, end).also { composed() }

        fun place(
            start: Int,
            end: Int = start
        ): Boolean = propose(text, start, end)

        fun typed(keys: String) {
            for (key in keys) {
                propose(text.substring(0, caret.first) + key + text.substring(caret.last), caret.first + 1)
            }
        }

        fun pasted(clip: String) {
            propose(text.substring(0, caret.first) + clip + text.substring(caret.last), caret.first + clip.length)
        }
    }

    private val locales = listOf(Locale.US, Locale.forLanguageTag("es-ES"))

    private fun cases(): List<Pair<String, () -> NumberField>> =
        listOf("ZEC" to NumberFieldReading.ZEC, "USD" to NumberFieldReading.USD).flatMap { (unit, reading) ->
            locales.map { locale -> "$unit ${locale.language}" to { NumberField(reading, locale) } }
        }

    /** [check] on every reading and locale; every failing case is reported, not only the first. */
    private fun eachCase(check: (String, NumberField) -> Unit) {
        val failures =
            cases().mapNotNull { (name, make) ->
                try {
                    check(name, make())
                    null
                } catch (e: AssertionError) {
                    e.message ?: name
                }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun assertAmount(
        expected: String,
        actual: BigDecimal?,
        message: String
    ) {
        assertNotNull(actual, message)
        assertEquals(0, BigDecimal(expected).compareTo(actual), message)
    }

    // ---- ZC-1775: a key the number machine does not take leaves the caret where it was ----

    @Test
    fun zc1775_a_second_decimal_mark_that_is_not_taken_does_not_move_the_caret_so_the_next_digit_lands_where_it_was() {
        eachCase { name, field ->
            field.typed("12${field.mark}34")
            field.place(1)
            field.typed(field.mark)
            assertEquals("12${field.mark}34", field.text, "$name: precondition, a second decimal mark is not taken")
            assertEquals(1..1, field.caret, "$name: ZC-1775: the caret moved to ${field.caret} although the text stayed ${field.text}")
            field.typed("5")
            assertEquals("152${field.mark}34", field.text, "$name: 5 typed after the refused mark (the report made it 125)")
            assertAmount("152.34", field.amount, "$name: the amount that counts is ${field.amount}")
        }
    }

    @Test
    fun zc1775_a_letter_that_is_not_taken_does_not_move_the_caret_so_the_next_digit_lands_where_it_was() {
        eachCase { name, field ->
            field.typed("1234")
            field.place(1)
            field.typed("a")
            assertEquals("1234", field.text, "$name: precondition, a letter is not taken")
            assertEquals(1..1, field.caret, "$name: ZC-1775: the caret moved to ${field.caret} although the text stayed 1234")
            field.typed("5")
            assertEquals("15234", field.text, "$name: 5 typed after the refused letter (the report made it 12534)")
            assertAmount("15234", field.amount, "$name: the amount that counts is ${field.amount}")
        }
    }

    @Test
    fun zc1775_a_refused_key_over_a_selection_keeps_the_whole_selection() {
        eachCase { name, field ->
            field.typed("12${field.mark}34")
            field.place(1, 2)
            field.typed(field.mark)
            assertEquals("12${field.mark}34", field.text, "$name: precondition, a mark replacing the 2 is not taken")
            assertEquals(1..2, field.caret, "$name: ZC-1775: the selection the user had was ${field.caret} after the refused key")
            field.typed("7")
            assertEquals("17${field.mark}34", field.text, "$name: 7 replaces the selection the user still has")
        }
    }

    @Test
    fun zc1775_a_tap_to_where_the_refused_key_had_put_the_caret_reaches_the_owner_and_the_next_digit_lands_there() {
        eachCase { name, field ->
            field.typed("1234")
            field.place(1)
            field.typed("a")
            assertEquals(1..1, field.caret, "$name: precondition, the refused letter left the caret after the 1")
            assertTrue(field.place(2), "$name: the tap after the 2 was not reported to the owner")
            assertEquals(2..2, field.caret, "$name: the caret the user tapped is not the caret shown")
            field.typed("5")
            assertEquals("12534", field.text, "$name: 5 typed after the tap must land after the 2")
        }
    }

    // ---- ZC-1776: two callbacks before the field recomposes ----

    @Test
    fun zc1776_the_correction_in_a_second_callback_before_recomposition_wins_in_the_number_field() {
        eachCase { name, field ->
            field.typed("12")
            field.place(1, 2)
            assertEquals(1..2, field.caret, "$name: precondition, the final 2 is selected")
            field.callback("19", 2)
            field.callback("12", 2)
            field.composed()
            assertEquals("12", field.text, "$name: ZC-1776: the field shows ${field.text}")
            assertAmount("12", field.amount, "$name: the amount that counts is ${field.amount}")
            assertEquals(2..2, field.caret, name)
        }
    }

    @Test
    fun zc1776_two_typed_keys_before_recomposition_make_the_two_digit_amount() {
        eachCase { name, field ->
            field.callback("1", 1)
            field.callback("12", 2)
            field.composed()
            assertEquals("12", field.text, name)
            assertAmount("12", field.amount, "$name: the amount that counts is ${field.amount}")
            assertEquals(2..2, field.caret, name)
        }
    }

    // ---- what must stay as it is ----

    @Test
    fun pin_a_key_the_machine_takes_moves_the_caret_with_it() {
        eachCase { name, field ->
            field.typed("1234")
            field.place(1)
            field.typed("5")
            assertEquals("15234", field.text, name)
            assertEquals(2..2, field.caret, "$name: the caret follows a taken key")
        }
    }

    @Test
    fun pin_a_selection_only_change_stores_the_new_selection_and_changes_no_amount() {
        eachCase { name, field ->
            field.typed("1234")
            val amount = field.amount
            field.place(2)
            field.place(0, 4)
            field.place(3, 1)
            assertEquals(1..3, field.caret, "$name: a reversed selection is kept")
            assertEquals("1234", field.text, name)
            assertEquals(amount, field.amount, "$name: a caret move is no edit")
        }
    }

    @Test
    fun pin_a_select_all_paste_is_still_read_as_a_paste() {
        // 15234.5, select all, paste "1,234.5" (QA2 m4, ZC-1759): the old amount must not stay
        val field = NumberField(NumberFieldReading.ZEC, Locale.US)
        field.typed("15234.5")
        assertTrue(field.place(0, field.text.length), "selecting all is a selection change and is reported")
        field.pasted("1,234.5")
        assertNotNull(field.amount, "the paste is readable")
        assertAmount("1234.5", field.amount, "the old amount 15234.5 still counts: ${field.amount}")
    }

    @Test
    fun pin_the_shown_selection_is_coerced_to_the_shown_text() {
        val editor = Editor()
        val typed = NumberTextFieldInnerState().edit("12", "", Locale.US, NumberFieldReading.ZEC, 0..0)
        val withFarSelection =
            typed.copy(
                innerTextFieldState = typed.innerTextFieldState.copy(selection = TextSelection.ByTextRange(TextRange(9, 9)))
            )
        val shown = editor.shown(withFarSelection, Locale.US)
        assertEquals("12", (shown.value as StringResource.ByString).value)
        assertEquals(TextRange(2), shown.getTextRange("12"), "an owner selection past the text is coerced to it")
    }

    // ---- wiring ----

    @Test
    fun the_composable_keeps_one_editor_and_hands_it_every_composition_and_every_proposal() {
        val source =
            AmountReadingProbe.source("ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/component/ZashiNumberTextField.kt")
        assertTrue(
            Regex("""remember\s*\{\s*NumberFieldEditor\(\)\s*\}""").containsMatchIn(source),
            "ZC-1775: createTextFieldState does not keep an unkeyed remember { NumberFieldEditor() }"
        )
        assertTrue(
            Regex("""\.shown\(""").containsMatchIn(source) && Regex("""\.proposed\(""").containsMatchIn(source),
            "ZC-1775: createTextFieldState does not call the editor's shown(...) and proposed(...)"
        )
    }
}
