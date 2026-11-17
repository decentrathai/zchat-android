package co.electriccoin.zcash.ui.screen.advancedsettings

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 polish lane p4 (interim phone pass of 2026-11-11, `qa2/interim-pass/PASS_RESULT.md`): the money display and
 * four small screens the first rounds left open.
 *
 * - ZC-1716 (money display, Q2-01 follow-up): after the 48 sp full-size digits, an amount of about ten characters
 *   (8 decimals) or any 8-decimal amount at 1.3x font no longer fits. `StyledBalance` is `maxLines = 1` +
 *   `basicMarquee()`, so the right-hand digits rest cut off and only show while the marquee scrolls. The amount the
 *   user confirms (Home, Pay, the confirmation) must be fully readable at rest: it shrinks to fit, to a floor, with
 *   the integer and the decimals at the same size (Q2-01). It never ends in an ellipsis and never hides digits
 *   behind a marquee. There is no Compose UI test runner in the unit-test source set, so the checks below read
 *   `Balance.kt` and its three entry points as text; the real fit at 360 dp and at 1.3x is a [phone check].
 * - ZC-1717 (Q2-89/Advanced): the footnote and the Reset button sit below the fold. The bottom inset added in
 *   2d47bf4e8 is `consumeWindowInsets(paddingValues).windowInsetsPadding(navigationBars bottom)`; the Scaffold
 *   (default content insets = system bars) has already put the navigation bar into `paddingValues`, so the
 *   consume leaves 0 dp. Either remove it or pin the footnote and Reset below the scroll area; the footnote and
 *   Reset are never split (one pinned, one scrolling).
 * - ZC-1718 (Q2-93): Currency Conversion settings and the Tor opt-in still close with an X; they close with the
 *   back arrow like Tor settings, and the arrow is the top-bar arrow of the other screens (the first back arrow was
 *   a 40 dp IconButton inside the padded content column, so it sits lower and further right).
 * - ZC-1719: a tap on the transparent Receive card collapses the shielded card's Copy, QR and Request; the
 *   disabled Send Message container is 1.05:1 on Nightwire Light, invisible as a button, so it gets a border; the
 *   balance eye in the top bars keeps the drawable's own (night) stroke on the beige Nightwire Light page.
 *
 * [no-red] (Compose layout, phone check): the exact pixel fit of the shrunk amount; the exact position of the back
 * arrow against the other screens' arrow; the Advanced footnote and Reset on a 360 dp phone; the eye on Nightwire
 * Light.
 *
 * Sources are read as text, so this file compiles before the fix.
 */
class QA2PolishP4Test {
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

    /** The text inside the brackets that open at [open] (the index of the opening one). */
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

