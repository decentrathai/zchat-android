package co.electriccoin.zcash.ui.screen.send.model

import androidx.compose.runtime.saveable.SaverScope
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.call
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.getter
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.method
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * T1 fixup for main Send (AmountState).
 *
 * X-F1-1 (must-fix): at d27d46dba (F1) a pasted "$5" in the ZEC field is Valid(5 ZEC), and a pasted "5 ZEC" or "ⓩ5" in
 * the USD field is $5, because the shared reader drops every currency sign in every field. Before F1 the ZEC field
 * refused '$'. Wanted: the ZEC field reads with the ZEC unit and the USD field with the USD unit. The other unit's
 * sign is a paste error (OtherUnit), so Send stays disabled. It blanks the other side like any paste error (H3),
 * survives a rate tick and a rotation, and the field's own sign still reads.
 *
 * X-F1-3 (should-fix): after a USD paste that asks ("1,000") or is refused, the rate goes stale and the user types a
 * ZEC amount. The ZEC field then passes the state's fiatValue, which is the unanswered paste. With no fresh rate,
 * newFromZec keeps it, so the disabled USD field shows "1,000" that nobody chose. The payment is the ZEC typed, so only
 * the display is wrong. Wanted: AmountState.fiatValueKeptByZecEdit is "" for a USD question or a USD paste error, and
 * the fiatValue otherwise. The ZEC field and the Send-all fill pass it.
 */
class AmountStateOtherUnitTest {
    private val en = Locale.US
    private val companion = AmountState.Companion

    private fun rate(
        price: Double = 50.0,
        stale: Boolean = false
    ) = ExchangeRateState.Data(
        isLoading = false,
        isStale = stale,
        isRefreshEnabled = true,
        currencyConversion = FiatCurrencyConversion(timestamp = Clock.System.now(), priceOfZec = price),
        onRefresh = {}
    )

    private fun fromZec(
        value: String,
        entry: String,
        fiatValue: String = "",
        exchangeRateState: ExchangeRateState = rate(),
    ): AmountState = zecEntry(value, en, entry, "", fiatValue, exchangeRateState)

    private fun fromFiat(
        fiatValue: String,
        entry: String,
        value: String = "",
        exchangeRateState: ExchangeRateState = rate(),
    ): AmountState = fiatEntry(fiatValue, en, entry, value, "", exchangeRateState)

    private fun reason(state: AmountState): String? {
        if (state !is AmountState.Invalid) return null
        return state.reason?.let(AmountReadingProbe::reasonName)
    }

    private fun keptFiat(state: AmountState): String = getter(state, "getFiatValueKeptByZecEdit") as String

    @Test
    fun `a pasted dollar amount in the ZEC field is refused, not 5 ZEC`() {
        for (paste in listOf("$5", "US$5", "5 USD")) {
            val state = fromZec(paste, "PASTE", fiatValue = "250")
            assertFalse(state is AmountState.Valid, "$paste pays ${(state as? AmountState.Valid)?.zatoshi} in the ZEC field")
            assertEquals("OtherUnit(USD)", reason(state), "$paste: $state")
            assertEquals(paste, state.value, "the field shows the paste")
            assertEquals("", state.fiatValue, "a paste error blanks the USD side")
        }
    }

    @Test
    fun `a pasted ZEC amount in the USD field is refused and blanks the ZEC side`() {
        for (paste in listOf("5 ZEC", "ⓩ5")) {
            val state = fromFiat(paste, "PASTE", value = "0.1")
            assertFalse(state is AmountState.Valid, "$paste reads as dollars: $state")
            assertEquals("OtherUnit(ZEC)", reason(state), "$paste: $state")
            assertEquals("", state.value, "H3: the old ZEC amount is gone")
        }
    }

    @Test
    fun `each field's own sign still reads (control)`() {
        assertEquals(500_000_000L, assertIs<AmountState.Valid>(fromZec("5 ZEC", "PASTE")).zatoshi.value)
        assertEquals(10_000_000L, assertIs<AmountState.Valid>(fromFiat("$5", "PASTE")).zatoshi.value, "\$5 at \$50")
    }

    @Test
    fun `the sign error survives a rate tick and a rotation`() {
        val error = fromZec("$5", "PASTE")
        assertEquals("OtherUnit(USD)", reason(error), "precondition: $error")
        val refreshed =
            call(method(AmountState.Companion::class.java, "refreshed", 5), companion, error, false, en, false, rate(51.0))
        assertEquals(error, refreshed)
        val saver = AmountState.Saver
        val saved = with(saver) { SaverScope { true }.save(error) }
        assertEquals(error, saver.restore(saved!!))
    }

    @Test
    fun `X-F1-3 a ZEC edit after an unanswered USD paste keeps no dollar text`() {
        val question = fromFiat("1,000", "PASTE", value = "5")
        assertEquals("Ask", question.javaClass.simpleName, "precondition: $question")
        assertEquals("", keptFiat(question), "an unanswered USD paste is not a dollar amount")
        val typed = fromZec("2", "KEYSTROKE", fiatValue = keptFiat(question), exchangeRateState = rate(stale = true))
        assertEquals(200_000_000L, assertIs<AmountState.Valid>(typed).zatoshi.value)
        assertEquals("", typed.fiatValue, "the USD field shows a paste nobody chose")

        val refused = fromFiat("1,2,3", "PASTE", value = "5")
        assertEquals("", keptFiat(refused), "a refused USD paste is not a dollar amount")
    }

    @Test
    fun `X-F1-3 a dollar amount the user entered is kept as before (control)`() {
        val typedUsd = fromFiat("100", "STORED")
        assertEquals("100", keptFiat(typedUsd))
        val noRate = fromFiat("100", "STORED", exchangeRateState = ExchangeRateState.OptedOut)
        assertEquals("100", keptFiat(noRate), "a readable dollar amount with no rate stays, as at 3e5f33f95")
        val zecQuestion = fromZec("1,000", "PASTE")
        assertEquals(zecQuestion.fiatValue, keptFiat(zecQuestion))
    }

    @Test
    fun `X-F1-3 the ZEC field and the Send-all fill pass the kept dollar text`() {
        // QA2 m3: SendView's ZEC field edits through AmountState.edit, which passes the kept dollar text.
        val sendView = AmountReadingProbe.source(SEND_VIEW)
        assertTrue("AmountState.editResult(" in sendView && "field = AmountField.ZEC," in sendView, "the ZEC field")
        val amountState = AmountReadingProbe.source(AMOUNT_STATE)
        assertTrue("fiatValue = current.fiatValueKeptByZecEdit," in amountState, "the ZEC field's edit")
        val androidSend = AmountReadingProbe.source(ANDROID_SEND)
        assertTrue("fiatValueIfNoRate = amountState.fiatValueKeptByZecEdit," in androidSend, "the Send-all fill")
    }

    private companion object {
        const val SEND_VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/send/view/SendView.kt"
        const val ANDROID_SEND = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/send/AndroidSend.kt"
        const val AMOUNT_STATE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/send/model/AmountState.kt"
    }
}
