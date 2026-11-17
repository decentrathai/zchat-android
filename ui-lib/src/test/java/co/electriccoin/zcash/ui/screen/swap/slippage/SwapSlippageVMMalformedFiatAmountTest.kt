package co.electriccoin.zcash.ui.screen.swap.slippage

import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.usecase.GetSlippageUseCase
import co.electriccoin.zcash.ui.common.usecase.SetSlippageUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import kotlin.test.Test

/**
 * #198 L1 (regression pin) — the slippage sheet crashed when its `fiatAmount` navigation argument
 * was not a parseable decimal. `SwapSlippageVM` parses the arg in a FIELD INITIALIZER
 * (`args.fiatAmount?.toBigDecimalOrNull()`, SwapSlippageVM.kt:37), so with the buggy parse the
 * whole ViewModel construction — and therefore the whole screen — threw `NumberFormatException`.
 *
 * The fixed contract this test pins: a malformed `fiatAmount` is tolerated and treated exactly
 * like an ABSENT one (the info line falls back to the percent-only wording), and a well-formed
 * `fiatAmount` still feeds the fiat estimate (so an over-hardened "always null" parse also fails).
 *
 * MUTATION THAT REDDENS THIS TEST: revert SwapSlippageVM.kt:37 to
 * `args.fiatAmount?.toBigDecimal()` — every `newVm("abc")` / `newVm("")` call below then throws
 * `NumberFormatException` out of the VM constructor before any assertion runs.
 *
 * Real VM + the shared hand-written fakes (no mockk, no Robolectric); Main is an unconfined test
 * dispatcher because `stateIn(viewModelScope, ...)` launches on Dispatchers.Main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapSlippageVMMalformedFiatAmountTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm(fiatAmount: String?, mode: SwapMode = SwapMode.EXACT_INPUT): SwapSlippageVM {
        val swapRepository = Zc101FakeSwapRepository()
        val navigationRouter = Zc101RecordingNavigationRouter()
        return SwapSlippageVM(
            getSlippage = GetSlippageUseCase(swapRepository),
            args = SwapSlippageArgs(fiatAmount = fiatAmount, mode = mode),
            setSlippage = SetSlippageUseCase(swapRepository, navigationRouter),
            navigationRouter = navigationRouter,
        )
    }

    @Test
    fun `malformed fiatAmount must not crash the slippage sheet and is treated as absent`() {
        // The reference render: the nav arg was genuinely absent.
        val absentFiatInfo = newVm(fiatAmount = null).state.value.info
        assertNotNull("fixture: the absent-fiat render must still show an info line", absentFiatInfo)

        for (malformed in listOf("abc", "", " ", "12,34.56x")) {
            // Mutation `toBigDecimal()` (the #198 L1 bug) throws NumberFormatException HERE,
            // inside the VM constructor — the screen never even renders.
            val state = newVm(fiatAmount = malformed).state.value

            assertEquals(
                "a malformed fiatAmount (\"$malformed\") must degrade to the SAME info line as an " +
                    "absent fiatAmount — percent-only wording, no fiat estimate, no crash",
                absentFiatInfo,
                state.info,
            )
        }
    }

    @Test
    fun `well-formed fiatAmount still drives the fiat slippage estimate`() {
        // Guards the other direction: a "fix" that hard-codes the parse result to null (or drops
        // the arg entirely) would pass the malformed-input test yet lose the fiat estimate. The
        // valid-fiat info line embeds the computed fiat amount, so it must DIFFER from the
        // percent-only absent-fiat line.
        val absentFiatInfo = newVm(fiatAmount = null).state.value.info
        val validFiatInfo = newVm(fiatAmount = "100").state.value.info

        assertNotNull("a valid fiatAmount must still produce an info line", validFiatInfo)
        assertNotEquals(
            "a parseable fiatAmount (\"100\") must feed the fiat slippage estimate — its info " +
                "line cannot collapse to the percent-only absent-fiat wording",
            absentFiatInfo,
            validFiatInfo,
        )
    }
}