    /** Every `name(...)` call of [name] in [text], as the text of its arguments. */
    private fun calls(
        text: String,
        name: String
    ): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text).map { balanced(text, it.range.last) }.toList()

    // ---------------------------------------------------------------- ZC-1716 (money display)

    private val balance by lazy { code(design("component/Balance.kt")) }

    @Test
    fun zc1716_the_balance_never_hides_digits_behind_a_marquee() {
        assertFalse(
            "basicMarquee" in balance,
            "StyledBalance still scrolls its text with basicMarquee(): at rest the digits that do not fit (the " +
                "right-hand ones of 1.23456789) are cut off, so an amount the user confirms is not fully readable"
        )
    }

    @Test
    fun zc1716_the_balance_never_ends_in_an_ellipsis() {
        assertFalse("TextOverflow.Ellipsis" in balance, "an ellipsis would replace digits of the amount")
    }

    @Test
    fun zc1716_the_balance_shrinks_to_fit_the_width_it_gets() {
        val fits =
            Regex("""TextAutoSize|autoSize\s*=|didOverflowWidth|rememberTextMeasurer|TextMeasurer|BoxWithConstraints|SubcomposeLayout""")
        assertTrue(
            fits.containsMatchIn(balance),
            "StyledBalance draws one line at a fixed size (maxLines = 1) and has no fit-to-width step " +
                "(auto-size, an overflow check with a smaller retry, a text measurer): an 8-decimal amount at 48 sp " +
                "or any amount at 1.3x does not fit"
        )
    }

    @Test
    fun zc1716_the_shrink_stops_at_a_floor_so_the_digits_stay_legible() {
        // A floor keeps a very long amount from shrinking into a smudge; without one, "fully visible" is bought
        // with unreadable digits. Any of these spellings names the lower bound.
        val floor = Regex("""minFontSize|MIN_\w*(SIZE|SCALE|SP)|min\w*(Size|Scale)\b|coerceAtLeast""")
        assertTrue(
            floor.containsMatchIn(balance),
            "no lower bound on the shrunk size is named in Balance.kt (minFontSize, MIN_..._SIZE, coerceAtLeast)"
        )
    }

    @Test
    fun zc1716_pin_home_pay_and_the_confirmation_reach_the_balance_through_one_widget() {
        // The one place to fix: all three entry points go through BalanceWidgetHeader -> StyledBalance.
        val widget = code(ui("screen/balances/BalanceWidget.kt"))
        assertTrue("StyledBalance(" in widget && "fun BalanceWidgetHeader(" in widget)
        assertTrue("BalanceWidgetHeader(" in widget.substringAfter("fun BalanceWidget("), "BalanceWidget draws the header")
        assertTrue("BalanceWidget(" in code(ui("screen/home/HomeView.kt")), "Home draws the balance via BalanceWidget")
        assertTrue("BalanceWidget(" in code(ui("screen/pay/PayView.kt")), "Pay draws the balance via BalanceWidget")
        assertTrue(
            "BalanceWidgetHeader(" in code(ui("screen/reviewtransaction/ReviewTransactionView.kt")),
            "the confirmation draws the amount via BalanceWidgetHeader"
        )
    }

    @Test
    fun zc1716_pin_the_integer_and_the_decimals_stay_one_size() {
        // Q2-01 / ZC-1699: the shrink must not bring back a smaller trailing part.
        val header = code(ui("screen/balances/BalanceWidget.kt")).substringAfter("fun BalanceWidgetHeader(")
        val args = balanced(header, header.indexOf("StyledBalanceDefaults.textStyles(") + "StyledBalanceDefaults.textStyles".length)
        val styles = Regex("""ZashiTypography\.(\w+)""").findAll(args).map { it.groupValues[1] }.toList()
        assertEquals(2, styles.size, "both digit parts name a style: `${args.trim()}`")
        assertEquals(styles[0], styles[1], "integer and decimals are drawn at one size")
    }

    @Test
    fun zc1716_pin_a_hidden_balance_still_shows_the_placeholder() {
        assertTrue("hiddenBalancePlaceholder" in balance && "isHideBalances" in balance)
    }

    // ---------------------------------------------------------------- ZC-1717 (Advanced footnote and Reset)

    private val advanced by lazy { code(ui("screen/advancedsettings/AdvancedSettingsView.kt")) }

    /** The body of `AdvancedSettings(...)` after the signature. */
    private fun advancedBody(): String {
        val at = advanced.indexOf("fun AdvancedSettings(")
        if (at < 0) fail("AdvancedSettings is gone")
        val open = advanced.indexOf('{', advanced.indexOf(')', at))
        return balanced(advanced, open)
    }

    @Test
    fun zc1717_the_bottom_inset_is_not_cancelled_by_the_scaffold_padding_it_follows() {
        val body = advancedBody()
        val dead =
            Regex(
                """consumeWindowInsets\(\s*paddingValues\s*\)\s*\.windowInsetsPadding\(\s*WindowInsets\.navigationBars""" +
                    """|consumeWindowInsets\(\s*paddingValues\s*\)\s*\.navigationBarsPadding\(\)"""
            )
        assertFalse(
            dead.containsMatchIn(body),
            "the scroll column consumes the Scaffold's insets and then asks for the navigation-bar inset: " +
                "the consume has already taken it, the added padding is 0 dp (ZC-1717), and the footnote and Reset " +
                "stay below the fold. Remove it, or pin the footnote and Reset outside the scroll area."
        )
    }

    @Test
    fun zc1717_the_footnote_and_reset_are_never_split_between_pinned_and_scrolling() {
        val body = advancedBody()
        val scrollAt = body.indexOf("verticalScroll(")
        if (scrollAt < 0) return // nothing scrolls: nothing can be split
        val open = body.lastIndexOf("Column(", scrollAt) + "Column".length
        val argsEnd = open + balanced(body, open).length + 1
        val scrolling = balanced(body, body.indexOf('{', argsEnd))
        val info = "Info()" in scrolling
        val reset = "state.deleteButton" in scrolling
        assertEquals(info, reset, "the footnote (Info) and the Reset button must sit together: scrolling=$info/$reset")
        assertTrue("Info()" in body && "state.deleteButton" in body, "the footnote and the Reset button are still drawn")
    }

    @Test
    fun zc1717_pin_the_scaffold_hands_the_navigation_bar_to_its_content() {
        // The reason the consume cancels the inset: BlankBgScaffold leaves contentWindowInsets at the default (system
        // bars), so `paddingValues` already holds the navigation bar. If this changes, ZC-1717 needs another look.
        val scaffold = code(design("component/Scaffold.kt"))
        val blank = scaffold.substringAfter("fun BlankBgScaffold(").substringBefore("fun GradientBgScaffold(")
        assertFalse("contentWindowInsets" in blank, "BlankBgScaffold overrides the content insets")
    }

    @Test
    fun zc1717_pin_reset_is_still_the_destructive_state_button_with_the_footnote_above_it() {
        val body = advancedBody()
        assertTrue("ZashiButtonDefaults.destructive1Colors()" in body)
        assertTrue(body.indexOf("Info()") in 0 until body.indexOf("state.deleteButton"), "the footnote sits above Reset")
        assertTrue("advanced_settings_info" in advanced, "the footnote text is still drawn")
        assertTrue("state.items.fastForEachIndexed" in body, "the list rows are still drawn")
    }

    // ---------------------------------------------------------------- ZC-1718 (close with the back arrow)

    private val optIn by lazy { code(design("component/ZashiBaseSettingsOptIn.kt")) }

    private fun asksForBackArrow(path: String) = Regex("""useBackArrow\s*=\s*true""").containsMatchIn(code(ui(path)))

    @Test
    fun zc1718_currency_conversion_settings_close_with_the_back_arrow() {
        assertTrue(
            asksForBackArrow("screen/exchangerate/settings/ExchangeRateSettingsView.kt"),
            "ExchangeRateSettingsView still closes with the X (useBackArrow = true is missing)"
        )
    }

    @Test
    fun zc1718_the_tor_opt_in_closes_with_the_back_arrow() {
        assertTrue(
            asksForBackArrow("screen/tor/optin/TorOptInView.kt"),
            "TorOptInView still closes with the X (useBackArrow = true is missing)"
        )
    }

    @Test
    fun zc1718_the_back_arrow_is_the_top_bar_arrow_of_the_other_screens() {
        val branch = optIn.substringAfter("if (useBackArrow) {", missingDelimiterValue = "").substringBefore("} else {")
        if (branch.isEmpty()) fail("ZashiBaseSettingsOptIn has no useBackArrow branch")
        assertTrue(
            Regex("""ZashiTopAppBarBackNavigation\(|ZashiSmallTopAppBar\(""").containsMatchIn(optIn),
            "the opt-in screen draws its back arrow as a bare 40 dp IconButton; the other screens use " +
                "ZashiTopAppBarBackNavigation inside the top app bar, so this arrow sits lower and further right"
        )
    }

    @Test
    fun zc1718_the_back_arrow_sits_in_the_scaffold_top_bar_not_the_padded_content_column() {
        // The content column carries scaffoldPadding (24 dp start, 16 dp top): an arrow drawn inside it cannot match
        // the other screens' arrow, which the top app bar places. Feasible here: Scaffold(topBar = ...).
        val scaffoldAt = Regex("""Scaffold\(""").find(optIn)
        val args = if (scaffoldAt == null) "" else balanced(optIn, scaffoldAt.range.last)
        assertTrue(
            "topBar" in args && Regex("""ZashiTopAppBarBackNavigation\(|ZashiSmallTopAppBar\(""").containsMatchIn(args),
            "Scaffold has no topBar that holds the back arrow"
        )
    }

    @Test
    fun zc1718_pin_the_x_close_stays_for_a_screen_that_does_not_ask_for_the_arrow() {
        // The crash-report and Currency Conversion opt-in screens keep the X unless the owner says otherwise.
        assertTrue("ic_settings_opt_int_close" in optIn, "the X drawable is still available to the opt-in screens")
        assertTrue(Regex("""useBackArrow:\s*Boolean\s*=\s*false""").containsMatchIn(optIn), "the default stays X")
        assertTrue(asksForBackArrow("screen/tor/settings/TorSettingsView.kt"), "Tor settings keep the back arrow (ZC-1703)")
    }

    // ---------------------------------------------------------------- ZC-1719 (Receive card, Send Message, eye)

    @Test
    fun zc1719_a_tap_on_the_transparent_receive_card_does_not_collapse_the_shielded_card() {
        val vm = code(ui("screen/receive/ReceiveVM.kt"))
        val shielded = vm.substringAfter("type = Unified,", missingDelimiterValue = "").substringBefore("onClick")
        if (shielded.isEmpty()) fail("the shielded card's createAddressState call is gone from ReceiveVM")
        assertFalse(
            "expandedIndex" in shielded,
            "the shielded card's isExpanded still follows expandedIndex: a tap on the transparent card sets it to 1 " +
                "and hides the shielded card's Copy, QR and Request (`${shielded.trim()}`)"
        )
    }

    @Test
    fun zc1719_pin_the_receive_buttons_are_still_gated_by_the_card_state() {
        val view = code(ui("screen/receive/ReceiveView.kt"))
        assertTrue(Regex("""AnimatedVisibility\(\s*visible\s*=\s*state\.isExpanded""").containsMatchIn(view))
        val vm = code(ui("screen/receive/ReceiveVM.kt"))
        val transparent = vm.substringAfter("type = Transparent,", missingDelimiterValue = "").substringBefore("onClick")
        assertTrue(Regex("""isExpanded\s*=\s*true""").containsMatchIn(transparent), "ZC-1700: the transparent card stays open")
    }

    // ---- the disabled Send Message container needs a border on the light themes

    private data class Rgba(
        val r: Double,
        val g: Double,
        val b: Double,
        val a: Double = 1.0
    )

    private val chatThemeSource by lazy { ui("screen/chat/view/ChatThemeColors.kt") }

    private fun colorsFile(file: String) =
        ChatResourceText.source("ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/theme/colors/$file")

    private fun argb(hex: String): Rgba {
        val v = hex.toLong(16)
        return Rgba(
            ((v shr 16) and 0xFF) / 255.0,
            ((v shr 8) and 0xFF) / 255.0,
            (v and 0xFF) / 255.0,
            ((v shr 24) and 0xFF) / 255.0
        )
    }

    /** Every `field = <colour>` of the chat palette [name], with its alpha (`Color(0xAARRGGBB)` or `NightwireX.Name`). */
    private fun palette(name: String): Map<String, Rgba> {
        val block = chatThemeSource.substringAfter("val $name = ChatColors(").substringBefore("\n)\n")
        val out = mutableMapOf<String, Rgba>()
        for (m in Regex("""(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)\s*[,\n]""").findAll(block)) {
            out[m.groupValues[1]] = argb(m.groupValues[2])
        }
        for (m in Regex("""(\w+)\s*=\s*(NightwireColors|NightwireLightColors)\.(\w+)\s*[,\n]""").findAll(block)) {
            val hex =
                Regex("""val ${m.groupValues[3]}\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""")
                    .find(colorsFile("${m.groupValues[2]}.kt"))
                    ?.groupValues
                    ?.get(1)
            if (hex != null) out[m.groupValues[1]] = argb(hex)
        }
        assertTrue(out.containsKey("background") && out.containsKey("textSecondary"), "cannot read the $name palette: ${out.keys}")
        return out
    }

    private fun blend(
        top: Rgba,
        alpha: Double,
        under: Rgba
    ): Rgba {
        val a = top.a * alpha
        return Rgba(
            top.r * a + under.r * (1 - a),
            top.g * a + under.g * (1 - a),
            top.b * a + under.b * (1 - a)
        )
    }

    private fun lum(c: Rgba): Double {
        fun lin(x: Double) = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
    }

    private fun contrast(
        a: Rgba,
        b: Rgba
    ): Double {
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val lightThemes = listOf("NightwireLightChatColors", "LightChatColors")

    private fun sendMessageButtonCall(): String {
        val compose = code(ui("screen/chat/view/ZchatComposeView.kt"))
        val at = compose.indexOf("onClick = state.onSendClick")
        if (at < 0) fail("the Send Message button is gone from ZchatComposeView")
        val start = compose.lastIndexOf("Button(", at)
        return balanced(compose, start + "Button".length)
    }

    @Test
    fun zc1719_the_disabled_send_message_button_has_a_visible_border_on_the_light_themes() {
        val button = sendMessageButtonCall()
        val border =
            Regex("""\bborder\s*=\s*(.*)""").find(button)?.groupValues?.get(1)
                ?: fail(
                    "the disabled Send Message container (bgElevated) is 1.05:1 on Nightwire Light, " +
                        "so the button has no visible shape; give the Button a border"
                )
        val stroke =
            Regex("""BorderStroke\(\s*([0-9.]+)\.dp\s*,\s*([^\n]*)""").find(border)
                ?: fail("write the border as BorderStroke(<width>.dp, chatColors().<field>): `${border.trim()}`")
        val width = stroke.groupValues[1].toDouble()
        assertTrue(width >= 1.0, "the border is $width dp wide (needs at least 1 dp)")
        val ref =
            Regex("""chatColors\(\)\.(\w+)(?:\.copy\(alpha\s*=\s*([0-9.]+)f\))?""").find(stroke.groupValues[2])
                ?: fail("write the border colour as chatColors().<field>[.copy(alpha = Nf)]: `${stroke.groupValues[2].trim()}`")
        val field = ref.groupValues[1]
        val alpha = ref.groupValues[2].ifEmpty { "1" }.toDouble()
        for (theme in lightThemes) {
            val p = palette(theme)
            val page = p.getValue("background")
            val edge = blend(p[field] ?: fail("$theme has no $field"), alpha, page)
            val ratio = contrast(edge, page)
            assertTrue(
                ratio >= MIN_BORDER_CONTRAST,
                "$theme: the disabled Send Message border is %.2f:1 on the page (needs %.1f:1, WCAG non-text): `%s`"
                    .format(ratio, MIN_BORDER_CONTRAST, stroke.groupValues[2].trim())
            )
        }
    }

    @Test
    fun zc1719_pin_the_disabled_send_message_label_stays_readable() {
        // ZC-1706 (p1): the border does not replace the readable label; the colours it chose stay.
        val button = sendMessageButtonCall()
        assertTrue(Regex("""disabledContentColor\s*=\s*chatColors\(\)\.textSecondary""").containsMatchIn(button), button)
        assertTrue(Regex("""containerColor\s*=\s*chatColors\(\)\.primary""").containsMatchIn(button), "enabled colour unchanged")
    }

    // ---- the balance eye in the top bars follows the app theme

    private val tinted = Regex("""tint\s*=\s*(ZashiColors\.|cc\.|chatColors\(\))\w*""")

    /** The `ZashiIconButton(...)` calls of [path] that draw the balance eye. */
    private fun eyeCalls(path: String): List<String> {
        val src = code(ui(path))
        return Regex("""ZashiIconButton\(""")
            .findAll(src)
            .mapNotNull {
                val args = balanced(src, it.range.last)
                val before = src.substring(maxOf(0, it.range.first - 90), it.range.first)
                val inLet = Regex("""balanceVisibilityButton\??\.let\s*\{\s*(\w+\s*->)?\s*$""").containsMatchIn(before)
                if ("balanceVisibilityButton" in args || inLet) args else null
            }.toList()
    }

    private fun assertEyeTinted(path: String) {
        val calls = eyeCalls(path)
        assertTrue(calls.isNotEmpty(), "$path: no balance eye ZashiIconButton found")
        for (c in calls) {
            assertTrue(
                tinted.containsMatchIn(c),
                "$path: the balance eye keeps the drawable's own stroke (pale grey from drawable-night on the beige " +
                    "Nightwire Light page); tint it from the theme (ZashiColors.Text.textPrimary): `${c.trim()}`"
            )
        }
    }

    @Test
    fun zc1719_the_eye_in_the_plain_top_bar_is_tinted_from_the_theme() = assertEyeTinted("common/appbar/ZashiTopAppbar.kt")

    @Test
    fun zc1719_the_eye_in_the_account_top_bar_is_tinted_from_the_theme() =
        assertEyeTinted("common/appbar/ZashiTopAppBarWithAccountSelection.kt")

    /** Same defect, same one-line fix, in the other screens that draw the eye (not named by the register item). */
    @Test
    fun zc1719_the_eye_in_the_other_screens_is_tinted_from_the_theme_too() {
        for (
        path in
        listOf(
            "screen/transactiondetail/TransactionDetailView.kt",
            "screen/swap/detail/SwapDetailView.kt",
            "screen/transactionhistory/ActivityHistoryView.kt",
            "screen/pay/PayView.kt"
        )
        ) {
            assertEyeTinted(path)
        }
    }

    @Test
    fun zc1719_pin_the_wallet_eye_stays_tinted_and_the_default_tint_is_unchanged() {
        assertTrue(Regex("""tint\s*=\s*cc\.textPrimary""").containsMatchIn(code(ui("screen/wallettab/WalletTabView.kt"))))
        assertTrue(Regex("""tint:\s*Color\s*=\s*Color\.Unspecified""").containsMatchIn(code(design("component/ZashiIconButton.kt"))))
    }

    private companion object {
        /** WCAG 1.4.11 non-text contrast: a control's edge against the page. */
        const val MIN_BORDER_CONTRAST = 3.0
    }
}
