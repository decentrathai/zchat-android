package co.electriccoin.zcash.ui.screen.swap

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1267: before the quote the swap screen shows 0.00156 ZEC for 2 USDC. That is the mid-market figure from the two USD
 * prices (`getDestinationAssetAmount`); the real quote came about $0.42 lower. The screen says so before the quote: the
 * `infoFooter` of the state is the text `swap_pre_quote_estimate_note` (EN, ES), which SwapView already draws. The quote
 * request does not change.
 *
 * The text names no figure: the loss depends on the provider, the route and the size.
 */
class SwapPreQuoteIsAnEstimateTest {
    private val chain = SwapBlockchain(chainTicker = "eth", chainName = stringRes("Ethereum"), chainIcon = imageRes(0))

    private val zec =
        ZecSwapAsset(
            tokenTicker = "ZEC",
            tokenName = stringRes("Zcash"),
            tokenIcon = imageRes(0),
            blockchain = chain,
            usdPrice = BigDecimal("1282"),
            assetId = "zec",
            decimals = 8
        )

    private val usdc =
        DynamicSwapAsset(
            tokenTicker = "USDC",
            tokenName = stringRes("USDC"),
            tokenIcon = imageRes(0),
            usdPrice = BigDecimal.ONE,
            assetId = "usdc",
            decimals = 6,
            blockchain = chain
        )

    private fun state(mode: Mode): SwapState =
        ExactInputVMMapper().createState(
            internalState =
                InternalStateImpl(
                    account = null,
                    swapAsset = usdc,
                    currencyType = CurrencyType.TOKEN,
                    amountTextState = NumberTextFieldInnerState(amount = BigDecimal("2")),
                    addressText = "refund-or-destination",
                    slippage = BigDecimal.ONE,
                    swapAssets = SwapAssetsData(data = listOf(zec, usdc), zecAsset = zec),
                    isRequestingQuote = false,
                    selectedContact = null,
                    mode = mode,
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

    @Test
    fun `before the quote the screen says the figure is an estimate, in both directions`() {
        val note = runCatching { R.string::class.java.getField("swap_pre_quote_estimate_note").getInt(null) }.getOrNull()
        for (mode in Mode.entries) {
            val footer = assertNotNull(state(mode).infoFooter, "$mode shows the mid-market figure with no word about it")
            assertEquals(note, (footer as? StringResource.ByResource)?.resource, "$mode")
        }
    }

    @Test
    fun `the note says estimate, before provider costs, and that small swaps can lose much more, in both languages`() {
        val english = assertNotNull(ChatResourceText.strings("values")["swap_pre_quote_estimate_note"], "no English note")
        val spanish = assertNotNull(ChatResourceText.strings("values-es")["swap_pre_quote_estimate_note"], "no Spanish note")
        assertNotEquals(english, spanish)
        val en = english.lowercase()
        for (word in listOf("estimate", "before provider costs", "small swaps", "much more")) {
            assertTrue(word in en, "the note lacks \"$word\": $english")
        }
        val es = spanish.lowercase()
        for (word in listOf("estimación", "proveedor", "pequeñ")) {
            assertTrue(word in es, "the Spanish note lacks \"$word\": $spanish")
        }
        for (text in listOf(en, es)) {
            assertFalse(text.any { it.isDigit() }, "the note names a figure the code does not know: $text")
            assertFalse("guarante" in text || "garantiz" in text, "the note promises: $text")
        }
    }
}
