package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.component.AmountUnit
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * QA2 m4, ZC-1763 (money-path rule 5): the payment request's unit switch re-reads a refused dollar paste in ZEC. A dollar paste
 * of 0.123456780 is refused for its decimals (cents) and keeps the value it reads as without a limit. That value carries the
 * redundant zero the paste had, so it does not fit eight decimals, and the switch to ZEC stays refused although the same text
 * pasted straight into a ZEC field reads 0.12345678. The redundant trailing zeros are stripped before the precision check
 * (GPT review of m3, finding 5).
 */
internal class AmountReaderM4Test {
    private val en: Locale = Locale.US
    private val es: Locale = Locale.forLanguageTag("es-ES")

    private fun mark(locale: Locale) = if (locale == es) "," else "."

    private fun eachLocale(block: (Locale) -> Unit) {
        for (locale in listOf(en, es)) {
            try {
                block(locale)
            } catch (e: AssertionError) {
                throw AssertionError("[$locale] ${e.message}", e)
            }
        }
    }

    @Test
    fun zc1763_a_dollar_paste_of_0_123456780_switched_to_zec_is_0_12345678() {
        eachLocale { locale ->
            val usd = ChatAmountEditor(locale, AmountUnit.USD)
            val zec = ChatAmountEditor(locale, AmountUnit.ZEC)
            val refused = usd.paste("0${mark(locale)}123456780")
            assertEquals(AmountInvalidReason.TooManyDecimals(2), refused.error, "precondition: cents refuse the paste")
            val switched = zec.reread(refused)
            assertNull(switched.error, "the switch to ZEC is still refused (${switched.error}, shown \"${switched.shown}\")")
            assertEquals("0.12345678", switched.settled, "the switch to ZEC settled \"${switched.settled}\"")
        }
    }

    @Test
    fun zc1763_the_same_text_pasted_straight_into_zec_reads_the_same_amount() {
        eachLocale { locale ->
            val zec = ChatAmountEditor(locale, AmountUnit.ZEC)
            assertEquals("0.12345678", zec.paste("0${mark(locale)}123456780").settled)
        }
    }

    @Test
    fun zc1763_the_switch_keeps_every_written_decimal_that_fits_and_strips_only_zeros() {
        eachLocale { locale ->
            val usd = ChatAmountEditor(locale, AmountUnit.USD)
            val zec = ChatAmountEditor(locale, AmountUnit.ZEC)
            val pasted = "1${mark(locale)}234567890"
            val refused = usd.paste(pasted)
            assertEquals(AmountInvalidReason.TooManyDecimals(2), refused.error, "precondition: cents refuse $pasted")
            val switched = zec.reread(refused)
            assertNull(switched.error, "$pasted switched to ZEC is refused (${switched.error})")
            assertEquals("1.23456789", switched.settled, "$pasted switched to ZEC")
        }
    }

    @Test
    fun zc1763_a_paste_with_nine_real_decimals_stays_refused_in_zec() {
        eachLocale { locale ->
            val usd = ChatAmountEditor(locale, AmountUnit.USD)
            val zec = ChatAmountEditor(locale, AmountUnit.ZEC)
            val refused = usd.paste("0${mark(locale)}1234567890123")
            val switched = zec.reread(refused)
            assertEquals(AmountInvalidReason.TooManyDecimals(8), switched.error, "0.1234567890123 must stay refused in ZEC")
            assertEquals("", switched.settled)
        }
    }

    @Test
    fun zc1763_a_field_state_switched_by_the_field_editor_gives_the_same_result() {
        eachLocale { locale ->
            val usd = FieldAmountEditor(locale, AmountUnit.USD)
            val zec = FieldAmountEditor(locale, AmountUnit.ZEC)
            val switched = zec.reread(usd.paste("0${mark(locale)}123456780"))
            assertNull(switched.error, "the switch to ZEC is still refused (${switched.error})")
            assertEquals("0.12345678", switched.settled)
        }
    }
}
