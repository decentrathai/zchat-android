package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.design.component.AmountUnit
import co.electriccoin.zcash.ui.design.util.AmountFieldState
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * QA2 ZC-1714 (money-path rule 5, the amount parsing path): after a pasted amount settles, the field stays paste-origin, so a
 * typed decimal mark was read as part of a whole paste and dropped ("12." read as "12"): paste "12", type "." then "5" settled
 * 125 where 12.5 was meant; paste "0.5", backspace, type "7" settled "07" = 7 ZEC. The same 10x class as the old comma bug.
 * The tests drive the real field state the way the chat amount fields do (every key press is one acceptAmountInput call on
 * the text the field shows); typing alone always worked and is pinned as a guard.
 */
class MoneyFieldTypedMarkAfterPasteTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")

    private fun paste(
        text: String,
        locale: Locale = en,
        unit: AmountUnit = AmountUnit.ZEC
    ) = acceptAmountInput(AmountFieldState(), text, locale, unit)

    private fun AmountFieldState.type(
        chars: String,
        locale: Locale = en,
        unit: AmountUnit = AmountUnit.ZEC
    ): AmountFieldState = chars.fold(this) { field, c -> acceptAmountInput(field, field.shown + c, locale, unit) }

    private fun AmountFieldState.backspace(
        times: Int = 1,
        locale: Locale = en,
        unit: AmountUnit = AmountUnit.ZEC
    ): AmountFieldState = (1..times).fold(this) { field, _ -> acceptAmountInput(field, field.shown.dropLast(1), locale, unit) }

    @Test
    fun guard_typing_only_gives_12_5() {
        assertEquals("12.5", AmountFieldState().type("12.5").settled)
        assertEquals("12.5", AmountFieldState().type("12,5").settled)
    }

    @Test
    fun a_pasted_12_then_a_typed_dot_and_5_is_12_5_not_125() {
        val field = paste("12").type(".5")
        assertEquals("12.5", field.settled, "paste 12, type . then 5 settled \"${field.settled}\" (shown \"${field.shown}\")")
    }

    @Test
    fun a_pasted_12_then_a_typed_dot_keeps_the_dot_in_the_field() {
        val field = paste("12").type(".")
        assertEquals("12.", field.shown, "the typed dot vanished from the field: shown \"${field.shown}\"")
    }

    @Test
    fun a_pasted_12_then_a_typed_comma_and_5_is_12_5_not_125() {
        val field = paste("12").type(",5")
        assertEquals("12.5", field.settled, "paste 12, type , then 5 settled \"${field.settled}\" (shown \"${field.shown}\")")
    }

    @Test
    fun on_a_spanish_phone_a_pasted_12_then_a_typed_comma_and_5_is_12_5_not_125() {
        val field = paste("12", es).type(",5", es)
        assertEquals("12.5", field.settled, "paste 12, type , then 5 settled \"${field.settled}\" (shown \"${field.shown}\")")
    }

    @Test
    fun on_a_spanish_phone_a_pasted_12_then_a_typed_dot_and_5_is_12_5_not_125() {
        val field = paste("12", es).type(".5", es)
        assertEquals("12.5", field.settled, "paste 12, type . then 5 settled \"${field.settled}\" (shown \"${field.shown}\")")
    }

    @Test
    fun a_pasted_0_5_then_a_backspace_and_a_typed_7_is_0_7_not_7_zec() {
        val field = paste("0.5").backspace().type("7")
        assertEquals("0.7", field.settled, "paste 0.5, backspace, type 7 settled \"${field.settled}\" (shown \"${field.shown}\")")
    }

    @Test
    fun a_pasted_0_5_then_a_backspace_keeps_the_dot_in_the_field() {
        val field = paste("0.5").backspace()
        assertEquals("0.", field.shown, "the dot vanished after the backspace: shown \"${field.shown}\"")
    }

    @Test
    fun a_pasted_100_then_two_backspaces_and_dot_5_is_1_5_not_15() {
        val field = paste("100").backspace(2).type(".5")
        assertEquals("1.5", field.settled, "paste 100, two backspaces, .5 settled \"${field.settled}\" (shown \"${field.shown}\")")
    }

    @Test
    fun a_pasted_amount_in_a_dollar_field_then_a_typed_dot_and_5_is_12_5_not_125() {
        val field = paste("12", unit = AmountUnit.USD).type(".5", unit = AmountUnit.USD)
        assertEquals("12.5", field.settled, "USD: paste 12, type . then 5 settled \"${field.settled}\" (shown \"${field.shown}\")")
    }

    @Test
    fun a_pasted_amount_then_more_digits_still_extends_the_whole_part() {
        assertEquals("123", paste("12").type("3").settled)
    }

    @Test
    fun guard_a_pasted_1_000_still_asks_and_nothing_settles() {
        val asked = paste("1,000")
        assertNotNull(asked.ask)
        assertEquals("", asked.settled)
    }

    @Test
    fun guard_a_pasted_1_500_on_a_spanish_phone_still_asks() {
        val asked = paste("1.500", es)
        assertNotNull(asked.ask)
        assertEquals("", asked.settled)
    }
}
