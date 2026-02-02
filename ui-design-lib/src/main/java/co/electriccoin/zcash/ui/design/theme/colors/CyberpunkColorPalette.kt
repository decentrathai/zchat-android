@file:Suppress("ObjectPropertyName", "ObjectPropertyNaming", "MagicNumber")

package co.electriccoin.zcash.ui.design.theme.colors

import androidx.compose.ui.graphics.Color

/**
 * Cyberpunk theme color palette
 * Primary: Cyan (#00FFFF)
 * Secondary: Magenta (#FF00FF)
 * Background: Deep Purple-Black (#0D0B1A)
 *
 * Color distribution: 60% backgrounds, 30% surfaces, 10% accents
 */
internal object CyberpunkBase {
    // === Backgrounds (60% of UI) ===
    val bgDeep = Color(0xFF050510)           // Near-black for splash
    val bgPrimary = Color(0xFF0D0B1A)        // Main background
    val bgSecondary = Color(0xFF1A1530)      // Cards
    val bgTertiary = Color(0xFF251E45)       // Elevated surfaces

    // Legacy names (mapped to new values)
    val Background = bgPrimary
    val BackgroundLight = bgSecondary
    val Surface = bgSecondary
    val SurfaceLight = bgTertiary

    // === Accents (10% of UI) ===
    val Cyan = Color(0xFF00FFFF)             // Primary neon cyan
    val CyanDark = Color(0xFF0088AA)         // Darker cyan
    val CyanGlow = Color(0x6600FFFF)         // 40% opacity for glow
    val Magenta = Color(0xFFFF00FF)          // Secondary neon magenta
    val MagentaDark = Color(0xFF880066)      // Darker magenta
    val MagentaGlow = Color(0x66FF00FF)      // 40% opacity for glow

    // Accent aliases
    val accentCyan = Cyan
    val accentCyanGlow = CyanGlow
    val accentMagenta = Magenta
    val accentMagentaGlow = MagentaGlow

    // === Text Colors ===
    val Text = Color(0xFFE8E8FF)             // Primary text (NOT pure white)
    val TextSecondary = Color(0xFFA8A8CC)    // Secondary text
    val TextTertiary = Color(0xFF6868A0)     // Muted/disabled text
    val TextInverse = Color(0xFF050510)      // Text on bright backgrounds

    // Semantic alias for textPrimary (others use PascalCase versions directly)
    val textPrimary = Text
}

internal object CyberpunkCyan {
    val `50` = Color(0xFFE0FFFF)
    val `100` = Color(0xFFB0FFFF)
    val `200` = Color(0xFF80FFFF)
    val `300` = Color(0xFF40FFFF)
    val `400` = Color(0xFF00FFFF)  // Primary
    val `500` = Color(0xFF00DDDD)
    val `600` = Color(0xFF00BBBB)
    val `700` = Color(0xFF009999)
    val `800` = Color(0xFF007777)
    val `900` = Color(0xFF005555)
    val `950` = Color(0xFF003333)
}

internal object CyberpunkMagenta {
    val `50` = Color(0xFFFFE0FF)
    val `100` = Color(0xFFFFB0FF)
    val `200` = Color(0xFFFF80FF)
    val `300` = Color(0xFFFF40FF)
    val `400` = Color(0xFFFF00FF)  // Primary
    val `500` = Color(0xFFDD00DD)
    val `600` = Color(0xFFBB00BB)
    val `700` = Color(0xFF990099)
    val `800` = Color(0xFF770077)
    val `900` = Color(0xFF550055)
    val `950` = Color(0xFF330033)
}

internal object CyberpunkPurple {
    val `50` = Color(0xFFE8E0FF)
    val `100` = Color(0xFFD0C0FF)
    val `200` = Color(0xFFB0A0E0)
    val `300` = Color(0xFF9080C0)
    val `400` = Color(0xFF7060A0)
    val `500` = Color(0xFF503080)
    val `600` = Color(0xFF402560)
    val `700` = Color(0xFF301A4E)
    val `800` = Color(0xFF251540)
    val `900` = Color(0xFF1A0A2E)
    val `950` = Color(0xFF0D0518)
}

internal object CyberpunkShades {
    val `00dp` = Color(0xFF1A0A2E)
    val `01dp` = Color(0xFF201235)
    val `02dp` = Color(0xFF24163A)
    val `03dp` = Color(0xFF28183E)
    val `04dp` = Color(0xFF2A1A42)
    val `06dp` = Color(0xFF301E48)
    val `08dp` = Color(0xFF34224C)
    val `12dp` = Color(0xFF3A2854)
    val `16dp` = Color(0xFF3E2C58)
    val `24dp` = Color(0xFF443260)
}
