package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.SHARE_QR
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.ZASHI_QR
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.block
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.callArguments
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.productKotlinFiles
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.source
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.squash
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures.topLevelArguments
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QR-DRAW (fix-all 2.13.0, train T4c) (b): a QR is always drawn dark on light, whatever the theme.
 *
 * At HEAD `QrCodeDefaults.colors()` picks `Color.White orDark ZashiColors.Surfaces.bgPrimary` and
 * `Color.Black orDark Color.White`, and `orDark` follows the phone's night mode, not the ZCHAT theme. With the phone
 * dark and ZCHAT on Light the QR is white on white (blank); on Nightwire Light white on cream; on the dark themes
 * inverted. The fix takes both colours from a fixed palette (`QrCodePalette`, reached by name here so this test
 * compiles before it exists).
 */
class QrColoursAreDarkOnLightTest {
    @Test
    fun defaults_do_not_follow_the_phone_dark_mode() {
        val defaults = block(source(ZASHI_QR), "object QrCodeDefaults")
        assertTrue(!defaults.contains("orDark"), "QrCodeDefaults must not use orDark (it follows the phone, not ZCHAT)")
        assertTrue(!defaults.contains("isSystemInDarkTheme"), "QrCodeDefaults must not read the phone's dark mode")
        val flat = squash(defaults)
        assertTrue(
            Regex("""foreground: Color = QrCodePalette\.Dark\b""").containsMatchIn(flat),
            "the default foreground must be QrCodePalette.Dark"
        )
        assertTrue(
            Regex("""background: Color = QrCodePalette\.Light\b""").containsMatchIn(flat),
            "the default background must be QrCodePalette.Light"
        )
    }

    @Test
    fun palette_is_dark_on_light_with_contrast_15() {
        val palette =
            try {
                Class.forName(PALETTE)
            } catch (_: ClassNotFoundException) {
                fail("QrCodePalette not found ($PALETTE)")
            }
        val dark = palette.getField("DARK_ARGB").getInt(null)
        val light = palette.getField("LIGHT_ARGB").getInt(null)
        assertTrue(dark ushr ALPHA_SHIFT == OPAQUE, "the dark colour must be opaque")
        assertTrue(light ushr ALPHA_SHIFT == OPAQUE, "the light colour must be opaque")
        val lDark = luminance(dark)
        val lLight = luminance(light)
        assertTrue(lDark < lLight, "the modules must be darker than the background")
        val contrast = (lLight + WCAG_FLARE) / (lDark + WCAG_FLARE)
        assertTrue(contrast >= MIN_CONTRAST, "QR contrast is %.2f:1; at least 15:1 is required".format(contrast))
    }

    /** Source guard: no screen hands theme colours to a QR. */
    @Test
    fun no_screen_passes_theme_colours_to_a_qr() {
        val allowed = Regex("""^(Color\.Black|Color\.White|QrCodePalette\.\w+)$""")
        val calls =
            productKotlinFiles.flatMap { (path, text) ->
                callArguments(text, "QrCodeDefaults.colors(").map { path to it }
            }
        assertTrue(calls.isNotEmpty(), "no QrCodeDefaults.colors( call found: the guard reads the wrong files")
        val wrong =
            calls.flatMap { (path, arguments) ->
                val problems = mutableListOf<String>()
                if (arguments.contains("orDark")) problems += "$path: a QR colour uses orDark"
                topLevelArguments(arguments).forEachIndexed { index, argument ->
                    val named = Regex("""^(\w+)\s*=\s*(.+)$""", RegexOption.DOT_MATCHES_ALL).find(argument)
                    val name = named?.groupValues?.get(1) ?: POSITIONAL.getOrNull(index)
                    val value = squash(named?.groupValues?.get(2) ?: argument).trim()
                    if ((name == "foreground" || name == "background") && !allowed.matches(value)) {
                        problems += "$path: $name = $value"
                    }
                }
                problems
            }
        assertTrue(wrong.isEmpty(), "a QR must be drawn with the fixed palette:\n" + wrong.joinToString("\n"))

        val share = squash(source(SHARE_QR))
        assertTrue(
            share.contains("background = Color.White.toArgb()") ||
                share.contains("background = QrCodePalette.LIGHT_ARGB"),
            "the shared QR PNG must have a white background"
        )
        assertTrue(
            share.contains("foreground = Color.Black.toArgb()") ||
                share.contains("foreground = QrCodePalette.DARK_ARGB"),
            "the shared QR PNG must have black modules"
        )
    }

    /** Source guard: the card behind the bitmap and the ring behind the logo are the light colour. */
    @Test
    fun the_card_and_logo_ring_are_the_light_colour() {
        val body = squash(block(source(ZASHI_QR), "private fun ZashiQrInternal("))
        val surface = callArguments(body, "Surface(").firstOrNull() ?: fail("ZashiQrInternal has no Surface(")
        assertTrue(
            topLevelArguments(surface).any { squash(it).trim() == "color = colors.background" },
            "the QR card must use color = colors.background"
        )
        assertTrue(
            body.contains(".background(Color.White)") || body.contains(".background(QrCodePalette.Light)"),
            "the ring behind the centre logo must be white"
        )
    }

    /** Source guard: nobody builds QrCodeColors directly, so the colours guard above cannot be sidestepped. */
    @Test
    fun no_qr_colours_built_outside_the_defaults() {
        val outside =
            productKotlinFiles.flatMap { (path, text) ->
                Regex("""(?<![\w.])QrCodeColors\(""")
                    .findAll(text)
                    .filter { m ->
                        !text.substring(maxOf(0, m.range.first - CLASS_LOOKBACK), m.range.first).endsWith("class ")
                    }.filter { !path.endsWith(ZASHI_QR) }
                    .map { path }
                    .toList()
            }
        assertTrue(outside.isEmpty(), "QrCodeColors( is built outside ZashiQr.kt:\n" + outside.joinToString("\n"))
    }

    private fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and CHANNEL_MASK) / CHANNEL_MAX
            return if (c <= SRGB_KNEE) c / SRGB_LINEAR_DIVISOR else ((c + SRGB_OFFSET) / SRGB_SCALE).pow(SRGB_GAMMA)
        }
        return RED_WEIGHT * channel(RED_SHIFT) + GREEN_WEIGHT * channel(GREEN_SHIFT) + BLUE_WEIGHT * channel(0)
    }

    private companion object {
        const val PALETTE = "co.electriccoin.zcash.ui.design.util.QrCodePalette"
        val POSITIONAL = listOf("background", "foreground", "border")
        const val ALPHA_SHIFT = 24
        const val OPAQUE = 0xFF
        const val CHANNEL_MASK = 0xFF
        const val CHANNEL_MAX = 255.0
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val SRGB_KNEE = 0.04045
        const val SRGB_LINEAR_DIVISOR = 12.92
        const val SRGB_OFFSET = 0.055
        const val SRGB_SCALE = 1.055
        const val SRGB_GAMMA = 2.4
        const val RED_WEIGHT = 0.2126
        const val GREEN_WEIGHT = 0.7152
        const val BLUE_WEIGHT = 0.0722
        const val WCAG_FLARE = 0.05
        const val MIN_CONTRAST = 15.0
        const val CLASS_LOOKBACK = 16
    }
}
