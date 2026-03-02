@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.design.theme.colors

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * NIGHTWIRE Color Palette — Cypherpunk Theme for ZChat
 *
 * Deep dark backgrounds with cyan/magenta/green accents.
 * Professional and readable while maintaining cyberpunk edge.
 */
object NightwireColors {
    // Background layers (darkest to lightest)
    val BgBase = Color(0xFF080B12)
    val BgSurface = Color(0xFF0F1420)
    val BgElevated = Color(0xFF161B2B)
    val BgInput = Color(0xFF1A2035)
    val BgHover = Color(0xFF1E2640)

    // Accent — Primary (Cyan)
    val AccentPrimary = Color(0xFF00E5FF)
    val AccentPrimaryDim = Color(0xFF00A3B4)
    val AccentPrimaryGlow = Color(0x3300E5FF)
    val AccentPrimaryBg = Color(0x0D00E5FF)

    // Accent — Secondary (Magenta)
    val AccentSecondary = Color(0xFFFF2D78)
    val AccentSecondaryDim = Color(0xFFCC2460)
    val AccentSecondaryGlow = Color(0x33FF2D78)

    // Accent — Success (Green)
    val AccentSuccess = Color(0xFF00FF88)
    val AccentSuccessDim = Color(0xFF00CC6A)

    // Semantic
    val ColorWarning = Color(0xFFFFB800)
    val ColorDanger = Color(0xFFFF3344)
    val ColorInfo = Color(0xFF00E5FF)

    // Text
    val TextPrimary = Color(0xFFE8EDF5)
    val TextSecondary = Color(0xFF7A849B)
    val TextTertiary = Color(0xFF464F66)
    val TextOnAccent = Color(0xFF080B12)

    // Chat bubbles
    val BubbleSent = Color(0xFF0A2A3A)
    val BubbleSentBorder = Color(0x1A00E5FF)
    val BubbleReceived = Color(0xFF161B2B)
    val BubbleReceivedBorder = Color(0x0AFFFFFF)

    // Special
    val BubblePayment = Color(0xFF0A2A1A)
    val BubblePaymentBorder = Color(0x2600FF88)
    val BubbleSystem = Color(0x00000000)
    val DestroyRed = Color(0xFFFF1A1A)

    // Borders
    val BorderDefault = Color(0x0FFFFFFF)
    val BorderActive = Color(0x4400E5FF)

    // Shape constants
    val RadiusCard = 8.dp
    val RadiusModal = 12.dp
    val RadiusBubble = 20.dp
    val RadiusBubbleTail = 4.dp
    val RadiusFull = 999.dp
    val RadiusButton = 8.dp
    val RadiusInput = 24.dp

    // Avatar color palette — deterministic per address for visual identity
    private val AvatarPalette = listOf(
        Color(0xFF00E5FF), // Cyan
        Color(0xFFFF2D78), // Magenta
        Color(0xFF00FF88), // Green
        Color(0xFFFFB800), // Amber
        Color(0xFF7C4DFF), // Purple
        Color(0xFFFF6E40), // Orange
        Color(0xFF64FFDA), // Teal
        Color(0xFFE040FB), // Pink
    )

    fun avatarColorForAddress(address: String): Color {
        val hash = address.fold(0) { acc, c -> acc * 31 + c.code }
        return AvatarPalette[(hash.toUInt() % AvatarPalette.size.toUInt()).toInt()]
    }
}
