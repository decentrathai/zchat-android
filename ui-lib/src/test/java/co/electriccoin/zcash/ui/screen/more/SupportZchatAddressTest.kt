package co.electriccoin.zcash.ui.screen.more

import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins where the "Support ZCHAT" entry in More sends donations.
 *
 * The owner chose the donation address published on zsend.xyz (2026-09-10). It is proven to receive
 * (marker payments of 7,777, 2,222 and 3,333 zatoshi arrived) and has collected the platform fee since
 * 2026-09-09, so the two must stay the same address. If either one changes, this test forces the change
 * to be a deliberate decision rather than a silent redirect of users' donations.
 */
class SupportZchatAddressTest {
    @Test
    fun donations_go_to_a_well_formed_mainnet_unified_address() {
        val address = BuildConfig.ZCHAT_DONATION_ADDRESS

        assertTrue(address.startsWith("u1"), "the mainnet build must donate to a mainnet unified address")
        assertTrue(ZMSGConstants.isBareAddressShaped(address), "the donation address must be a bare address")
    }

    @Test
    fun donations_and_the_platform_fee_share_the_zsend_xyz_address() {
        assertEquals(BuildConfig.ZCHAT_PLATFORM_FEE_ADDRESS, BuildConfig.ZCHAT_DONATION_ADDRESS)
    }
}
