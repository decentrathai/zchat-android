package co.electriccoin.zcash.ui.screen.swap

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
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.readError
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.type
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
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
import kotlin.test.assertTrue

/**
 * F12 (fix-all 2.13.0, train T3; review hole H11): the Swap amount field reads with the precision of what it holds.
 *
 * At 657570d65 the field has no precision: 9 decimals of ZEC or 7 of a 6-decimal token are taken, and the quote then
 * cuts the amount down at the asset's decimals (NearSwapDataSourceImpl: movePointRight(decimals), RoundingMode.DOWN),
 * so the amount asked for is not the amount typed. A cap of 8 would refuse valid amounts of an 18-decimal token. And
 * on a Spanish phone a pasted "1.500" (an English 1.5) is 1500 with no question.
 *
 * After F12 the field's NumberFieldReading is: ZEC 8 (unit ZEC) when swapping from ZEC, the origin asset's own decimals
 * (no unit, its ticker) when swapping into ZEC, 2 (unit USD) in the dollar mode; it asks on an ambiguous paste; an
 * amount typed under one asset that has more decimals than a newly chosen asset is not used; and the dollar switch
 * hands over a figure at the precision of the mode it switches to.
 */
class SwapAmountFieldUsesAssetDecimalsTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")

    private val chain = SwapBlockchain(chainTicker = "eth", chainName = stringRes("Ethereum"), chainIcon = imageRes(0))

    private val zec =
        ZecSwapAsset(
            tokenTicker = "ZEC",
            tokenName = stringRes("Zcash"),
            tokenIcon = imageRes(0),
            blockchain = chain,
            usdPrice = BigDecimal("33.333"),
            assetId = "zec",
            decimals = 8
        )

    private fun token(
        ticker: String,
        decimals: Int,
        price: String = "1"
    ) = DynamicSwapAsset(
        tokenTicker = ticker,
        tokenName = stringRes(ticker),
        tokenIcon = imageRes(0),
        usdPrice = BigDecimal(price),
        assetId = ticker.lowercase(),
        decimals = decimals,
        blockchain = chain
    )

    private val eth = token("ETH", 18, "3000")
    private val usdc = token("USDC", 6)

    private var swappedTo: BigDecimal? = null
    private var swapClicked = false

    private fun state(
        mode: Mode,
        asset: SwapAsset,
        currencyType: CurrencyType = CurrencyType.TOKEN,
        amount: NumberTextFieldInnerState = NumberTextFieldInnerState()
    ): SwapState =
        ExactInputVMMapper().createState(
            internalState =
                InternalStateImpl(
                    account = null,
                    swapAsset = asset,
                    currencyType = currencyType,
                    amountTextState = amount,
                    addressText = "refund-or-destination",
                    slippage = BigDecimal.ONE,
                    swapAssets = SwapAssetsData(data = listOf(zec, asset), zecAsset = zec),
                    isRequestingQuote = false,
                    selectedContact = null,
                    mode = mode,
                    isEphemeralAddressLocked = false
                ),
            onBack = {},
            onSwapInfoClick = {},
            onSwapAssetPickerClick = {},
            onSwapCurrencyTypeClick = { swappedTo = it },
            onSlippageClick = {},
            onRequestSwapQuoteClick = { _, _ -> swapClicked = true },
            onTryAgainClick = {},
            onAddressChange = {},
            onTextFieldChange = {},
            onQrCodeScannerClick = {},
            onAddressBookClick = {},
            onDeleteSelectedContactClick = {},
            onBalanceButtonClick = {},
            onChangeButtonClick = {},
            onAddressClick = {}
        )

    private fun field(state: SwapState): NumberTextFieldState = state.amountTextField.textField

    @Test
    fun swapping_into_zec_reads_the_origin_token_with_its_own_decimals() {
        val eighteen = field(state(Mode.SWAP_INTO_ZEC, eth))
        assertEquals(NumberFieldProbe.Reading(18, null, true, "ETH"), NumberFieldProbe.reading(eighteen))
        val typed = type(eighteen, en, "0", ".", *"1234567890123456789".map { it.toString() }.toTypedArray())
        assertEquals("0.123456789012345678", plain(typed.innerState.amount), "18 decimals are taken, the 19th is refused")

        val six = field(state(Mode.SWAP_INTO_ZEC, usdc))
        assertEquals(6, NumberFieldProbe.reading(six).maxDecimals)
        assertEquals("1.123456", plain(type(six, en, "1", ".", "1", "2", "3", "4", "5", "6", "7").innerState.amount))
        val pasted = paste(six, "0.1234567", en)
        assertNull(pasted.innerState.amount, "a paste past the token's decimals is not cut down silently")
        assertEquals("TooManyDecimals(6)", readError(pasted.innerState))
    }

    @Test
    fun swapping_from_zec_reads_zec_with_8_decimals_and_the_dollar_mode_with_2() {
        val zecField = field(state(Mode.SWAP_FROM_ZEC, eth))
        assertEquals(NumberFieldProbe.Reading(8, "ZEC", true, null), NumberFieldProbe.reading(zecField))
        assertEquals(
            "0.12345678",
            plain(type(zecField, en, "0", ".", "1", "2", "3", "4", "5", "6", "7", "8", "9").innerState.amount)
        )
        val dollars = field(state(Mode.SWAP_FROM_ZEC, eth, CurrencyType.FIAT))
        assertEquals(NumberFieldProbe.Reading(2, "USD", true, null), NumberFieldProbe.reading(dollars))
    }

    @Test
    fun a_spanish_paste_of_1_500_asks_and_the_swap_button_waits_for_the_answer() {
        val asked = paste(field(state(Mode.SWAP_INTO_ZEC, usdc)), "1.500", es)
        assertEquals(listOf("1.5", "1500"), ask(asked.innerState))
        val waiting = state(Mode.SWAP_INTO_ZEC, usdc, amount = asked.innerState)
        assertFalse(assertNotNull(waiting.primaryButton).isEnabled, "nothing is quoted while the question is open")
        val chosen = NumberFieldProbe.choose(asked, "1.5")
        val answered = state(Mode.SWAP_INTO_ZEC, usdc, amount = chosen)
        assertTrue(assertNotNull(answered.primaryButton).isEnabled)
        assertEquals("1.5", plain(chosen.amount))
    }

    @Test
    fun an_english_paste_of_1_000_asks_in_the_zec_field() {
        assertEquals(listOf("1", "1000"), ask(paste(field(state(Mode.SWAP_FROM_ZEC, eth)), "1,000", en).innerState))
    }

    @Test
    fun an_amount_typed_for_one_token_is_not_used_after_a_token_with_fewer_decimals_is_chosen() {
        // Typed 0.1234567 while ETH (18) was chosen, then USDC (6): the quote would cut it to 0.123456.
        val typed =
            NumberTextFieldInnerState(
                innerTextFieldState = InnerTextFieldState(value = stringRes("0.1234567"), selection = TextSelection.End),
                amount = BigDecimal("0.1234567"),
                lastValidAmount = BigDecimal("0.1234567")
            )
        val afterSwitch = state(Mode.SWAP_INTO_ZEC, usdc, amount = typed)
        assertNull(afterSwitch.amountTextField.textField.innerState.amount)
        assertFalse(assertNotNull(afterSwitch.primaryButton).isEnabled, "no quote for an amount the token can't hold")
        // Control: the same amount under ETH stays usable.
        assertTrue(assertNotNull(state(Mode.SWAP_INTO_ZEC, eth, amount = typed).primaryButton).isEnabled)
    }

    @Test
    fun the_dollar_switch_hands_over_a_figure_at_the_precision_of_the_mode_it_switches_to() {
        val zecAmount = NumberFieldProbe.fromAmount("1.23456789", 8)
        state(Mode.SWAP_FROM_ZEC, eth, amount = zecAmount).amountTextField.onSwapChange()
        val toDollars = assertNotNull(swappedTo)
        assertTrue(toDollars.stripTrailingZeros().scale() <= 2, "dollars get 2 decimals, got ${toDollars.toPlainString()}")

        val dollarAmount = NumberFieldProbe.fromAmount("10", 2)
        state(Mode.SWAP_FROM_ZEC, eth, CurrencyType.FIAT, dollarAmount).amountTextField.onSwapChange()
        val toZec = assertNotNull(swappedTo)
        assertTrue(toZec.stripTrailingZeros().scale() <= 8, "ZEC gets 8 decimals, got ${toZec.toPlainString()}")
    }

    @Test
    fun the_view_model_writes_plain_text_into_the_field() {
        // The dollar switch and the QR prefill write through the field's own writers, never a grouped number
        // ("1,234.56" can't be edited under the F1 keystroke rule).
        val vm = AmountReadingProbe.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/swap/SwapVM.kt")
        assertFalse("stringResByDynamicNumber" in vm, "SwapVM writes a formatted number into the amount field")
        assertFalse("NumberTextFieldInnerState.fromAmount(result.amount)" in vm, "the QR prefill ignores the precision")
        assertTrue("NumberTextFieldInnerState.fromComputed(" in vm, "the dollar switch must write at the new precision")
    }
}
