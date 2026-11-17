package co.electriccoin.zcash.ui.screen.send.model

import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock

/**
 * R2-1 (a), plan step p2-6ccb00a01f88: the Send screen amount (AmountState) pays what was typed and
 * what the screen shows. "0.5" on a German phone used to become 5 ZEC and "0,00001" on a US phone
 * used to become 1 ZEC, because the lone non-locale mark was stripped as grouping.
 */
class AmountStateTest {
    private val en = Locale.US
    private val de = Locale.GERMANY
    private val es = Locale.forLanguageTag("es-ES")

    private fun rate(
        isStale: Boolean = false,
        isLoading: Boolean = false,
        price: Double = 50.0
    ) = ExchangeRateState.Data(
        isLoading = isLoading,
        isStale = isStale,
        isRefreshEnabled = true,
        currencyConversion = FiatCurrencyConversion(timestamp = Clock.System.now(), priceOfZec = price),
        onRefresh = {}
    )

    private fun fromZec(
        value: String,
        locale: Locale,
        transparent: Boolean = false,
        exchangeRateState: ExchangeRateState = ExchangeRateState.OptedOut,
        fiatValue: String = ""
    ) = AmountState.newFromZec(
        locale = locale,
        value = value,
        fiatValue = fiatValue,
        isTransparentOrTextRecipient = transparent,
        exchangeRateState = exchangeRateState
    )

    private fun zatoshiOf(state: AmountState): Long = assertIs<AmountState.Valid>(state).zatoshi.value

    // --- The misreads (RED on the parent) ---

    @Test
    fun `0_5 typed with a dot on a German phone pays half a ZEC, not 5`() =
        assertEquals(50_000_000L, zatoshiOf(fromZec("0.5", de)))

    @Test
    fun `0_00001 typed with a dot on a German phone pays 1000 zatoshi, not 1 ZEC`() =
        assertEquals(1_000L, zatoshiOf(fromZec("0.00001", de)))

    @Test
    fun `0,5 typed with a comma on a US phone pays half a ZEC, not 5`() =
        assertEquals(50_000_000L, zatoshiOf(fromZec("0,5", en)))

    @Test
    fun `0,00001 typed with a comma on a US phone pays 1000 zatoshi, not 1 ZEC`() =
        assertEquals(1_000L, zatoshiOf(fromZec("0,00001", en)))

    @Test
    fun `0_5 typed with a dot on a Spanish phone pays half a ZEC, not 5`() =
        assertEquals(50_000_000L, zatoshiOf(fromZec("0.5", es)))

    @Test
    fun `the text the screen keeps is the amount that is paid`() {
        // The normalized value is what the field shows next; re-reading it must give the same zatoshi.
        val state = assertIs<AmountState.Valid>(fromZec("0.5", de))
        assertEquals(state.zatoshi.value, zatoshiOf(fromZec(state.value, de)))
    }

    // --- Controls ---

    // F1 (fix-all 2.13.0): the field's own text (typed keystroke by keystroke, or written by the app) is read as it
    // stands: its single mark is the decimal point, whatever the language, and it never holds grouping. A PASTED
    // "1,234" asks "1.234 or 1234?" instead (AmountStatePasteAmbiguityTest); these were R2-1 a's grouping reads.

    @Test
    fun `1,234 in the field on a US phone is 1_234 ZEC, the typed comma is the decimal point`() =
        assertEquals(123_400_000L, zatoshiOf(fromZec("1,234", en)))

    @Test
    fun `1_234 in the field on a German phone is 1_234 ZEC`() = assertEquals(123_400_000L, zatoshiOf(fromZec("1.234", de)))

    @Test
    fun `two marks in the field's own text are refused, never guessed`() {
        assertIs<AmountState.Invalid>(fromZec("1.234,5", de))
    }

    @Test
    fun `a zero amount to a transparent address is refused`() {
        assertIs<AmountState.Invalid>(fromZec("0", en, transparent = true))
    }

    @Test
    fun `a zero amount to a shielded address stays valid for a memo-only send`() =
        assertEquals(0L, zatoshiOf(fromZec("0", en, transparent = false)))

    @Test
    fun `a fiat amount at a stale rate still shows the ZEC that is paid`() {
        // Formatting a fresh fiat figure needs android.icu (not on the JVM), so the fresh ZEC-to-fiat
        // fill is left to the device run; the money invariant is pinned here for both rate states.
        for (stale in listOf(false, true)) {
            val state =
                assertIs<AmountState.Valid>(
                    AmountState.newFromFiat(
                        locale = de,
                        value = "",
                        fiatValue = "100",
                        isTransparentOrTextRecipient = false,
                        exchangeRateState = rate(isStale = stale, price = 50.0)
                    )
                )
            assertEquals(200_000_000L, state.zatoshi.value, "stale=$stale")
            assertEquals(state.zatoshi.value, zatoshiOf(fromZec(state.value, de)), "stale=$stale")
        }
    }

    @Test
    fun `a stale rate does not rewrite the fiat field`() {
        val state =
            assertIs<AmountState.Valid>(
                fromZec("2", en, exchangeRateState = rate(isStale = true), fiatValue = "typed")
            )
        assertEquals("typed", state.fiatValue)
        assertEquals(200_000_000L, state.zatoshi.value)
    }

    @Test
    fun `a fiat amount converts at the rate and the ZEC shown re-reads to the zatoshi paid`() {
        for (locale in listOf(en, de, es)) {
            val fiat = if (locale == en) "12.5" else "12,5"
            val state =
                assertIs<AmountState.Valid>(
                    AmountState.newFromFiat(
                        locale = locale,
                        value = "",
                        fiatValue = fiat,
                        isTransparentOrTextRecipient = false,
                        exchangeRateState = rate(price = 50.0)
                    )
                )
            assertEquals(25_000_000L, state.zatoshi.value, "fiat $fiat @ $locale")
            assertEquals(state.zatoshi.value, zatoshiOf(fromZec(state.value, locale)), "ZEC shown @ $locale")
        }
    }

    @Test
    fun `a fiat amount without any rate is refused`() {
        assertIs<AmountState.Invalid>(
            AmountState.newFromFiat(
                locale = en,
                value = "",
                fiatValue = "10",
                isTransparentOrTextRecipient = false,
                exchangeRateState = ExchangeRateState.OptedOut
            )
        )
    }
}
