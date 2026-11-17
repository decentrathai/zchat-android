package co.electriccoin.zcash.ui.common.sendattempt

import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.model.ZcashNetwork
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * ZC-114 (fix-all 2.13.0, train T5b; high-items SPECS §1.5 R6), the tripwire: ChainTiming's height table describes the
 * SDK this build ships. SDK 3.0.0 knows no NU7 (ZcashNetwork carries only the Sapling and Orchard heights; its backend's
 * upgrade list ends at NU6.3), so it builds only pre-NU7 transactions and both NU7 rows are empty: no NU7 height is
 * ever estimated. The SDK bump that brings NU7 (ZC-272) must fill both rows from that SDK's own heights, confirm the
 * NU7 expiry delta and block spacing on testnet, and change this test with them; changing ZCASH_SDK_VERSION alone
 * fails here.
 *
 * At RED there is no ChainTiming.
 */
class ChainTimingFollowsTheShippedSdkTest {
    @Test
    fun `the NU7 rows are empty exactly while the shipped SDK is 3_0_0`() {
        val sdk = assertNotNull(Regex("""(?m)^ZCASH_SDK_VERSION=(\S+)""").find(read("gradle.properties"))?.groupValues?.get(1))
        val mainnet = ChainTimingApi.nu7Of(ChainTimingApi.row("MAINNET"))
        val testnet = ChainTimingApi.nu7Of(ChainTimingApi.row("TESTNET"))
        assertEquals(
            sdk == "3.0.0",
            mainnet == null && testnet == null,
            "ZCASH_SDK_VERSION is $sdk and ChainTiming's NU7 rows are mainnet=$mainnet testnet=$testnet: the SDK and the " +
                "height table moved apart. SDK 3.0.0 knows no NU7 (both rows null); an NU7-aware SDK needs both rows " +
                "filled from its own heights in the same change (ZC-272)",
        )
    }

    @Test
    fun `the block spacing before NU7 is the shipped SDK's`() {
        assertEquals(ZcashSdk.BLOCK_INTERVAL_MILLIS, ChainTimingApi.constant("PRE_NU7_BLOCK_SECONDS") * 1_000L)
    }

    @Test
    fun `the table follows the synchronizer's network`() {
        assertEquals(ChainTimingApi.row("MAINNET"), ChainTimingApi.forNetwork(ZcashNetwork.Mainnet))
        assertEquals(ChainTimingApi.row("TESTNET"), ChainTimingApi.forNetwork(ZcashNetwork.Testnet))
    }

    private fun read(path: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) dir = dir.parentFile
        val root = assertNotNull(dir, "repo root (gradle.properties + README.md) not found above the test working dir")
        return File(root, path).readText()
    }
}
