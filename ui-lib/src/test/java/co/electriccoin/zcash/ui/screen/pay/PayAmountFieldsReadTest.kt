package co.electriccoin.zcash.ui.screen.pay

import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.design.component.InnerTextFieldState
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.ask
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.paste
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.plain
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.text
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.type
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.TextSelection
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * F12 (fix-all 2.13.0, train T3): the Pay screen's two amount fields (the token the recipient gets, and dollars) read
 * through F1's amount reader.
 *
 * At 657570d65 a pasted "1.500" on a Spanish phone is 1500 tokens with no question (an English 1.5: a 1000x overpay
 * of the quote), the token field has no precision (the quote cuts the amount down at the token's decimals), and the
 * figure the app writes into the other field is a formatted number whose amount is not the one it shows (the dollar
 * field of a token change is grouped: "1,234.50").
 *
 * After F12 the token field reads with the token's decimals (its ticker, no unit) and the dollar field with 2 (unit
 * USD); an ambiguous paste asks and empties the other field until a reading is tapped; the other field is written as
 * plain text at its own precision and holds exactly the amount it shows.
 */
class PayAmountFieldsReadTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")

    private val chain = SwapBlockchain(chainTicker = "eth", chainName = stringRes("Ethereum"), chainIcon = imageRes(0))

    private val zec =
        ZecSwapAsset(
            tokenTicker = "ZEC",
            tokenName = stringRes("Zcash"),
            tokenIcon = imageRes(0),
            blockchain = chain,
            usdPrice = BigDecimal("30"),
            assetId = "zec",
            decimals = 8
        )

    private val usdc =
        DynamicSwapAsset(
            tokenTicker = "USDC",
            tokenName = stringRes("USD Coin"),
            tokenIcon = imageRes(0),
            usdPrice = BigDecimal("0.9999"),
            assetId = "usdc",
            decimals = 6,
            blockchain = chain
        )

    private var changed: Pair<NumberTextFieldInnerState, NumberTextFieldInnerState>? = null

    private fun state(
        amount: NumberTextFieldInnerState = NumberTextFieldInnerState(),
        fiat: NumberTextFieldInnerState = NumberTextFieldInnerState(),
        asset: SwapAsset = usdc
    ): PayState =
        ExactOutputVMMapper().createState(
            internalState =
                InternalStateImpl(
                    address = "0xrecipient",
                    isABHintVisible = false,
                    selectedABContact = null,
                    asset = asset,
                    amount = amount,
                    fiatAmount = fiat,
                    slippage = BigDecimal.ONE,
                    isRequestingQuote = false,
                    account = null,
                    swapAssets = SwapAssetsData(data = listOf(zec, asset), zecAsset = zec),
                    isEphemeralAddressLocked = false
                ),
            onBack = {},
            onSwapInfoClick = {},
            onSwapAssetPickerClick = {},
            onSlippageClick = {},
            onRequestSwapQuoteClick = { _, _ -> },
            onTryAgainClick = {},
            onAddressChange = {},
            onTextFieldChange = { a, f -> changed = a to f },
            onQrCodeScannerClick = {},
            onAddressBookClick = {},
            onDeleteSelectedContactClick = {}
        )

    @Test
    fun the_token_field_reads_with_the_token_s_decimals_and_the_dollar_field_with_2() {
        val pay = state()
        assertEquals(NumberFieldProbe.Reading(6, null, true, "USDC"), NumberFieldProbe.reading(pay.amount))
        assertEquals(NumberFieldProbe.Reading(2, "USD", true, null), NumberFieldProbe.reading(pay.amountFiat))
        assertEquals("1.123456", plain(type(pay.amount, en, "1", ".", "1", "2", "3", "4", "5", "6", "7").innerState.amount))
        assertEquals("1.25", plain(type(pay.amountFiat, en, "1", ".", "2", "5", "9").innerState.amount))
    }

    @Test
    fun a_spanish_paste_of_1_500_asks_and_empties_the_dollar_field_until_a_reading_is_tapped() {
        val pay = state()
        val asked = paste(pay.amount, "1.500", es)
        assertEquals(listOf("1.5", "1500"), ask(asked.innerState))
        pay.amount.onValueChange(asked.innerState)
        val (amount, fiat) = assertNotNull(changed)
        assertNull(amount.amount, "no amount counts while the question is open")
        assertNull(fiat.amount, "the dollar field can't show a figure for an amount nobody chose")
        assertEquals("", text(fiat))

        val chosen = NumberFieldProbe.choose(asked, "1.5")
        pay.amount.onValueChange(chosen)
        val (answered, dollars) = assertNotNull(changed)
        assertEquals("1.5", plain(answered.amount))
        assertEquals("1.5", text(dollars), "1.5 x 0.9999 = 1.49985 is 1.50 at 2 decimals, written plain")
        assertEquals("1.5", plain(dollars.amount), "the dollar field holds what it shows")
    }

    @Test
    fun dollars_typed_write_the_token_amount_at_the_token_s_precision() {
        val pay = state()
        val typed = type(pay.amountFiat, en, "1", "0")
        pay.amountFiat.onValueChange(typed.innerState)
        val (token, _) = assertNotNull(changed)
        // 10 / 0.9999 = 10.001000100010001... -> 10.0010001 would be 7 decimals; USDC has 6.
        assertEquals("10.001", text(token))
        assertEquals("10.001", plain(token.amount), "the token field holds exactly the amount it shows")
    }

    @Test
    fun a_token_change_writes_the_dollar_figure_plain_never_grouped() {
        val amount =
            NumberTextFieldInnerState(
                innerTextFieldState = InnerTextFieldState(value = stringRes("1234.5"), selection = TextSelection.End),
                amount = BigDecimal("1234.5"),
                lastValidAmount = BigDecimal("1234.5")
            )
        val fiat = ExactOutputVMMapper().createFiatAmountInnerState(amount, NumberTextFieldInnerState(), usdc)
        // 1234.5 x 0.9999 = 1234.37655 -> 1234.38
        assertEquals("1234.38", text(fiat))
        assertEquals("1234.38", plain(fiat.amount))
    }

    @Test
    fun an_amount_with_more_decimals_than_a_newly_chosen_token_is_not_used() {
        val typed =
            NumberTextFieldInnerState(
                innerTextFieldState = InnerTextFieldState(value = stringRes("0.1234567"), selection = TextSelection.End),
                amount = BigDecimal("0.1234567"),
                lastValidAmount = BigDecimal("0.1234567")
            )
        val pay = state(amount = typed)
        assertNull(pay.amount.innerState.amount)
        assertFalse(assertNotNull(pay.primaryButton).isEnabled)
    }

    @Test
    fun the_view_model_prefills_a_scanned_amount_with_the_field_s_precision() {
        val vm = AmountReadingProbe.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/pay/PayVM.kt")
        assertFalse("NumberTextFieldInnerState.fromAmount(result.amount)" in vm, "the QR prefill ignores the precision")
    }
}
