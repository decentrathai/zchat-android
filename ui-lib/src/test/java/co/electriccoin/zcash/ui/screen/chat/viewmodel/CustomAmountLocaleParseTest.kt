package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.util.Locale
import kotlin.test.Test

/**
 * RED regression pin for finding [14] (amount-parsing-locale) — ZchatComposeVM.onCustomAmountChange
 * parsed the custom ZEC amount with the locale-INDEPENDENT `String.toDoubleOrNull()`, so on any device
 * whose locale uses ',' as the decimal separator a "2,5" input parsed to null -> 0 zatoshi and the send
 * silently collapsed to the dust minimum (0.00001 ZEC) instead of the intended 2.5 ZEC.
 *
 * The parse is now extracted to the pure companion helper [ZchatComposeVM.parseCustomAmountZatoshi],
 * which routes through the same ZashiNumberTextFieldParser (normalizeInput + toBigDecimalOrNull) the
 * main Send screen uses (AmountState.kt), honoring the device Locale.
 *
 * RED-on-the-buggy-body: with the old toDoubleOrNull()-based body, `parseCustomAmountZatoshi("2,5",
 * Locale.GERMANY)` returns 0 and the 250_000_000 assertion fails. The locale-aware body makes it green.
 */
class CustomAmountLocaleParseTest {

    @Test
    fun `German comma-decimal amount parses to the intended zatoshi, not 0`() {
        // 2.5 ZEC = 250,000,000 zatoshi. The comma is the German decimal separator.
        assertEquals(250_000_000L, ZchatComposeVM.parseCustomAmountZatoshi("2,5", Locale.GERMANY))
    }

    @Test
    fun `US dot-decimal amount still parses correctly`() {
        assertEquals(250_000_000L, ZchatComposeVM.parseCustomAmountZatoshi("2.5", Locale.US))
    }

    @Test
    fun `a small German comma-decimal amount reaches the dust magnitude, not 0`() {
        // 0.00001 ZEC = 1000 zatoshi (the platform dust minimum).
        assertEquals(1_000L, ZchatComposeVM.parseCustomAmountZatoshi("0,00001", Locale.GERMANY))
    }

    @Test
    fun `the root cause - a comma decimal is unparseable by the old locale-independent toDoubleOrNull`() {
        assertNull("2,5".toDoubleOrNull())
    }
}
