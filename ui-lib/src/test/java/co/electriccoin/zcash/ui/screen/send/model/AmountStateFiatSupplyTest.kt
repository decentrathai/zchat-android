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
 * QA2 m5, ZC-1769 (low, a pin; money-path rules 1 and 5): dollars worth more than the 21,000,000 ZEC that can exist are
 * never Valid in [AmountState.newFromFiat] (the guard at AmountState.kt, QA2 m4 ZC-1764 on the dollar side). The zatoshi of
 * $9223372036854.78 at 50 dollars per ZEC (184467440737.0956 ZEC) is near 2^64, the number the zatoshi wrap of ZC-1764
 * used. Both tests pass at 178518e78; they keep the guard from going away.
 */
class AmountStateFiatSupplyTest {
    private val en: Locale = Locale.US

    private fun rate(price: Double) =
        ExchangeRateState.Data(
            isLoading = false,
            isStale = false,
            isRefreshEnabled = true,
            currencyConversion = FiatCurrencyConversion(timestamp = Clock.System.now(), priceOfZec = price),
            onRefresh = {}
        )

    private fun fromFiat(
        text: String,
        price: Double = 50.0
    ): AmountState = AmountState.newFromFiat(en, "", text, false, rate(price))

    @Test
    fun zc1769_dollars_above_the_supply_times_the_rate_are_invalid() {
        val texts =
            listOf(
                "9223372036854.78",
                "9223372036854.77",
                "1050000000.01",
                "1050000001",
                "99999999999999",
                "184467440738.09",
                "1000000000000000000"
            )
        for (text in texts) {
            val state = fromFiat(text)
            assertTrue(
                state !is AmountState.Valid,
                "$text USD at 50 per ZEC is Valid with ${(state as? AmountState.Valid)?.zatoshi} zatoshi; more ZEC than can exist must disable Send"
            )
        }
    }

    @Test
    fun zc1769_the_guard_follows_the_rate_and_the_whole_supply_in_dollars_is_valid() {
        assertEquals(
            2_100_000_000_000_000L,
            assertIs<AmountState.Valid>(fromFiat("1050000000")).zatoshi.value,
            "21,000,000 ZEC at 50 dollars is valid"
        )
        assertTrue(fromFiat("1050000000.01") !is AmountState.Valid, "one cent above the supply at 50")
        assertTrue(fromFiat("1050000000.01", price = 100.0) is AmountState.Valid, "the same text at 100 per ZEC is half the supply")
        assertTrue(fromFiat("2100000000.01", price = 100.0) !is AmountState.Valid, "one cent above the supply at 100")
    }
}
