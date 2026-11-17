package co.electriccoin.zcash.ui.screen.send.model

import androidx.compose.runtime.saveable.SaverScope
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.AmountUnit
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.call
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.getter
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.method
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.plain
import co.electriccoin.zcash.ui.design.util.SendAmountEditor
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * F1 (fix-all 2.13.0, train T1): main Send's ZEC and USD fields (AmountState) ask when a paste reads two ways.
 *
 * At HEAD, AmountState reads every text with the R2-1 a rule: a single non-locale mark with 3 digits after it is
 * grouping. So a pasted "1.500" on a Spanish phone (an English 1.5 from an exchange) is 1500 ZEC, 1000 times too much,
 * with no question, and en-US "1,000" is 1000 ZEC here but 1 ZEC in the chat dialogs.
 *
 * Wanted, with the review holes the design fixed:
 * - a pasted "1,000" (ZEC or USD) is an Ask state: Send needs Valid, so it stays disabled until a reading is tapped;
 * - H1: text the field already holds (STORED) never asks: "1,500" on a Spanish phone stays 1.5 when the recipient or
 *   the rate changes, "1.000" on an English phone stays 1;
 * - H3: a fiat question or a fiat error blanks the ZEC side, and a rate tick or a recipient change leaves an open
 *   question and a paste error as they are, so the old ZEC amount never comes back and enables Send by itself;
 * - H2: the ZEC the app writes after a fiat entry is not grouped, so typing one more digit makes it larger;
 * - H12: the Ask state survives a rotation;
 * - H14: a typed mark shows at once as the phone's decimal mark, and "1,000" typed on an English phone is 1 ZEC;
 * - the reading tapped is what the state holds.
 */
class AmountStatePasteAmbiguityTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")

    private fun rate(price: Double = 50.0) =
        ExchangeRateState.Data(
            isLoading = false,
            isStale = false,
            isRefreshEnabled = true,
            currencyConversion = FiatCurrencyConversion(timestamp = Clock.System.now(), priceOfZec = price),
            onRefresh = {}
        )

    private val companion = AmountState.Companion

    private fun fromZec(
        value: String,
        locale: Locale,
        entry: String,
        previous: String = "",
        fiatValue: String = "",
        exchangeRateState: ExchangeRateState = ExchangeRateState.OptedOut,
    ): AmountState = zecEntry(value, locale, entry, previous, fiatValue, exchangeRateState)

    private fun fromFiat(
        fiatValue: String,
        locale: Locale,
        entry: String,
        value: String = "",
        previous: String = "",
        exchangeRateState: ExchangeRateState = rate(),
    ): AmountState = fiatEntry(fiatValue, locale, entry, value, previous, exchangeRateState)

    private fun refreshed(
        current: AmountState,
        exchangeRateState: ExchangeRateState
    ): AmountState {
        val refresh = method(AmountState.Companion::class.java, "refreshed", 5)
        return call(refresh, companion, current, false, en, false, exchangeRateState) as AmountState
    }

    private fun options(state: AmountState): List<String> {
        assertEquals("Ask", state.javaClass.simpleName, "expected a question, got $state")
        return (getter(state, "getOptions") as List<*>).map { plain(it as BigDecimal) }
    }

    private fun zatoshi(state: AmountState): Long = assertIs<AmountState.Valid>(state).zatoshi.value

    @Test
    fun `a pasted 1,000 in the ZEC field asks and blanks the USD side`() {
        val state = fromZec("1,000", en, "PASTE", fiatValue = "50")
        assertEquals(listOf("1", "1000"), options(state))
        assertEquals("1,000", state.value, "the field shows the paste")
        assertEquals("", state.fiatValue)
        assertFalse(state is AmountState.Valid, "Send needs Valid")
    }

    @Test
    fun `a pasted 1_500 on a Spanish phone asks instead of paying 1500`() {
        assertEquals(listOf("1.5", "1500"), options(fromZec("1.500", es, "PASTE")))
    }

    @Test
    fun `a pasted 1,000 in the USD field asks and blanks the ZEC side`() {
        val state = fromFiat("1,000", en, "PASTE", value = "5")
        assertEquals(listOf("1", "1000"), options(state))
        assertEquals("", state.value, "H3: the old ZEC amount is gone")
    }

    @Test
    fun `H1 stored text never asks`() {
        assertEquals(150_000_000L, zatoshi(fromZec("1,500", es, "STORED")))
        assertEquals(100_000_000L, zatoshi(fromZec("1.000", en, "STORED")))
    }

    @Test
    fun `H3 a rate tick leaves an open question and a paste error as they are`() {
        val question = fromFiat("1,000", en, "PASTE", value = "5")
        assertEquals(question, refreshed(question, rate(price = 51.0)))

        val error = fromFiat("1,2,3", en, "PASTE", value = "5")
        assertEquals("", error.value, "a fiat error blanks the ZEC side")
        val afterTick = refreshed(error, rate(price = 51.0))
        assertFalse(afterTick is AmountState.Valid, "the old 5 ZEC came back after a rate tick: $afterTick")
    }

    @Test
    fun `a rate tick re-reads a typed amount without asking`() {
        val typed = fromZec("1,500", es, "STORED")
        assertEquals(150_000_000L, zatoshi(refreshed(typed, rate(price = 51.0))))
    }

    @Test
    fun `H2 the ZEC written after a fiat entry is not grouped and grows with a digit`() {
        val state = fromFiat("1000", en, "STORED", exchangeRateState = rate(price = 0.5))
        assertEquals(200_000_000_000L, zatoshi(state))
        assertFalse(',' in state.value, "grouped: ${state.value}")
        val grown = fromZec(state.value + "5", en, "KEYSTROKE", previous = state.value)
        assertTrue(zatoshi(grown) > zatoshi(state), "'${state.value}' + 5 read as ${zatoshi(grown)}")
    }

    @Test
    fun `H12 the question survives a rotation`() {
        val state = fromZec("1,000", en, "PASTE")
        val saver = AmountState.Saver
        val saved = with(saver) { SaverScope { true }.save(state) }
        val restored = saver.restore(saved!!)
        assertEquals(state, restored)
    }

    @Test
    fun `H14 a typed mark shows at once as the phone's mark and 1,000 typed is 1 ZEC`() {
        assertEquals("1.", fromZec("1,", en, "KEYSTROKE", previous = "1").value)
        assertEquals("1,", fromZec("1.", es, "KEYSTROKE", previous = "1").value)
        var state = fromZec("1", en, "KEYSTROKE")
        for (key in listOf(",", "0", "0", "0")) {
            state = fromZec(state.value + key, en, "KEYSTROKE", previous = state.value)
        }
        assertEquals(100_000_000L, zatoshi(state), "1 , 0 0 0 typed on an English phone")
    }

    @Test
    fun `the reading tapped is what the state holds`() {
        val question = fromZec("1,000", en, "PASTE")
        for ((choice, zat) in listOf("1000" to 100_000_000_000L, "1" to 100_000_000L)) {
            val chosen =
                call(
                    method(AmountState.Companion::class.java, "choose", 5),
                    companion,
                    question,
                    BigDecimal(choice),
                    en,
                    false,
                    ExchangeRateState.OptedOut
                ) as AmountState
            assertEquals(zat, zatoshi(chosen))
        }
    }

    @Test
    fun `H5 editing a question discards the paste, it never answers the question (QA2 m3, ZC-1746)`() {
        // m2: "editing a paste keeps reading it as a paste" (the question came back). m3 (owner decision W-137): a key on
        // an open question discards the paste, the field is empty and the next key types afresh.
        with(SendAmountEditor(en, AmountUnit.ZEC)) {
            val question = paste("1,000")
            val backspaced = question.backspaced()
            assertFalse(backspaced.handle is AmountState.Ask, "the question must close: ${backspaced.shown}")
            assertEquals("", backspaced.shown)
            assertEquals("", backspaced.settled)
            val retyped = backspaced.typed("0")
            assertFalse(retyped.handle is AmountState.Ask, "the question must not come back: ${retyped.shown}")
            assertEquals("0", retyped.settled)
        }
    }
}
