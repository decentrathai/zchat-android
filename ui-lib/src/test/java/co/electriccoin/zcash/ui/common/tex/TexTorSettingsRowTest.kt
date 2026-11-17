package co.electriccoin.zcash.ui.common.tex

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * F3 (PLAN_V2 §2 F3: the notice says Tor "can be turned off in Settings"): the Advanced Settings Tor row is listed
 * while Tor is on, which covers every phone where ZCHAT turned it on, and while a TEX check needs the Tor the user
 * turned off again (its text sends them there). Without it the notice would point at a menu item that does not exist.
 * ZC-416 (train T5b): the row is listed always, so both F3 cases are a subset and stay covered by "the row is listed
 * with no condition" (TorRowStaysListedWhenTorIsOffTest checks the rest).
 * Fails at 15e2b1c39: the Tor row is commented out.
 */
class TexTorSettingsRowTest {
    @Test
    fun the_tor_row_is_listed_while_tor_is_on_or_a_tex_check_needs_it() {
        val vm =
            generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .first { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
                .resolve("src/main/java/co/electriccoin/zcash/ui/screen/advancedsettings/AdvancedSettingsVM.kt")
                .readText()
        val code =
            vm
                .lines()
                .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
                .joinToString("\n")
        val row = code.substringAfter("R.string.advanced_settings_privacy", missingDelimiterValue = "")
        assertTrue(row.isNotEmpty(), "Advanced Settings lists no Tor row")
        val window = row.substringBefore("ListItemState(")
        assertTrue("takeIf" !in window, "the Tor row is not listed always (F3's cases and ZC-416's Tor off)")
    }
}
