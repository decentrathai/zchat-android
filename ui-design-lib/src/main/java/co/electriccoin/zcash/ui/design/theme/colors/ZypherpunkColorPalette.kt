@file:Suppress("ObjectPropertyName", "ObjectPropertyNaming", "MagicNumber")

package co.electriccoin.zcash.ui.design.theme.colors

import androidx.compose.ui.graphics.Color

/**
 * ZYPHERPUNK theme color palette
 * An ultra-cyberpunk theme with intensified neon colors,
 * circuit board aesthetics, and matrix-style design.
 *
 * Primary: Electric Cyan (#00FFFF / #39FF14 accents)
 * Secondary: Hot Magenta (#FF00FF / #FF0080)
 * Background: Near-black with purple tint (#0D0518)
 * Accents: Neon green for "transmission" headers
 */
internal object ZypherpunkBase {
    val Background = Color(0xFF050510)           // Near-black with slight purple
    val BackgroundCircuit = Color(0xFF0A0820)    // Circuit board pattern bg
    val Surface = Color(0xFF0D0B1A)              // Card/surface color
    val SurfaceElevated = Color(0xFF12102A)      // Elevated surface
    val Cyan = Color(0xFF00FFFF)                 // Primary neon cyan (full bright)
    val CyanGlow = Color(0xFF40FFFF)             // Cyan with glow effect
    val CyanDark = Color(0xFF00AAAA)             // Darker cyan
    val Magenta = Color(0xFFFF00FF)              // Hot magenta (full bright)
    val MagentaGlow = Color(0xFFFF40FF)          // Magenta with glow
    val MagentaHot = Color(0xFFFF0080)           // Hot pink variant
    val NeonGreen = Color(0xFF39FF14)            // Matrix green for headers
    val Text = Color(0xFFEEEEFF)                 // Bright light text
    val TextSecondary = Color(0xFFAAB0CC)        // Secondary text
    val TextTertiary = Color(0xFF6868A0)         // Muted/disabled text
    val TextInverse = Color(0xFF050510)          // Text on bright backgrounds
    val TextTransmission = Color(0xFF00FF88)     // Transmission header text
    val bgSecondary = Color(0xFF1A1530)          // Cards/surfaces
    val bgTertiary = Color(0xFF251E45)           // Elevated surfaces
}

internal object ZypherpunkCyan {
    val `50` = Color(0xFFE8FFFF)
    val `100` = Color(0xFFC0FFFF)
    val `200` = Color(0xFF90FFFF)
    val `300` = Color(0xFF60FFFF)
    val `400` = Color(0xFF00FFFF)   // Full bright primary
    val `500` = Color(0xFF00E0E0)
    val `600` = Color(0xFF00C0C0)
    val `700` = Color(0xFF00A0A0)
    val `800` = Color(0xFF008080)
    val `900` = Color(0xFF006060)
    val `950` = Color(0xFF004040)
}

internal object ZypherpunkMagenta {
    val `50` = Color(0xFFFFE8FF)
    val `100` = Color(0xFFFFC0FF)
    val `200` = Color(0xFFFF90FF)
    val `300` = Color(0xFFFF60FF)
    val `400` = Color(0xFFFF00FF)   // Full bright primary
    val `500` = Color(0xFFE000E0)
    val `600` = Color(0xFFC000C0)
    val `700` = Color(0xFFA000A0)
    val `800` = Color(0xFF800080)
    val `900` = Color(0xFF600060)
    val `950` = Color(0xFF400040)
}

internal object ZypherpunkPurple {
    val `50` = Color(0xFFE8E0FF)
    val `100` = Color(0xFFD0C0FF)
    val `200` = Color(0xFFB0A0E0)
    val `300` = Color(0xFF8878C0)
    val `400` = Color(0xFF6658A0)
    val `500` = Color(0xFF443878)
    val `600` = Color(0xFF2E2058)
    val `700` = Color(0xFF1E1438)
    val `800` = Color(0xFF140E28)
    val `900` = Color(0xFF0D0818)
    val `950` = Color(0xFF06040C)
}

internal object ZypherpunkShades {
    val `00dp` = Color(0xFF050510)
    val `01dp` = Color(0xFF080815)
    val `02dp` = Color(0xFF0A0A1A)
    val `03dp` = Color(0xFF0C0C1E)
    val `04dp` = Color(0xFF0E0E22)
    val `06dp` = Color(0xFF12122A)
    val `08dp` = Color(0xFF161632)
    val `12dp` = Color(0xFF1C1C3A)
    val `16dp` = Color(0xFF202042)
    val `24dp` = Color(0xFF28284A)
}

// Special accent colors for ZYPHERPUNK theme
internal object ZypherpunkAccent {
    val TransmissionGreen = Color(0xFF00FF88)    // For "TRANSMISSION" headers
    val NeonYellow = Color(0xFFFFFF00)           // Alert/warning color
    val CircuitTrace = Color(0xFF1A4050)         // Circuit board trace color
    val CircuitNode = Color(0xFF00FFAA)          // Circuit node points
    val GlowAura = Color(0x4000FFFF)             // Semi-transparent glow
}
