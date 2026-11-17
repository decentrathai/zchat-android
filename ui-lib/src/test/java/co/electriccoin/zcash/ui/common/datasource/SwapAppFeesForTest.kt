package co.electriccoin.zcash.ui.common.datasource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ZC-1268: a build without a swap fee account sends no app fee rather than a wrong recipient. */
class SwapAppFeesForTest {
    @Test
    fun blankFeeAccountSendsNoAppFee() {
        assertTrue(appFeesFor("").isEmpty())
        assertTrue(appFeesFor("   ").isEmpty())
    }

    @Test
    fun configuredFeeAccountGetsFiftyBps() {
        val fees = appFeesFor("zchat-test-account")
        assertEquals(1, fees.size)
        assertEquals("zchat-test-account", fees.single().recipient)
        assertEquals(50, fees.single().fee)
    }
}
