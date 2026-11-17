package co.electriccoin.zcash.ui.screen.send.model

import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * QA2 ZC-1714 (money-path rule 5), main Send: the ZEC and USD fields stay paste-origin after a pasted amount settles, so a
 * typed decimal mark was read as part of a whole paste and dropped: paste "12", type "." then "5" settled 125 where 12.5
 * was meant. Each key press goes through [AmountState.edit] the way SendView's onValueChange calls it (QA2 m3: the one
 * amount state machine, no paste origin).
 */
class AmountStateTypedMarkAfterPasteTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")
    private val noRate = ExchangeRateState.OptedOut

    private fun zecEdit(
        state: AmountState,
        newValue: String,
        locale: Locale
    ): AmountState = AmountState.edit(state, AmountField.ZEC, newValue, null, locale, false, noRate)

    private fun fiatEdit(
        state: AmountState,
        newValue: String,
        locale: Locale
    ): AmountState = AmountState.edit(state, AmountField.FIAT, newValue, null, locale, false, noRate)

    private fun emptyZec() = AmountState.newFromZec(en, "", "", false, noRate)

    private fun pasteZec(
        text: String,
        locale: Locale = en
    ): AmountState = zecEdit(emptyZec(), text, locale)

    private fun AmountState.typeZec(
        chars: String,
        locale: Locale = en
    ): AmountState = chars.fold(this) { s, c -> zecEdit(s, s.value + c, locale) }

    private fun AmountState.backspaceZec(
        locale: Locale = en
    ): AmountState = zecEdit(this, value.dropLast(1), locale)

    private fun zatoshi(state: AmountState): Long = assertIs<AmountState.Valid>(state).zatoshi.value

    @Test
    fun guard_typing_only_gives_12_5() {
        assertEquals(1_250_000_000L, zatoshi(emptyZec().typeZec("12.5")))
    }

    @Test
    fun a_pasted_12_then_a_typed_dot_and_5_is_12_5_not_125() {
        val state = pasteZec("12").typeZec(".5")
        assertEquals("12.5", state.value, "paste 12, type . then 5 shows \"${state.value}\"")
        assertEquals(1_250_000_000L, zatoshi(state))
    }

    @Test
    fun a_pasted_12_then_a_typed_dot_keeps_the_dot_in_the_field() {
        val state = pasteZec("12").typeZec(".")
        assertEquals("12.", state.value, "the typed dot vanished from the field: shown \"${state.value}\"")
    }

    @Test
    fun on_a_spanish_phone_a_pasted_12_then_a_typed_comma_and_5_is_12_5_not_125() {
        val state = pasteZec("12", es).typeZec(",5", es)
        assertEquals("12,5", state.value, "paste 12, type , then 5 shows \"${state.value}\"")
        assertEquals(1_250_000_000L, zatoshi(state))
    }

    @Test
    fun a_pasted_0_5_then_a_backspace_and_a_typed_7_is_0_7_not_7_zec() {
        val state = pasteZec("0.5").backspaceZec().typeZec("7")
        assertEquals(70_000_000L, zatoshi(state), "paste 0.5, backspace, type 7 shows \"${state.value}\"")
    }

    @Test
    fun a_pasted_100_then_two_backspaces_and_dot_5_is_1_5_not_15() {
        val state = pasteZec("100").backspaceZec().backspaceZec().typeZec(".5")
        assertEquals(150_000_000L, zatoshi(state), "paste 100, two backspaces, .5 shows \"${state.value}\"")
    }

    @Test
    fun in_the_dollar_field_a_pasted_12_then_a_typed_dot_and_5_is_12_5_not_125() {
        var state = fiatEdit(emptyZec(), "12", en)
        for (c in ".5") state = fiatEdit(state, state.fiatValue + c, en)
        assertEquals("12.5", state.fiatValue, "USD: paste 12, type . then 5 shows \"${state.fiatValue}\"")
    }

    @Test
    fun guard_a_pasted_1_000_still_asks() {
        assertIs<AmountState.Ask>(pasteZec("1,000"))
    }

    @Test
    fun guard_a_pasted_1_500_on_a_spanish_phone_still_asks() {
        assertIs<AmountState.Ask>(pasteZec("1.500", es))
    }
}
