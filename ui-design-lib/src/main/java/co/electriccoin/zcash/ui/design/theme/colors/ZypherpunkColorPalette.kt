@file:Suppress("ObjectPropertyName", "ObjectPropertyNaming", "MagicNumber")

package co.electriccoin.zcash.ui.design.theme.colors

/**
 * NIGHTWIRE theme color palette — Cypherpunk Edition
 *
 * Deep dark backgrounds with cyan/magenta/green accents.
 * Professional and readable while maintaining cyberpunk edge.
 *
 * Primary: Electric Cyan (#00E5FF)
 * Secondary: Hot Magenta (#FF2D78)
 * Background: Near-black navy (#080B12)
 * Success: Neon Green (#00FF88)
 */
internal object ZypherpunkBase {
    val Background = NightwireColors.BgBase              // 0xFF080B12
    val BackgroundCircuit = NightwireColors.BgSurface    // 0xFF0F1420
    val Surface = NightwireColors.BgSurface              // 0xFF0F1420
    val SurfaceElevated = NightwireColors.BgElevated     // 0xFF161B2B
    val Cyan = NightwireColors.AccentPrimary             // 0xFF00E5FF
    val CyanGlow = NightwireColors.AccentPrimaryGlow     // 0x3300E5FF
    val CyanDark = NightwireColors.AccentPrimaryDim      // 0xFF00A3B4
    val Magenta = NightwireColors.AccentSecondary        // 0xFFFF2D78
    val MagentaGlow = NightwireColors.AccentSecondaryGlow // 0x33FF2D78
    val MagentaHot = NightwireColors.AccentSecondaryDim  // 0xFFCC2460
    val NeonGreen = NightwireColors.AccentSuccess        // 0xFF00FF88
    val Text = NightwireColors.TextPrimary               // 0xFFE8EDF5
    val TextSecondary = NightwireColors.TextSecondary    // 0xFF7A849B
    val TextTertiary = NightwireColors.TextTertiary      // 0xFF464F66
    val TextInverse = NightwireColors.TextOnAccent       // 0xFF080B12
    val TextTransmission = NightwireColors.AccentSuccess // 0xFF00FF88
    val bgSecondary = NightwireColors.BgSurface          // 0xFF0F1420
    val bgTertiary = NightwireColors.BgElevated          // 0xFF161B2B
}
