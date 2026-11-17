package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.screen.ai.AiTopupCustomAmountTest.Companion.accept
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T1 fixup, X-F1-1: the AI top-up's dollar field refuses a pasted ZEC amount instead of billing it as dollars.
 *
 * At d27d46dba (F1) the shared reader drops "ZEC" and "ⓩ" in every field, so a pasted "5 ZEC" (about $250 at $50 a ZEC)
 * offers a $5 top-up. Wanted: the field reads with the dollar unit, so a ZEC sign is refused with a reason and no tier
 * is offered. A dollar sign still reads, as before.
 */
class AiTopupOtherUnitTest {
    private val empty get() = AmountReadingProbe.emptyField()

    @Test
    fun `a pasted ZEC amount is refused and offers no top-up`() {
        for (paste in listOf("5 ZEC", "ZEC 5", "ⓩ5")) {
            val field = accept(empty, paste)
            assertEquals("OtherUnit(ZEC)", field.error, "$paste in the dollar field: $field")
            assertEquals("", field.settled)
            assertNull(customUsdTier(field.settled), "$paste offers a top-up")
        }
    }

    @Test
    fun `a pasted dollar amount still reads (control)`() {
        for (paste in listOf("$12", "US$12", "12 USD")) {
            assertEquals("12", accept(empty, paste).settled, paste)
        }
    }
}
