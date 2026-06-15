@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.design.theme.colors

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * NIGHTWIRE — Daylight Edition
 *
 * Cypherpunk in daylight: bone paper background, ink text, teal-cyan, garnet, forest green.
 * NOT an inversion of NightwireColors — separately curated for AA contrast.
 *
 * Two values diverge from the design handoff:
 *   - BgInput #F8F5EC (handoff said #FFFFFF — same as BgSurface, making unfocused inputs invisible).
 *   - BubbleReceivedBorder bumped to 22% alpha (handoff 8% gave 1.05:1 surface contrast on bgBase).
 */
object NightwireLightColors {
    // Background layers
    val BgBase = Color(0xFFF4F0E6)      // bone paper
    val BgSurface = Color(0xFFFFFFFF)
    val BgElevated = Color(0xFFFAF6EC)
    val BgInput = Color(0xFFF8F5EC)     // diverges from handoff to stay distinct from BgSurface
    val BgHover = Color(0xFFEBE6D8)

    // Accent — Primary (teal-cyan)
    val AccentPrimary = Color(0xFF006B78)
    val AccentPrimaryDim = Color(0xFF004E58)
    val AccentPrimaryGlow = Color(0x24006B78)
    val AccentPrimaryBg = Color(0x0F006B78)

    // Accent — Secondary (garnet)
    val AccentSecondary = Color(0xFFB11458)
    val AccentSecondaryDim = Color(0xFF7C0E3D)
    val AccentSecondaryGlow = Color(0x24B11458)

    // Accent — Success (forest)
    val AccentSuccess = Color(0xFF0E7A3F)
    val AccentSuccessDim = Color(0xFF07582D)

    // Semantic
    val ColorWarning = Color(0xFF9A6A00)
    val ColorDanger = Color(0xFFB91C2E)
    val ColorInfo = Color(0xFF006B78)

    // Text
    val TextPrimary = Color(0xFF0B0E16)
    val TextSecondary = Color(0xFF4A5168)
    val TextTertiary = Color(0xFF8B92A6)
    val TextOnAccent = Color(0xFFFFFFFF)

    // Chat bubbles
    val BubbleSent = Color(0xFF006B78)
    val BubbleSentBorder = Color(0x33006B78)
    val BubbleReceived = Color(0xFFFFFFFF)
    val BubbleReceivedBorder = Color(0x380B0E16)  // 22% ink — diverges from handoff 8%

    val BubblePayment = Color(0xFFE7F3EC)
    val BubblePaymentBorder = Color(0x2E0E7A3F)
    val BubbleSystem = Color(0x00000000)
    val DestroyRed = Color(0xFF8F0F1A)            // distinct from ColorDanger so semantics differ

    // Borders
    val BorderDefault = Color(0x140B0E16)
    val BorderActive = Color(0x66006B78)

    // Shape constants (same as dark)
    val RadiusCard = 8.dp
    val RadiusModal = 12.dp
    val RadiusBubble = 20.dp
    val RadiusBubbleTail = 4.dp
    val RadiusFull = 999.dp
    val RadiusButton = 8.dp
    val RadiusInput = 24.dp

    // Avatar palette — slightly desaturated for light bg
    private val AvatarPalette = listOf(
        Color(0xFF006B78), Color(0xFFB11458), Color(0xFF0E7A3F),
        Color(0xFF9A6A00), Color(0xFF5230B0), Color(0xFFC25030),
        Color(0xFF208070), Color(0xFFA02BAA),
    )

    fun avatarColorForAddress(address: String): Color {
        val hash = address.fold(0) { acc, c -> acc * 31 + c.code }
        return AvatarPalette[(hash.toUInt() % AvatarPalette.size.toUInt()).toInt()]
    }
}
