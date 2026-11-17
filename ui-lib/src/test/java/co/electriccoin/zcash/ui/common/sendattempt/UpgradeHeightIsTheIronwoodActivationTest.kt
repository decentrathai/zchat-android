package co.electriccoin.zcash.ui.common.sendattempt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZC-599 (FIRST-CONTACT-SPEC revision 4a, 5.4 item 2, leg 3) — the upgrade height is a field of the network table
 * (NetworkUpgradeHeights): mainnet 3,428,143, the height WalletAccount.kt names for NU6.3; none on testnet, where leg 3
 * is off. The field is reached by name, so this test compiles before it exists.
 */
class UpgradeHeightIsTheIronwoodActivationTest {
    private fun upgradeOf(row: NetworkUpgradeHeights): Any? {
        val getter = NetworkUpgradeHeights::class.java.methods.firstOrNull { it.name == "getUpgrade" }
        assertNotNull("NetworkUpgradeHeights has no upgrade height (the answers mined at or below it are read as before)", getter)
        return getter!!.invoke(row)
    }

    @Test
    fun `mainnet's upgrade height is the NU6_3 height WalletAccount names, testnet has none`() {
        val source =
            listOf("src/main/java", "ui-lib/src/main/java")
                .map { File(it, "co/electriccoin/zcash/ui/common/model/WalletAccount.kt") }
                .first { it.exists() }
                .readText()
        assertTrue("WalletAccount.kt no longer names the NU6.3 height", source.contains("NU6.3 (mainnet height 3,428,143"))
        assertEquals(3_428_143L, upgradeOf(ChainTiming.MAINNET))
        assertNull(upgradeOf(ChainTiming.TESTNET))
    }
}
