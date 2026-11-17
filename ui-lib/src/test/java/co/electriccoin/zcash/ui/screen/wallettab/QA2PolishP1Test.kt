package co.electriccoin.zcash.ui.screen.wallettab

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.io.File
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 polish lane p1 (interim pass on the 2.13.0 debug APK, `qa2/interim-pass/PASS_RESULT.md`): five items that the
 * first fix round left FAIL on the phones.
 *
 * - ZC-1699 (Q2-01): the Wallet and Send headline draw "0.000" large and the significant digits ("46") at about 60 %
 *   of it. They must be the same size as the lead. Text-free (the digits are numbers, the same composable draws
 *   English and Spanish).
 * - ZC-1700 (Q2-07): the transparent Receive card still showed no Copy and no Request. The buttons exist, but they
 *   sit behind `isExpanded`, and only the shielded card starts expanded; the transparent card shows nothing that
 *   says it can be tapped. The actions must be visible on the transparent card without a tap. The labels
 *   (`receive_copy`, `receive_request`) already exist in English and Spanish and are checked here.
 * - ZC-1701 (Q2-13): the Request memo screen draws the 64 dp logo at the top of the scrolling column; with the
 *   keyboard up the column scrolls and the logo is cut by the title bar into a white half-disc. The logo is not
 *   drawn while the keyboard is up.
 * - ZC-1702 (Q2-20): the Wallet action buttons are `maxLines = 1` + `Ellipsis` at 13 sp; at 1.3x font scale
 *   "Receive" reads "Rece...". The label wraps or shrinks, it never ends in an ellipsis.
 * - ZC-1706 (light theme, Nightwire Light): the Wallet eye icon is pale grey on beige (the night drawable is picked
 *   by the system mode, not by the app theme); the disabled Send Message label and the disabled New Chat mode chips
 *   ("Needs code", "On-chain") are very faint. The contrast checks below recompute the colours from the palette
 *   sources, so they are numbers, not opinions. Not covered (file owned by another lane): the green "Paid" and
 *   amount on the teal payment bubble (ChatDialogs.kt:1699 `statusColor = chatColors().success`).
 *
 * Sources and resources are read as text, so this file compiles before the fix. The disabled-colour checks need the
 * colours written as `chatColors().<field>` with an optional `.copy(alpha = Nf)`, the way the file writes them today.
 */
class QA2PolishP1Test {
    private val en = ChatResourceText.strings("values")
    private val es = ChatResourceText.strings("values-es")

    private fun src(path: String): String =
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/$path")

    // ---------------------------------------------------------------- ZC-1699 (Q2-01)

