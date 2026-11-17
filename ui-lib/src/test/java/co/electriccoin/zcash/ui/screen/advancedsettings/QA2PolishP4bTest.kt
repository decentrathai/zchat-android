package co.electriccoin.zcash.ui.screen.advancedsettings

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 polish lane p4b: the follow-up of the Fable review of p4 (fix 1094ef354).
 *
 * - ZC-1720 (medium): the p4 Scaffold top bar of the opt-in screens (Tor opt-in, Currency Conversion settings) is
 *   drawn with `ZcashTheme.colors.topAppBarColors`, an opaque container, over a page painted with
 *   `MaterialTheme.colorScheme.background`. On Nightwire (#080B12 bar over #0D1B2A page), Nightwire Light (#F4F0E6
 *   bar over #FFFFFF) and Dark a band shows. The fix is a transparent container that keeps the theme's own arrow
 *   tint: `copyColors(containerColor = Color.Transparent)`. NOT `TransparentTopAppBarColors`, whose white arrow
 *   vanishes on the two light themes.
 * - ZC-1721 / ZC-1722 (low): the size choice and the wrap fallback of `StyledBalance` become public pure functions
 *   in `Balance.kt`; their behaviour is in [QA2PolishP4bBalanceScaleTest]. The checks here only pin that the widget
 *   really uses them, and that the amount the user copies stays free of invisible characters.
 * - ZC-1723 (low): both Receive cards kept `.clickable(onClick = state.onClick)` with an empty lambda, so a ripple
 *   and a TalkBack "double tap to activate" announce an action that does nothing. `onClick` becomes nullable and
 *   the card drops `clickable` when it is null.
 *
 * Sources are read as text, so this file compiles before the fix. [phone check]: the band on the four themes and
 * the Receive card with TalkBack.
 */
class QA2PolishP4bTest {
    private fun design(path: String): String =
        ChatResourceText.source("ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/$path")

    private fun ui(path: String): String =
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/$path")

    /** Source without block comments, line comments and import lines: what the compiler sees. */
    private fun code(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .filterNot { it.trimStart().startsWith("import ") }
            .joinToString("\n") { it.replace(Regex("""//.*$"""), "") }

    private fun balanced(
        text: String,
        open: Int
    ): String {
        val (o, c) =
            when (text[open]) {
                '(' -> '(' to ')'
                '{' -> '{' to '}'
                else -> fail("not an opening bracket at $open")
            }
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                o -> depth++
                c -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        fail("unbalanced brackets")
    }

    // ---------------------------------------------------------------- ZC-1720 (top-bar band on the opt-in screens)

    private val optIn by lazy { code(design("component/ZashiBaseSettingsOptIn.kt")) }

    /** The arguments of the `ZashiSmallTopAppBar(` call inside the opt-in's `topBar`. */
    private fun optInBarCall(): String {
        val at = optIn.indexOf("ZashiSmallTopAppBar(")
        if (at < 0) fail("ZashiBaseSettingsOptIn no longer draws its top bar with ZashiSmallTopAppBar")
        return balanced(optIn, at + "ZashiSmallTopAppBar".length)
    }

    @Test
    fun zc1720_the_opt_in_top_bar_container_is_transparent_so_no_band_shows_on_any_theme() {
        val colors =
            Regex("""colors\s*=\s*(.*)""", RegexOption.DOT_MATCHES_ALL)
                .find(optInBarCall())
                ?.groupValues
                ?.get(1)
                .orEmpty()
        assertTrue(
            colors.isNotBlank() &&
                Regex("""copyColors\(\s*containerColor\s*=\s*Color\.Transparent""").containsMatchIn(colors),
            "the opt-in top bar still uses the theme's opaque bar colour (the default `ZcashTheme.colors.topAppBarColors`) " +
                "over a page coloured MaterialTheme.colorScheme.background: a band shows on Nightwire (#080B12 over " +
                "#0D1B2A), Nightwire Light (#F4F0E6 over #FFFFFF) and Dark. Pass " +
                "colors = ZcashTheme.colors.topAppBarColors.copyColors(containerColor = Color.Transparent)"
        )
    }

    @Test
    fun zc1720_the_transparent_bar_keeps_the_theme_arrow_tint() {
        val call = optInBarCall()
        assertFalse(
            Regex("""navigationColor\s*=""").containsMatchIn(call),
            "the arrow colour comes from the theme (black on the light themes, white on the dark ones), not from an override"
        )
        assertFalse(
            "TransparentTopAppBarColors" in optIn || "transparentTopAppBarColors" in optIn,
            "TransparentTopAppBarColors has a white arrow: it vanishes on Nightwire Light and Light"
        )
    }

    @Test
    fun zc1720_pin_why_the_ready_made_transparent_colours_are_wrong_for_the_light_themes() {
        val colors = code(design("theme/internal/TopAppBarColors.kt"))
        val transparent = colors.substringAfter("class TransparentTopAppBarColors(").substringBefore(") : TopAppBarColors")
        assertTrue(Regex("""navigationColor:\s*Color\s*=\s*Color\(0xFFFFFFFF\)""").containsMatchIn(transparent))
        val light = colors.substringAfter("class LightTopAppBarColors(").substringBefore(") : TopAppBarColors")
        assertTrue(Regex("""navigationColor:\s*Color\s*=\s*Color\(0xFF000000\)""").containsMatchIn(light))
    }

    @Test
    fun zc1720_pin_the_opt_in_bar_still_holds_the_back_arrow_and_the_x_screens_are_unchanged() {
        assertTrue("ZashiTopAppBarBackNavigation(onBack = onDismiss)" in optInBarCall())
        assertTrue("if (useBackArrow)" in optIn && "ic_settings_opt_int_close" in optIn)
    }

    // ---------------------------------------------------------------- ZC-1721 / ZC-1722 (balance, wiring)

    private val balance by lazy { code(design("component/Balance.kt")) }

    @Test
    fun zc1721_the_balance_widget_chooses_its_size_with_the_pure_function() {
        assertTrue(
            Regex("""fun\s+chooseBalanceScale\(""").containsMatchIn(balance),
            "Balance.kt has no public top-level chooseBalanceScale(measure, maxWidth, floor)"
        )
        assertTrue(
            Regex("""chooseBalanceScale\(""").findAll(balance).count() >= 2,
            "chooseBalanceScale is declared but nothing in Balance.kt calls it, so the unit-tested function is not the one in use"
        )
    }

    @Test
    fun zc1722_the_wrap_fallback_breaks_at_the_decimal_separator() {
        assertTrue(
            Regex("""fun\s+balanceWrapLines\(""").containsMatchIn(balance),
            "Balance.kt has no public top-level balanceWrapLines(parts, decimalSeparator)"
        )
        assertTrue(
            Regex("""balanceWrapLines\(""").findAll(balance).count() >= 2,
            "balanceWrapLines is declared but StyledBalance does not use it in its wrap fallback, so the number still breaks at any digit"
        )
    }

    @Test
    fun zc1722_pin_no_invisible_character_goes_into_the_amount_text() {
        // The amount is selectable and copyable; a zero-width space inside it would be pasted into the amount field.
        val raw = design("component/Balance.kt")
        for (c in listOf('​', '‌', '‍', '⁠', '﻿', '­')) {
            assertFalse(c in raw, "Balance.kt contains the invisible character U+${c.code.toString(16).uppercase()}")
        }
        assertFalse(Regex("""\\u(200B|200C|200D|2060|FEFF|00AD)""", RegexOption.IGNORE_CASE).containsMatchIn(raw))
    }

    @Test
    fun zc1721_pin_no_marquee_no_ellipsis_and_the_floor_stay() {
        assertFalse("basicMarquee" in balance)
        assertFalse("TextOverflow.Ellipsis" in balance)
        assertTrue(Regex("""MIN_BALANCE_SCALE|minBalanceScale""").containsMatchIn(balance))
    }

    // ---------------------------------------------------------------- ZC-1723 (Receive cards: no no-op clickable)

    private val receiveState by lazy { code(ui("screen/receive/ReceiveState.kt")) }
    private val receiveVm by lazy { code(ui("screen/receive/ReceiveVM.kt")) }
    private val receiveView by lazy { code(ui("screen/receive/ReceiveView.kt")) }

    @Test
    fun zc1723_the_card_click_is_nullable() {
        assertTrue(
            Regex("""val\s+onClick:\s*\(\(\)\s*->\s*Unit\)\?""").containsMatchIn(receiveState),
            "ReceiveAddressState.onClick must be `(() -> Unit)?`: null means the card does nothing when tapped"
        )
    }

    @Test
    fun zc1723_neither_card_passes_an_empty_click() {
        assertFalse(
            Regex("""onClick\s*=\s*\{\s*\}""").containsMatchIn(receiveVm),
            "ReceiveVM still passes `onClick = {}`: the card has a ripple and TalkBack announces an action that does nothing"
        )
        for (type in listOf("Unified", "Transparent")) {
            val card = receiveVm.substringAfter("type = $type,", missingDelimiterValue = "").substringBefore("),")
            if (card.isEmpty()) fail("the $type createAddressState call is gone from ReceiveVM")
            assertTrue(Regex("""onClick\s*=\s*null""").containsMatchIn(card), "the $type card passes onClick = null: `${card.trim()}`")
        }
    }

    @Test
    fun zc1723_the_card_drops_clickable_when_there_is_no_click() {
        val panel = receiveView.substringAfter("private fun AddressPanel(").substringBefore("\n@Composable")
        assertFalse(
            Regex("""\.clickable\(\s*onClick\s*=\s*state\.onClick\s*\)""").containsMatchIn(panel),
            "AddressPanel still makes the card clickable unconditionally"
        )
        assertTrue(
            "?.let" in panel || "?.run" in panel || "!= null" in panel,
            "AddressPanel must add clickable only when state.onClick is not null"
        )
    }

    @Test
    fun zc1723_pin_the_buttons_inside_the_card_keep_their_own_clicks() {
        val panel = receiveView.substringAfter("private fun AddressPanel(").substringBefore("\n@Composable")
        for (cb in listOf("onCopyClicked", "onQrClicked", "onRequestClicked")) {
            assertTrue("state.$cb" in panel, "the card's $cb button is wired")
        }
        assertTrue(
            Regex("""onClick\s*=\s*\{\s*onAddressInfoClick\(type\)\s*\}""").containsMatchIn(receiveVm),
            "the info icon still opens the info"
        )
    }
}
