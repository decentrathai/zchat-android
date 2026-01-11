@file:Suppress("ObjectPropertyName", "ObjectPropertyNaming", "MagicNumber")

package co.electriccoin.zcash.ui.design.theme.colors

import androidx.compose.ui.graphics.Color

/**
 * Cyberpunk theme color palette
 * Primary: Cyan (#00FFFF)
 * Secondary: Magenta (#FF00FF)
 * Background: Deep Purple (#1A0A2E)
 */
internal object CyberpunkBase {
    val Background = Color(0xFF1A0A2E)      // Deep purple-black
    val BackgroundLight = Color(0xFF2A1A4E) // Lighter purple
    val Surface = Color(0xFF251540)          // Card/surface color
    val SurfaceLight = Color(0xFF3A2560)     // Lighter surface
    val Cyan = Color(0xFF00FFFF)             // Primary neon cyan
    val CyanDark = Color(0xFF0088AA)         // Darker cyan
    val Magenta = Color(0xFFFF00FF)          // Secondary neon magenta
    val MagentaDark = Color(0xFF880066)      // Darker magenta
    val Text = Color(0xFFE0E0FF)             // Light text
    val TextSecondary = Color(0xFFA0A0C0)    // Secondary text
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