    /** The text inside the parentheses that open at [open] (the index of '('). */
    private fun balanced(
        text: String,
        open: Int
    ): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        fail("unbalanced parentheses")
    }

    @Test
    fun zc1699_the_headline_balance_draws_every_digit_at_the_headline_size() {
        val widget = src("screen/balances/BalanceWidget.kt")
        val header = widget.substringAfter("fun BalanceWidgetHeader(", missingDelimiterValue = "")
        if (header.isEmpty()) fail("BalanceWidgetHeader is gone from BalanceWidget.kt")
        val at = header.indexOf("StyledBalanceDefaults.textStyles(")
        if (at < 0) fail("BalanceWidgetHeader no longer sets the digit styles")
        val args = balanced(header, header.indexOf('(', at))
        val mostAt = args.indexOf("mostSignificantPart")
        val leastAt = args.indexOf("leastSignificantPart")
        if (mostAt < 0 || leastAt < 0) fail("name both digit parts of the headline")
        val most = if (mostAt < leastAt) args.substring(mostAt, leastAt) else args.substring(mostAt)
        val least = if (leastAt < mostAt) args.substring(leastAt, mostAt) else args.substring(leastAt)
        val mostStyle = Regex("""ZashiTypography\.(\w+)""").find(most)?.groupValues?.get(1)
        val leastStyle = Regex("""ZashiTypography\.(\w+)""").find(least)?.groupValues?.get(1)
        assertTrue(mostStyle != null && leastStyle != null, "cannot read the headline styles: `${args.trim()}`")
        assertEquals(
            mostStyle,
            leastStyle,
            "Wallet/Send headline: the significant digits are drawn in ZashiTypography.$leastStyle under a " +
                "ZashiTypography.$mostStyle lead; they must be the same size"
        )
    }

    // ---------------------------------------------------------------- ZC-1700 (Q2-07)

    @Test
    fun zc1700_the_transparent_receive_card_shows_copy_and_request_without_a_tap() {
        val view = src("screen/receive/ReceiveView.kt")
        val vm = src("screen/receive/ReceiveVM.kt")

        // The button row is gated by a visibility flag. Either the view also shows it for a card that is not
        // shielded, or the view model hands the transparent card `isExpanded = true`.
        val gate = Regex("""AnimatedVisibility\(\s*visible\s*=\s*([^)]*)\)""").find(view)?.groupValues?.get(1)
        if (gate == null) fail("the Receive card's button row (AnimatedVisibility) is gone from ReceiveView")
        val viewShowsIt = "isShielded" in gate

        val transparentCall = vm.substringAfter("type = Transparent,", missingDelimiterValue = "").substringBefore("onClick")
        val vmShowsIt = Regex("""isExpanded\s*=\s*true""").containsMatchIn(transparentCall)

        assertTrue(
            viewShowsIt || vmShowsIt,
            "the transparent Receive card starts collapsed (isExpanded = expandedIndex == 1) and shows no Copy, QR or " +
                "Request until it is tapped; nothing on the card says it can be tapped. view gate: `$gate`"
        )
    }

    @Test
    fun zc1700_copy_and_request_have_labels_in_both_languages() {
        for (key in listOf("receive_copy", "receive_request")) {
            assertTrue(!en[key].isNullOrBlank(), "English text for $key")
            assertTrue(!es[key].isNullOrBlank(), "Spanish text for $key")
            assertTrue(es[key] != en[key], "Spanish $key is still the English text")
        }
    }

    // ---------------------------------------------------------------- ZC-1701 (Q2-13)

    @Test
    fun zc1701_the_request_memo_logo_is_not_drawn_while_the_keyboard_is_up() {
        val view = src("screen/request/view/RequestMemoView.kt")
        val use = view.indexOf("painterResource(id = state.icon)")
        if (use < 0) return // the logo is gone from the memo screen: nothing can be cut
        val imageAt = view.lastIndexOf("Image(", use)
        val before = view.substring(maxOf(0, imageAt - 400), imageAt)
        val guarded =
            Regex("""if\s*\([^)]*(isImeVisible|WindowInsets\.ime|imeVisible|isKeyboard|keyboardOpen|LocalKeyboardManager)""")
                .containsMatchIn(before)
        assertTrue(
            guarded,
            "RequestMemoView draws the 64 dp logo at the top of the scrolling column with no keyboard check; with the " +
                "keyboard up the column scrolls and the title bar cuts it into a half-disc"
        )
    }

    // ---------------------------------------------------------------- ZC-1702 (Q2-20)

    @Test
    fun zc1702_the_wallet_action_buttons_never_end_in_an_ellipsis() {
        val view = src("screen/wallettab/WalletTabView.kt")
        val start = view.indexOf("private fun WalletActionButton(")
        if (start < 0) fail("WalletActionButton is gone from WalletTabView.kt")
        val body = view.substring(start, view.indexOf("\n}\n", start).let { if (it < 0) view.length else it })
        assertFalse(
            "TextOverflow.Ellipsis" in body,
            "WalletActionButton ellipsizes its label: at 1.3x font scale 'Receive' reads 'Rece...'"
        )
        val oneLine = Regex("""maxLines\s*=\s*1\b""").containsMatchIn(body)
        val shrinks = Regex("""autoSize|AutoSize|AutoSizing|onTextLayout""").containsMatchIn(body)
        assertTrue(
            !oneLine || shrinks,
            "WalletActionButton keeps the label on one line (maxLines = 1) with nothing that shrinks it: it is clipped"
        )
    }

    @Test
    fun zc1702_the_three_wallet_buttons_have_labels_in_both_languages() {
        for (key in listOf("home_button_receive", "home_button_send", "home_button_swap")) {
            assertTrue(!en[key].isNullOrBlank(), "English text for $key")
            assertTrue(!es[key].isNullOrBlank(), "Spanish text for $key")
        }
    }

    // ---------------------------------------------------------------- ZC-1706 (light theme contrast)

    @Test
    fun zc1706_the_wallet_eye_takes_its_colour_from_the_app_theme() {
        val view = src("screen/wallettab/WalletTabView.kt")
        val at = view.indexOf("if (balanceVisibilityButton != null) {")
        if (at < 0) fail("the Wallet header eye is gone from WalletTabView.kt")
        val block = view.substring(at, minOf(view.length, at + 500)).substringBefore("if (accountChip")
        assertTrue(
            Regex("""tint\s*=\s*(cc|chatColors\(\))\.\w+""").containsMatchIn(block),
            "the Wallet eye keeps the drawable's own stroke (pale grey from drawable-night when the phone is in dark " +
                "mode, on the beige Nightwire Light page); tint it from the theme (cc.textPrimary): `${block.trim()}`"
        )
    }

    private data class Rgb(
        val r: Double,
        val g: Double,
        val b: Double
    )

    private fun hex(
        file: String,
        name: String
    ): Rgb {
        val text =
            File(ChatResourceText.repoRoot, "ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/theme/colors/$file").readText()
        val m = Regex("""val $name\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""").find(text) ?: fail("$file has no opaque $name")
        val v = m.groupValues[1].toLong(16)
        return Rgb(((v shr 16) and 0xFF) / 255.0, ((v shr 8) and 0xFF) / 255.0, (v and 0xFF) / 255.0)
    }

    /** The opaque colour of every `field = NightwireColors.X` / `NightwireLightColors.X` of the chat palette [name]. */
    private fun palette(name: String): Map<String, Rgb> {
        val text = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatThemeColors.kt")
        val block = text.substringAfter("val $name = ChatColors(").substringBefore("\n)\n")
        val out = mutableMapOf<String, Rgb>()
        for (m in Regex("""(\w+)\s*=\s*(NightwireColors|NightwireLightColors)\.(\w+)\s*[,\n]""").findAll(block)) {
            runCatching { hex("${m.groupValues[2]}.kt", m.groupValues[3]) }.getOrNull()?.let { out[m.groupValues[1]] = it }
        }
        assertTrue(out.containsKey("background") && out.containsKey("textSecondary"), "cannot read the $name palette: ${out.keys}")
        return out
    }

    private fun blend(
        top: Rgb,
        alpha: Double,
        under: Rgb
    ) = Rgb(
        top.r * alpha + under.r * (1 - alpha),
        top.g * alpha + under.g * (1 - alpha),
        top.b * alpha + under.b * (1 - alpha)
    )

    private fun lum(c: Rgb): Double {
        fun lin(x: Double) = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
    }

    private fun contrast(
        a: Rgb,
        b: Rgb
    ): Double {
        val (hi, lo) = lum(a).let { la -> lum(b).let { lb -> if (la >= lb) la to lb else lb to la } }
        return (hi + 0.05) / (lo + 0.05)
    }

    /** `chatColors().<field>[.copy(alpha = Nf)]` read from the text of a colour argument: field and alpha. */
    private fun colourRef(expression: String): Pair<String, Double>? =
        Regex("""chatColors\(\)\.(\w+)(?:\.copy\(alpha\s*=\s*([0-9.]+)f\))?""").find(expression)?.let {
            it.groupValues[1] to (it.groupValues[2].ifEmpty { "1" }.toDouble())
        }

    private val themes = listOf("NightwireLightChatColors", "ZypherpunkChatColors")

    @Test
    fun zc1706_the_disabled_send_message_label_is_readable_in_light_and_dark() {
        val compose = src("screen/chat/view/ZchatComposeView.kt")
        val container =
            Regex("""disabledContainerColor\s*=\s*([^\n]*)""").find(compose)?.groupValues?.get(1)
                ?: fail("the Send Message button has no disabledContainerColor")
        val content =
            Regex("""disabledContentColor\s*=\s*([^\n]*)""").find(compose)?.groupValues?.get(1)
                ?: fail("the Send Message button has no disabledContentColor")
        val (containerField, containerAlpha) =
            colourRef(container)
                ?: fail("write the disabled container as chatColors().<field>[.copy(alpha = Nf)]: `$container`")
        val (contentField, contentAlpha) =
            colourRef(content)
                ?: fail("write the disabled label as chatColors().<field>[.copy(alpha = Nf)]: `$content`")
        for (theme in themes) {
            val p = palette(theme)
            val page = p.getValue("background")
            val button = blend(p[containerField] ?: fail("$theme has no $containerField"), containerAlpha, page)
            val label = blend(p[contentField] ?: fail("$theme has no $contentField"), contentAlpha, button)
            val ratio = contrast(label, button)
            assertTrue(
                ratio >= MIN_DISABLED_CONTRAST,
                "$theme: the disabled Send Message label is %.2f:1 on its button (needs %.1f:1): container `%s`, label `%s`"
                    .format(ratio, MIN_DISABLED_CONTRAST, container.trim(), content.trim())
            )
        }
    }

    @Test
    fun zc1706_the_disabled_and_locked_new_chat_mode_chips_are_readable_in_light_and_dark() {
        val compose = src("screen/chat/view/ZchatComposeView.kt")
        val from = compose.indexOf("the chips are one radio group")
        if (from < 0) fail("the New Chat mode chips are gone from ZchatComposeView")
        val chips = compose.substring(from, compose.indexOf("if (showOpenNeedsCode)", from).let { if (it < 0) compose.length else it })

        val rowAlpha =
            Regex("""\.alpha\(if \(enabled\) 1f else ([0-9.]+)f\)""").findAll(chips).map { it.groupValues[1].toDouble() }.minOrNull() ?: 1.0
        val lockedAlpha =
            Regex("""\.alpha\(if \(locked\) ([0-9.]+)f else 1f\)""")
                .find(chips)
                ?.groupValues
                ?.get(1)
                ?.toDouble() ?: 1.0
        val label =
            Regex("""color = if \(isSelected\) chatColors\(\)\.primary else ([^\n]*)""").find(chips)?.groupValues?.get(1)
                ?: fail("cannot read the chip name colour")
        val cueAt = chips.indexOf("shortTagRes")
        val cue =
            Regex("""else\s+(chatColors\(\)\.\w+[^\n]*)""").find(chips, cueAt)?.groupValues?.get(1)
                ?: fail("cannot read the chip cue colour")
        val (labelField, labelAlpha) = colourRef(label) ?: fail("write the chip name colour as chatColors().<field>: `$label`")
        val (cueField, cueAlpha) = colourRef(cue) ?: fail("write the chip cue colour as chatColors().<field>[.copy(alpha = Nf)]: `$cue`")

        for (theme in themes) {
            val p = palette(theme)
            val page = p.getValue("background")
            val chipBg = p.getValue("bgElevated")

            // text alpha inside the chip (the chip alpha is applied after the background, so it dims the text only),
            // then the whole row (chip background included) is faded over the page by the row alpha.
            fun seen(
                field: String,
                textAlpha: Double,
                rowFade: Double
            ): Double {
                val text = blend(p.getValue(field), textAlpha, chipBg)
                return contrast(blend(text, rowFade, page), blend(chipBg, rowFade, page))
            }

            val cases =
                listOf(
                    Triple("disabled chip name (Shielded/Tunnel/Open)", seen(labelField, labelAlpha, rowAlpha), MIN_DISABLED_CONTRAST),
                    Triple("disabled chip cue (On-chain)", seen(cueField, cueAlpha, rowAlpha), MIN_DISABLED_CONTRAST),
                    Triple(
                        "disabled locked chip cue (Needs code)",
                        seen(cueField, cueAlpha * lockedAlpha, rowAlpha),
                        MIN_DISABLED_CONTRAST
                    ),
                    Triple("enabled locked chip cue (Needs code)", seen(cueField, cueAlpha * lockedAlpha, 1.0), MIN_ACTIONABLE_CONTRAST),
                )
            for ((what, ratio, need) in cases) {
                assertTrue(
                    ratio >= need,
                    "$theme: $what is %.2f:1 (needs %.1f:1); row alpha $rowAlpha, locked alpha $lockedAlpha, cue `%s`"
                        .format(ratio, need, cue.trim())
                )
            }
        }
    }

    private companion object {
        /** Disabled controls are exempt from WCAG, but the owner wants them readable: the large-text floor. */
        const val MIN_DISABLED_CONTRAST = 3.0

        /** A locked chip is dimmed but still taps to an explanation, so its 12 sp cue is normal text. */
        const val MIN_ACTIONABLE_CONTRAST = 4.5
    }
}
