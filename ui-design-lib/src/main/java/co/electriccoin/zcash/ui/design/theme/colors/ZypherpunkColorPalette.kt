@file:Suppress("ObjectPropertyName", "ObjectPropertyNaming", "MagicNumber")

package co.electriccoin.zcash.ui.design.theme.colors

import androidx.compose.ui.graphics.Color

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

internal object ZypherpunkCyan {
    val `50` = Color(0xFFE0F7FA)
    val `100` = Color(0xFFB2EBF2)
    val `200` = Color(0xFF80DEEA)
    val `300` = Color(0xFF4DD0E1)
    val `400` = NightwireColors.AccentPrimary             // 0xFF00E5FF — primary
    val `500` = NightwireColors.AccentPrimaryDim           // 0xFF00A3B4
    val `600` = Color(0xFF008BA3)
    val `700` = Color(0xFF006978)
    val `800` = Color(0xFF004D5A)
    val `900` = Color(0xFF00363F)
    val `950` = Color(0xFF001F26)
}

internal object ZypherpunkMagenta {
    val `50` = Color(0xFFFFE8F0)
    val `100` = Color(0xFFFFC0D6)
    val `200` = Color(0xFFFF8AAF)
    val `300` = Color(0xFFFF5A8E)
    val `400` = NightwireColors.AccentSecondary           // 0xFFFF2D78 — primary
    val `500` = NightwireColors.AccentSecondaryDim        // 0xFFCC2460
    val `600` = Color(0xFFA01D4D)
    val `700` = Color(0xFF7A163B)
    val `800` = Color(0xFF540F29)
    val `900` = Color(0xFF360A1B)
    val `950` = Color(0xFF1A050D)
}

internal object ZypherpunkPurple {
    val `50` = Color(0xFFE8EDF5)  // matches TextPrimary for top end
    val `100` = Color(0xFFC0C8D8)
    val `200` = Color(0xFF9AA5BE)
    val `300` = NightwireColors.TextSecondary              // 0xFF7A849B
    val `400` = NightwireColors.TextTertiary               // 0xFF464F66
    val `500` = Color(0xFF343C52)
    val `600` = Color(0xFF2A3045)
    val `700` = Color(0xFF1E2640)  // BgHover
    val `800` = Color(0xFF161B2B)  // BgElevated
    val `900` = Color(0xFF0F1420)  // BgSurface
    val `950` = Color(0xFF080B12)  // BgBase
}

internal object ZypherpunkShades {
    val `00dp` = NightwireColors.BgBase          // 0xFF080B12
    val `01dp` = Color(0xFF0A0E17)
    val `02dp` = Color(0xFF0C101C)
    val `03dp` = Color(0xFF0E1321)
    val `04dp` = NightwireColors.BgSurface       // 0xFF0F1420
    val `06dp` = NightwireColors.BgElevated      // 0xFF161B2B
    val `08dp` = NightwireColors.BgInput         // 0xFF1A2035
    val `12dp` = NightwireColors.BgHover         // 0xFF1E2640
    val `16dp` = Color(0xFF242B4A)
    val `24dp` = Color(0xFF2A3255)
}

// Special accent colors for NIGHTWIRE theme
internal object ZypherpunkAccent {
    val TransmissionGreen = NightwireColors.AccentSuccess  // 0xFF00FF88
    val NeonYellow = NightwireColors.ColorWarning          // 0xFFFFB800
    val CircuitTrace = Color(0xFF0A2A3A)                   // BubbleSent-like
    val CircuitNode = NightwireColors.AccentSuccess         // 0xFF00FF88
    val GlowAura = NightwireColors.AccentPrimaryGlow       // 0x3300E5FF
}
