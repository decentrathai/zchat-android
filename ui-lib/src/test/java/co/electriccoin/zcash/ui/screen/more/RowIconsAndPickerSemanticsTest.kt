package co.electriccoin.zcash.ui.screen.more

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 batch B7, unit U8 (FIX_LIST rows Q2-14, Q2-92, Q2-100 icons, Q2-05 remainder; owner W-136/W-137: fixed in
 * 2.13.0). Source and resource checks; how TalkBack reads the result is checked on the phone in the QA repeat.
 *
 * - Q2-14 (ZC-1617): More shows the same (i) for Language, Notifications, Security and About and the same gear for
 *   Theme, Advanced and Change Identity; Advanced draws the red upload arrow on every Destroy row; About's
 *   "Source Code" uses the (i). Each row gets its own picture; the (i) stays only on About.
 * - Q2-100 (ZC-1600): About's privacy rows use emoji (🚫 for "Optional AI Tab") beside vector icons.
 * - Q2-92 (ZC-1665): the pickers (Tor, the mode sheet, New Chat's mode chips, the amount dialog) expose no selected
 *   state; the amount dialog marks the choice with a "✓" text. Each option is selectable with Role.RadioButton.
 * - Q2-05 remainder (ZC-1599): the Swap amount field's currency toggle is a tappable picture with no label.
 * - Texts the touched rows still had as English literals ("Export Viewing Keys", "Debug menu",
 *   "Check for Updates") become resources, EN + ES (Spanish lines are drafts, app-texts rule 9).
 */
class RowIconsAndPickerSemanticsTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private fun mainSource(path: String): String =
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/$path")

    private fun count(
        haystack: String,
        needle: String
    ): Int = haystack.windowed(needle.length).count { it == needle }

    private fun assertBoth(
        key: String,
        english: String,
        spanish: String
    ) {
        assertEquals(english, assertNotNull(en[key], "values: no $key"), "values: $key")
        assertEquals(spanish, assertNotNull(es[key], "values-es: no $key"), "values-es: $key")
    }

    /** A day and a night picture exist for [name] in some res/ui screen folder. */
    private fun assertDayAndNightDrawable(name: String) {
        val root = File(ChatResourceText.repoRoot, "ui-lib/src/main/res/ui")
        val screens = root.listFiles().orEmpty()
        assertTrue(
            screens.any { File(it, "drawable/$name.xml").isFile },
            "no drawable/$name.xml under res/ui"
        )
        assertTrue(
            screens.any { File(it, "drawable-night/$name.xml").isFile },
            "no drawable-night/$name.xml under res/ui"
        )
    }

    // ---- Q2-14 ----

    @Test
    fun q2_14_more_rows_each_have_their_own_picture() {
        val vm = mainSource("screen/more/MoreVM.kt")
        assertEquals(1, count(vm, "R.drawable.ic_settings_info"), "More: the (i) must stay only on About")
        assertEquals(1, count(vm, "R.drawable.ic_advanced_settings)"), "More: the gear must stay only on Advanced")
        for (icon in listOf("ic_more_theme", "ic_more_notifications", "ic_more_security", "ic_more_identity")) {
            assertTrue("R.drawable.$icon" in vm, "More: no row uses $icon")
            assertDayAndNightDrawable(icon)
        }
        val language = mainSource("screen/more/MoreLanguageRow.kt")
        assertFalse("ic_settings_info" in language, "More: the Language row still shows the (i)")
        assertTrue("R.drawable.ic_more_language" in language, "More: the Language row has no globe")
        assertDayAndNightDrawable("ic_more_language")
    }

    @Test
    fun q2_14_advanced_destroy_rows_show_a_delete_picture_not_an_upload_arrow() {
        val vm = mainSource("screen/advancedsettings/AdvancedSettingsVM.kt")
        assertFalse("ic_reset_zashi_warning" in vm, "Advanced: a Destroy row still shows the red upload arrow")
        assertEquals(
            4,
            count(vm, "R.drawable.ic_advanced_settings_destroy"),
            "Advanced: the four Destroy rows share the delete picture"
        )
        assertDayAndNightDrawable("ic_advanced_settings_destroy")
        assertFalse("ic_settings_info" in vm, "Advanced: the (i) belongs to About only")
    }

    @Test
    fun q2_14_about_source_code_row_shows_code_not_info() {
        val src = mainSource("screen/about/view/AboutView.kt")
        assertFalse("ic_settings_info" in src, "About: Source Code still shows the (i)")
        assertTrue("R.drawable.ic_about_source_code" in src, "About: Source Code has no code picture")
        assertDayAndNightDrawable("ic_about_source_code")
    }

    @Test
    fun touched_rows_have_no_english_literals() {
        val advanced = mainSource("screen/advancedsettings/AdvancedSettingsVM.kt")
        assertFalse("stringRes(\"Export Viewing Keys\")" in advanced, "Advanced: \"Export Viewing Keys\" is a literal")
        assertFalse("stringRes(\"Debug menu\")" in advanced, "Advanced: \"Debug menu\" is a literal")
        assertTrue("R.string.advanced_settings_export_viewing_keys" in advanced)
        assertTrue("R.string.advanced_settings_debug_menu" in advanced)
        val about = mainSource("screen/about/view/AboutView.kt")
        assertFalse("stringRes(\"Check for Updates\")" in about, "About: \"Check for Updates\" is a literal")
        assertTrue("R.string.about_check_for_updates" in about)
        assertBoth("advanced_settings_export_viewing_keys", "Export Viewing Keys", "Exportar claves de visualización")
        assertBoth("advanced_settings_debug_menu", "Debug menu", "Menú de depuración")
        assertBoth("about_check_for_updates", "Check for Updates", "Buscar actualizaciones")
    }

    // ---- Q2-100 ----

    @Test
    fun q2_100_about_privacy_rows_use_vector_icons_not_emoji() {
        val src = mainSource("screen/about/view/AboutView.kt")
        for (emoji in listOf("🔒", "📱", "🛡️", "🚫", "👁️")) {
            assertFalse("\"$emoji\"" in src, "About: a privacy row still shows the emoji $emoji")
        }
        val start = src.indexOf("private fun PrivacyFeatureItem(")
        assertTrue(start >= 0, "AboutView has no PrivacyFeatureItem")
        val body = src.substring(start, src.indexOf("\n}\n", start).let { if (it < 0) src.length else it })
        assertFalse("emoji" in body, "PrivacyFeatureItem still takes an emoji")
        assertTrue("icon: ImageVector" in body, "PrivacyFeatureItem takes no vector icon")
        assertTrue("Icon(" in body, "PrivacyFeatureItem draws no Icon")
    }

    // ---- Q2-92 ----

    @Test
    fun q2_92_mode_sheet_rows_are_radio_buttons_with_one_focus_target() {
        val src = mainSource("screen/chat/view/ConversationModeUi.kt")
        assertTrue(".selectableGroup()" in src, "mode sheet: the options are not one selectable group")
        assertTrue(
            Regex("""\.selectable\(\s*selected = selected == mode,""").containsMatchIn(src),
            "mode sheet: a row is not selectable with its selected state"
        )
        assertTrue("role = Role.RadioButton" in src, "mode sheet: rows have no RadioButton role")
        assertFalse("Modifier.clickable { selected = mode }" in src, "mode sheet: a row is still a plain clickable")
        assertTrue(
            Regex("""RadioButton\(\s*selected = selected == mode,\s*onClick = null,""").containsMatchIn(src),
            "mode sheet: the radio dot is a second focus target (onClick must be null)"
        )
    }

    @Test
    fun q2_92_new_chat_mode_chips_and_amount_dialog_expose_selected_state() {
        val src = mainSource("screen/chat/view/ZchatComposeView.kt")
        assertTrue(
            Regex("""\.selectable\(\s*selected = isSelected,""").containsMatchIn(src),
            "New Chat: a mode chip is not selectable with its selected state"
        )
        assertTrue(
            Regex("""\.selectable\(\s*selected = selectedAmount == amount,""").containsMatchIn(src),
            "amount dialog: an option is not selectable with its selected state"
        )
        assertTrue(count(src, "role = Role.RadioButton") >= 2, "New Chat: chips and amounts need the RadioButton role")
        assertTrue(count(src, ".selectableGroup()") >= 2, "New Chat: chips and amounts are not selectable groups")
        assertFalse("\"\\u2713\"" in src, "amount dialog: the choice is still marked with a ✓ text")
        assertFalse(".clickable { onAmountSelect(amount) }" in src, "amount dialog: an option is still a plain clickable")
    }

    @Test
    fun q2_92_tor_and_exchange_rate_options_are_radio_buttons() {
        val src = mainSource("screen/exchangerate/settings/ExchangeRateSettingsView.kt")
        val start = src.indexOf("fun Option(")
        assertTrue(start >= 0, "ExchangeRateSettingsView has no Option")
        val body = src.substring(start, src.indexOf("\n}\n", start).let { if (it < 0) src.length else it })
        assertTrue("selectable(" in body, "Option (Tor, exchange rate) is not selectable")
        assertTrue("selected = isChecked" in body, "Option does not expose isChecked as its selected state")
        assertTrue("role = Role.RadioButton" in body, "Option has no RadioButton role")
        assertFalse("clickable(" in body, "Option is still a plain clickable")
        val tor = mainSource("screen/tor/settings/TorSettingsView.kt")
        assertTrue(".selectableGroup()" in tor, "Tor: Enable/Disable are not one selectable group")
    }

    // ---- Q2-05 remainder ----

    @Test
    fun q2_05_swap_currency_toggle_has_a_label() {
        val src = mainSource("screen/swap/ui/SwapAmountTextField.kt")
        val at = src.indexOf("R.drawable.ic_swap_recipient")
        assertTrue(at >= 0, "SwapAmountTextField has no currency toggle")
        val around = src.substring((at - 900).coerceAtLeast(0), (at + 300).coerceAtMost(src.length))
        assertTrue(
            "R.string.swap_amount_currency_toggle_content_description" in around,
            "Swap: the currency toggle has no label"
        )
        assertTrue("role = Role.Button" in around, "Swap: the currency toggle has no Button role")
        assertBoth(
            "swap_amount_currency_toggle_content_description",
            "Switch amount currency",
            "Cambiar la moneda del monto"
        )
    }
}
