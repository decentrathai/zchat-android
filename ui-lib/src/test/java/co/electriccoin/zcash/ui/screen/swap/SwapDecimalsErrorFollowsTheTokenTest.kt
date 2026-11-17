package co.electriccoin.zcash.ui.screen.swap

import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.ask
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.paste
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.plain
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.readError
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
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
 * T3b-2 item 3 (fix-all 2.13.0, train T3b; train T3 phone finding 6, F12 check 4, both phones): in Swap into ZEC, a
 * pasted "0.1234567" under USDC (6 decimals) correctly shows "This amount can have at most 6 decimals". After
 * switching the token to ETH (18 decimals) the same error and $0.00 stay, although ETH holds 7 decimals.
 *
 * Cause at f4b0853c7: the paste is read with the precision of the token chosen at that moment, and the field keeps
 * only the error (TooManyDecimals(6)) and the pasted text. On a token change the mapper calls
 * NumberTextFieldInnerState.limitedTo(new precision), which only ever takes an amount away: an error made under a
 * smaller precision is never read again, so it stays, with the old token's number in it.
 *
 * What it must be: the field says the truth for the token now chosen. A paste refused only for its decimals is read
 * again under the new precision: under ETH "0.1234567" is 0.1234567 (usable, the quote button enabled); under a
 * 4-decimal token the error names 4; back under USDC it names 6 again. Safe either way: an amount the token cannot
 * hold is never used (F12 H11).
 */
class SwapDecimalsErrorFollowsTheTokenTest {
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
    private val four = token("FOUR", 4)

    private fun state(
        asset: SwapAsset,
        amount: NumberTextFieldInnerState = NumberTextFieldInnerState()
    ): SwapState =
        ExactInputVMMapper().createState(
            internalState =
                InternalStateImpl(
                    account = null,
                    swapAsset = asset,
                    currencyType = CurrencyType.TOKEN,
                    amountTextState = amount,
                    addressText = "refund-or-destination",
                    slippage = BigDecimal.ONE,
                    swapAssets = SwapAssetsData(data = listOf(zec, asset), zecAsset = zec),
                    isRequestingQuote = false,
                    selectedContact = null,
                    mode = Mode.SWAP_INTO_ZEC,
                    isEphemeralAddressLocked = false
                ),
            onBack = {},
            onSwapInfoClick = {},
            onSwapAssetPickerClick = {},
            onSwapCurrencyTypeClick = {},
            onSlippageClick = {},
            onRequestSwapQuoteClick = { _, _ -> },
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

    private fun inner(state: SwapState): NumberTextFieldInnerState = state.amountTextField.textField.innerState

    /** The phone case: the paste under USDC, as the Swap VM stores it (the field's edit under USDC). */
    private fun pastedUnderUsdc(
        text: String = "0.1234567",
        locale: Locale = en
    ): NumberTextFieldInnerState {
        val pasted = paste(state(usdc).amountTextField.textField, text, locale).innerState
        assertEquals("TooManyDecimals(6)", readError(pasted), "setup: USDC refuses 7 decimals")
        return pasted
    }

    @Test
    fun a_paste_refused_for_usdc_decimals_is_read_again_when_eth_is_chosen() {
        val underEth = state(eth, amount = pastedUnderUsdc())

        assertNull(readError(inner(underEth)), "the USDC decimals error stays after switching to ETH")
        assertEquals("0.1234567", plain(inner(underEth).amount), "ETH holds 7 decimals: the amount is usable")
        assertTrue(assertNotNull(underEth.primaryButton).isEnabled, "the quote button is enabled under ETH")
    }

    @Test
    fun the_error_names_the_decimals_of_the_token_now_chosen() {
        val pasted = pastedUnderUsdc()
        assertEquals("TooManyDecimals(4)", readError(inner(state(four, amount = pasted))), "a 4-decimal token says 4")
        assertEquals("TooManyDecimals(6)", readError(inner(state(usdc, amount = pasted))), "back under USDC it says 6")
        assertFalse(assertNotNull(state(four, amount = pasted).primaryButton).isEnabled, "nothing is quoted")
    }

    @Test
    fun the_number_field_reads_a_refused_paste_again_for_a_larger_precision() {
        // The model under Swap and Pay (both call limitedTo with the precision of the token now chosen).
        val pasted = pastedUnderUsdc()
        val wider = pasted.limitedTo(18)
        assertNull(readError(wider))
        assertEquals("0.1234567", plain(wider.amount))
        assertFalse(wider.isQuestionOpen)
    }

    @Test
    fun a_spanish_paste_refused_for_its_decimals_is_read_with_the_spanish_mark_under_a_larger_precision() {
        // "1,5" pasted on a Spanish phone into a 0-decimal field is refused for its decimals. Under 2 decimals it is
        // read again as the phone read it (',' is the decimal mark), so it is 1.5 and asks nothing.
        val zero = NumberFieldProbe.field(NumberFieldProbe.newReading(0, null, ask = true, ticker = "X"))
        val pasted = paste(zero, "1,5", es).innerState
        assertEquals("TooManyDecimals(0)", readError(pasted), "setup: 1,5 does not fit 0 decimals")
        val two = pasted.limitedTo(2)
        assertNull(readError(two))
        assertEquals("1.5", plain(two.amount))
        assertNull(ask(two))
    }

    /** Control, green before and after: an amount typed under ETH is still not used under USDC (F12 H11). */
    @Test
    fun control_an_amount_the_new_token_cannot_hold_is_still_not_used() {
        val typed = NumberFieldProbe.fromAmount("0.1234567", 18)
        val underUsdc = state(usdc, amount = typed)
        assertNull(inner(underUsdc).amount)
        assertEquals("TooManyDecimals(6)", readError(inner(underUsdc)))
        assertFalse(assertNotNull(underUsdc.primaryButton).isEnabled)
    }
}
