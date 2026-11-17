package co.electriccoin.zcash.ui.screen.send.model

import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * QA2 m4, main Send's amount state (money-path rules 1 and 5):
 *
 * - ZC-1764 (medium): a ZEC amount beyond what can exist is never Valid. The ZEC text was converted to zatoshi before it was
 *   compared with the 21,000,000 ZEC supply, so 184467440738.09551616 ZEC (2^64 zatoshi plus one ZEC) wrapped to 1 ZEC and
 *   Send stayed enabled with a quote for 1 ZEC beside a field that said 184 billion. The decimal is compared first.
 * - ZC-1765: an over-limit ZEC amount blanks the dollar text (it showed the dollars of the previous, smaller amount), and
 *   newFromZec / newFromFiat lose their unused `entry` and `previous` parameters (no product caller passes them).
 */
class AmountStateM4Test {
    private val en: Locale = Locale.US

    private val rate =
        ExchangeRateState.Data(
            isLoading = false,
            isStale = false,
            isRefreshEnabled = true,
            currencyConversion = FiatCurrencyConversion(timestamp = Clock.System.now(), priceOfZec = 50.0),
            onRefresh = {}
        )

    private fun empty(): AmountState = AmountState.Invalid("", "", AmountField.ZEC, null)

    private fun edit(
        current: AmountState,
        field: AmountField,
        text: String,
        selection: IntRange?
    ): AmountState =
        AmountState.edit(
            current = current,
            field = field,
            newText = text,
            selectionBefore = selection,
            locale = en,
            isTransparentOrTextRecipient = false,
            exchangeRateState = rate
        )

    @Test
    fun zc1764_an_amount_that_wraps_the_64_bit_zatoshi_is_never_valid() {
        val texts =
            listOf(
                "184467440738.09551616",
                "184467440738.59551616",
                "184467440738.00000000",
                "184467440737.09551616",
                "18446744073709551616",
                "21000000.00000001",
                "21000001"
            )
        for (text in texts) {
            val state = edit(empty(), AmountField.ZEC, text, 0..0)
            assertTrue(
                state !is AmountState.Valid,
                "$text ZEC is Valid with ${(state as? AmountState.Valid)?.zatoshi} zatoshi; more ZEC than can exist must disable Send"
            )
        }
    }

    @Test
    fun zc1764_the_same_amounts_typed_digit_by_digit_are_never_valid_once_above_the_supply() {
        var state = empty()
        var text = ""
        for (c in "184467440738.09551616") {
            val next = text + c
            state = edit(state, AmountField.ZEC, next, text.length..text.length)
            text = next
            val integer = text.substringBefore('.').toBigInteger()
            if (integer > 21_000_000.toBigInteger()) {
                assertTrue(state !is AmountState.Valid, "typed \"$text\" is Valid with ${(state as? AmountState.Valid)?.zatoshi} zatoshi")
            }
        }
    }

    @Test
    fun guard_zc1764_the_whole_supply_is_valid_and_one_zatoshi_more_is_not() {
        val all = assertIs<AmountState.Valid>(edit(empty(), AmountField.ZEC, "21000000", 0..0))
        assertEquals(2_100_000_000_000_000L, all.zatoshi.value)
        assertTrue(edit(empty(), AmountField.ZEC, "21000000.00000001", 0..0) !is AmountState.Valid)
        assertEquals(
            2_099_999_999_999_999L,
            assertIs<AmountState.Valid>(edit(empty(), AmountField.ZEC, "20999999.99999999", 0..0)).zatoshi.value
        )
    }

    @Test
    fun zc1765_an_over_limit_zec_amount_blanks_the_dollar_text() {
        val valid = assertIs<AmountState.Valid>(edit(empty(), AmountField.ZEC, "21000000", 0..0))
        assertEquals("1050000000", valid.fiatValue, "precondition: the dollars of 21,000,000 ZEC at 50")
        val over = edit(valid, AmountField.ZEC, "210000001", 8..8)
        assertTrue(over !is AmountState.Valid, "210,000,001 ZEC is Valid")
        assertEquals("", over.fiatValue, "the dollar text \"${over.fiatValue}\" belongs to the old amount, not to ${over.value}")
    }

    @Test
    fun zc1765_a_select_all_paste_of_an_over_limit_amount_blanks_the_dollar_text() {
        val five = assertIs<AmountState.Valid>(edit(empty(), AmountField.ZEC, "5", 0..0))
        assertEquals("250", five.fiatValue, "precondition")
        val over = edit(five, AmountField.ZEC, "21000001", 0..1)
        assertTrue(over !is AmountState.Valid)
        assertEquals("", over.fiatValue, "the dollar text \"${over.fiatValue}\" belongs to the old 5 ZEC")
    }

    @Test
    fun zc1765_newFromZec_and_newFromFiat_take_no_entry_and_no_previous_text() {
        val methods =
            AmountState.Companion::class.java.methods.filter { it.name == "newFromZec" || it.name == "newFromFiat" }
        assertTrue(methods.isNotEmpty(), "newFromZec / newFromFiat are gone")
        for (method in methods) {
            val entryParameters = method.parameterTypes.filter { it.simpleName == "AmountEntry" }
            assertTrue(entryParameters.isEmpty(), "${method.name} still takes an AmountEntry (${method.parameterCount} parameters)")
        }
        assertEquals(
            listOf(6, 5),
            listOf("newFromZec", "newFromFiat").map { name -> methods.single { it.name == name }.parameterCount },
            "newFromZec(locale, value, fiatValue, isTransparentOrTextRecipient, exchangeRateState, lastFieldChangedByUser) and " +
                "newFromFiat(locale, value, fiatValue, isTransparentOrTextRecipient, exchangeRateState)"
        )
    }
}
