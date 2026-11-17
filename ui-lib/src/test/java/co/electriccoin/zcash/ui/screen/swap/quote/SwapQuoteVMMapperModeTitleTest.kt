package co.electriccoin.zcash.ui.screen.swap.quote

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapQuote
import co.electriccoin.zcash.ui.design.util.stringRes
import org.junit.Assert.assertEquals
import kotlin.test.Test
import kotlin.time.Clock

/**
 * #198 L3 (regression pin) — the quote sheet's title builder crashed on a swap mode it did not
 * special-case. `SwapQuoteVMMapper.createState` (SwapQuoteVMMapper.kt:34-41) special-cases only a
 * ZEC destination and EXACT_OUTPUT; every other mode must fall through to the GENERIC swap title
 * (`R.string.swap_quote_title`). The historical bug enumerated the modes exhaustively and threw
 * for the one it didn't know (FLEX_INPUT), killing the whole quote sheet.
 *
 * MUTATION THAT REDDENS THIS TEST: replace the `else -> stringRes(R.string.swap_quote_title)`
 * fallback with an exhaustive per-mode `when` that `error(...)`s (or has no branch) for
 * FLEX_INPUT — `renderedTitle(FLEX_INPUT)` then throws (IllegalStateException /
 * NoWhenBranchMatchedException) instead of returning the generic title.
 *
 * The mapper is a pure internal class (same-module test => friend visibility), exercised on the
 * JVM with the shared all-synthetic `Zc101FakeSwapQuote` (non-ZEC destination, so the title never
 * takes the ZEC-review special case).
 */
class SwapQuoteVMMapperModeTitleTest {

    private fun renderedTitle(mode: SwapMode) =
        SwapQuoteVMMapper()
            .createState(
                state =
                    SwapQuoteInternalState(
                        // No proposal on this render: the mapper must still build a full state.
                        proposal = null,
                        quote = Zc101FakeSwapQuote(timestamp = Clock.System.now(), mode = mode),
                    ),
                onBack = {},
                onSubmitQuoteClick = {},
                onNavigateToOnRampSwap = {},
            ).title

    @Test
    fun `a mode without a dedicated title falls back to the generic swap title instead of crashing`() {
        // FLEX_INPUT is exactly the mode the buggy exhaustive `when` didn't know: with the
        // fallback reverted, this call THROWS instead of returning the generic title.
        assertEquals(
            "an unhandled swap mode must render the generic swap title — never crash the sheet",
            stringRes(R.string.swap_quote_title),
            renderedTitle(SwapMode.FLEX_INPUT),
        )
    }

    @Test
    fun `exact input renders the generic swap title`() {
        assertEquals(stringRes(R.string.swap_quote_title), renderedTitle(SwapMode.EXACT_INPUT))
    }

    @Test
    fun `exact output keeps its dedicated pay title - the fallback must not swallow it`() {
        // Guards the fix's other edge: making the fallback TOO broad (dropping the EXACT_OUTPUT
        // special case into `else`) would silently retitle the Pay flow.
        assertEquals(stringRes(R.string.pay_quote_title), renderedTitle(SwapMode.EXACT_OUTPUT))
    }
}
