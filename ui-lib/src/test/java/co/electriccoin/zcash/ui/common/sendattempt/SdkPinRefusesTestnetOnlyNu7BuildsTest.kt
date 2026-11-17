package co.electriccoin.zcash.ui.common.sendattempt

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * ZC-1560 (Fable review of ZC-1554's fix, finding 2): the settings guard accepted any "3.0.0-nu7-zchat.N". Builds .1
 * and .2 (October, pre-release crates) know only the testnet NU7 height, sit in the same local Maven folder, and an
 * APK built on them signs NU6.3 transactions on mainnet while ChainTiming says NU7. The guard names the first build
 * with the mainnet height (.3) and refuses anything below it.
 *
 * At RED the guard only checks for the "-nu7-zchat." substring.
 */
class SdkPinRefusesTestnetOnlyNu7BuildsTest {
    @Test
    fun `the settings guard refuses our NU7 builds below the first with the mainnet height`() {
        val settings = File(ChatResourceText.repoRoot, "settings.gradle.kts").readText()
        assertTrue(
            Regex("""val\s+zchatSdkMinBuild\s*=\s*3\b""").containsMatchIn(settings),
            "settings.gradle.kts names no minimum NU7 build (3: the first with mainnet NU7 at 3,543,405)",
        )
        assertTrue(
            Regex("""-nu7-zchat\\\.\(\\d\+\)""").containsMatchIn(settings) &&
                "zchatSdkMinBuild" in
                settings.substringAfter("val zchatSdkMinBuild"),
            "the guard must parse the build number and compare it with zchatSdkMinBuild",
        )
    }
}
