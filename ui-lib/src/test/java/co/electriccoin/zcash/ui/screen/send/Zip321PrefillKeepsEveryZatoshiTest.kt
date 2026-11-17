package co.electriccoin.zcash.ui.screen.send

import cash.z.ecc.android.sdk.ext.convertZatoshiToZecString
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.call
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.method
import co.electriccoin.zcash.ui.screen.send.model.AmountField
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * F1 (fix-all 2.13.0, train T1): an amount the app writes into main Send keeps every zatoshi and reads back on every
 * phone language.
 *
 * At HEAD, AndroidSend.kt writes amounts as text and reads the text back:
 * - a scanned ZIP-321 request or a resend: `convertZatoshiToZecString(locale)`, which the SDK rounds to 6 decimals,
 *   so a request for 0.12345678 ZEC is filled as 0.123457 (12,345,700 zatoshi, 22 more than asked);
 * - Back from review and Send all: `toZecString(locale)`, exact but grouped ("1’000" on a Swiss phone, "١٬٠٠٠" on an
 *   Arabic one), which the field's own reader then refuses as invalid.
 * And the Send button hands the field's text, not the amount, to the SDK's parser a second time.
 *
 * Wanted: `AmountState.fromZatoshi` builds the state from the zatoshi itself (the text is written with the no-grouping
 * field formatter), the three write sites use it, and the Send button pays the state's zatoshi.
 */
class Zip321PrefillKeepsEveryZatoshiTest {
    private val companion = AmountState.Companion

    private fun fromZatoshi(
        zatoshi: Long,
        locale: Locale
    ): AmountState =
        call(
            method(AmountState.Companion::class.java, "fromZatoshi", 6),
            companion,
            Zatoshi(zatoshi),
            locale,
            false,
            ExchangeRateState.OptedOut,
            AmountField.ZEC,
            ""
        ) as AmountState

    private fun reread(
        value: String,
        locale: Locale
    ): AmountState =
        call(
            method(AmountState.Companion::class.java, "newFromZec", 6),
            companion,
            locale,
            value,
            "",
            false,
            ExchangeRateState.OptedOut,
            AmountField.ZEC
        ) as AmountState

    @Test
    fun `the old prefill text loses zatoshi`() {
        // Why the prefill must not go through the SDK's default formatter (a control on the SDK, not on ZChat).
        val text = Zatoshi(12_345_678).convertZatoshiToZecString(locale = Locale.US)
        assertNotEquals("0.12345678", text)
    }

    @Test
    fun `a ZIP-321 request for 0_12345678 ZEC is filled to the zatoshi`() {
        for (locale in listOf(Locale.US, Locale.forLanguageTag("es-ES"))) {
            val state = assertIs<AmountState.Valid>(fromZatoshi(12_345_678, locale))
            assertEquals(12_345_678L, state.zatoshi.value, "@ $locale")
            assertEquals(12_345_678L, assertIs<AmountState.Valid>(reread(state.value, locale)).zatoshi.value)
        }
    }

    @Test
    fun `1000 ZEC on a Swiss and an Arabic phone is valid and not grouped`() {
        for (tag in listOf("de-CH", "ar-EG")) {
            val locale = Locale.forLanguageTag(tag)
            val state = assertIs<AmountState.Valid>(fromZatoshi(100_000_000_000, locale), "@ $tag")
            assertFalse(state.value.any { it == '’' || it == '٬' || it == ',' }, "grouped @ $tag: ${state.value}")
            assertEquals(
                100_000_000_000L,
                assertIs<AmountState.Valid>(reread(state.value, locale), "re-read @ $tag").zatoshi.value
            )
        }
    }

    @Test
    fun `AndroidSend writes every amount from zatoshi`() {
        val source = AmountReadingProbe.source(ANDROID_SEND)
        assertFalse("convertZatoshiToZecString(" in source, "the ZIP-321 prefill still rounds through the SDK formatter")
        assertFalse("toZecString(" in source, "Back from review or Send all still writes grouped text")
        assertTrue(Regex("""AmountState\.fromZatoshi\(""").findAll(source).count() >= 3, "prefill, Back and Send all")
        assertTrue("AmountState.refreshed(" in source, "the recipient/rate effect must go through AmountState.refreshed")
    }

    @Test
    fun `the Send button pays the amount, not a second parse of the text`() {
        val source = AmountReadingProbe.source(SEND_VIEW)
        assertFalse("zecString = amountState.value" in source, "SendButton re-parses the field text with the SDK")
        assertTrue("amountState.zatoshi" in source.substringAfter("fun SendButton("))
    }

    private companion object {
        const val ANDROID_SEND = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/send/AndroidSend.kt"
        const val SEND_VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/send/view/SendView.kt"
    }
}
