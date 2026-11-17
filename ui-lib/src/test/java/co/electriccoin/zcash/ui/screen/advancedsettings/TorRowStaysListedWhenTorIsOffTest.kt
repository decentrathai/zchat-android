package co.electriccoin.zcash.ui.screen.advancedsettings

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-416 (fix-all 2.13.0, train T5b): once "Beta: Tor Protection" is turned off, its row must stay in Advanced
 * Settings, so Tor can be turned on again from the app. At 88b3eb9c2 the row is `.takeIf { showTorRow }`, and
 * showTorRow (F3) is "Tor is on, or a TEX check needs the Tor the user turned off": with Tor off and no such check
 * the row is gone, and nothing else navigates to TorSettingsArgs or TorOptInArgs (Seeker debug, 30 Sep 23:19:55).
 * Wanted (orchestrator decision 1 Oct under the owner's 25 Sep "fix every issue before release" rule): the row is
 * listed always. F3's two cases (Tor on; a TEX check that needs Tor) are a subset of "always".
 * Controls that pass today: the row opens TorSettingsArgs; Tor settings enable Save for off -> on and save the
 * choice through the opt-in use case; the title exists in English and Spanish.
 */
class TorRowStaysListedWhenTorIsOffTest {
    private val vm = SourceScan.stripComments(ChatResourceText.source("$UI/screen/advancedsettings/AdvancedSettingsVM.kt"))
    private val torView = SourceScan.stripComments(ChatResourceText.source("$UI/screen/tor/settings/TorSettingsView.kt"))
    private val torVm = SourceScan.stripComments(ChatResourceText.source("$UI/screen/tor/settings/TorSettingsVM.kt"))

    /** The Tor row: from its title to the next row of the list (or the end of the list). */
    private fun torRow(): String {
        val from = vm.indexOf("fun createState(")
        assertTrue(from >= 0, "AdvancedSettingsVM has no createState")
        val to = vm.indexOf("deleteButton =", from)
        val builder = vm.substring(from, if (to < 0) vm.length else to)
        val start = builder.indexOf("R.string.advanced_settings_privacy")
        assertTrue(start >= 0, "createState lists no row titled advanced_settings_privacy")
        val next = builder.indexOf("ListItemState(", start)
        return builder.substring(start, if (next < 0) builder.length else next)
    }

    @Test
    fun the_tor_row_is_listed_with_no_condition() {
        val row = torRow()
        for (condition in listOf("takeIf", "takeUnless", "if (", "?.let")) {
            assertFalse(condition in row, "the Tor row is listed only on a condition ($condition): $row")
        }
    }

    @Test
    fun whether_tor_is_on_decides_nothing_in_advanced_settings() {
        assertFalse("showTorRow" in vm, "AdvancedSettingsVM still keeps showTorRow")
    }

    @Test
    fun control_the_tor_row_opens_tor_settings() {
        assertTrue("onClick = ::onPrivacyClick" in torRow(), "the Tor row does not call onPrivacyClick")
        assertTrue(
            Regex("""fun onPrivacyClick\(\)\s*=\s*navigationRouter\.forward\(TorSettingsArgs\)""").containsMatchIn(vm),
            "onPrivacyClick does not open TorSettingsArgs",
        )
    }

    @Test
    fun control_tor_settings_save_is_enabled_for_off_to_on() {
        val disabled =
            Regex("""derivedStateOf\s*\{\s*(.*?)\s*}""", RegexOption.DOT_MATCHES_ALL)
                .find(torView)
                ?.groupValues
                ?.get(1)
                ?.replace(Regex("""\s+"""), "")
        assertEquals("(state.isOptedIn&&isOptInSelected)||(!state.isOptedIn&&!isOptInSelected)", disabled)
        assertTrue("onClick = { isOptInSelected = true }" in torView, "no option selects Enable")
        assertTrue("onClick = { state.onSaveClick(isOptInSelected) }" in torView, "Save does not hand on the choice")
        assertTrue("enabled = !isButtonDisabled" in torView, "Save is not enabled by the choice")
        assertTrue("optInExchangeRateAndTor(optInt)" in torVm, "Tor settings do not save through the opt-in use case")
    }

    @Test
    fun control_the_tor_row_title_exists_in_english_and_spanish() {
        assertEquals("Beta: Tor Protection", assertNotNull(ChatResourceText.strings("values")["advanced_settings_privacy"]))
        assertEquals("Beta: Protección con Tor", assertNotNull(ChatResourceText.strings("values-es")["advanced_settings_privacy"]))
    }

    private companion object {
        const val UI = "ui-lib/src/main/java/co/electriccoin/zcash/ui"
    }
}
