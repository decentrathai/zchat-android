@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.colors.NightwireLightColors
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors

/**
 * Chat-specific theme colors that can be switched between themes
 */
data class ChatColors(
    val primary: Color,           // Main accent color (cyan in default)
    val secondary: Color,         // Secondary accent (green in default)
    val background: Color,        // Dark background
    val backgroundLight: Color,   // Lighter background for cards
    val surface: Color,           // Surface color for UI elements
    val textPrimary: Color,       // Main text color
    val textSecondary: Color,     // Secondary text color
    val outgoingBubble: Color,    // Outgoing message bubble
    val incomingBubble: Color,    // Incoming message bubble
    val fabBackground: Color,     // FAB background
    val fabForeground: Color,     // FAB icon color
    val divider: Color,           // Divider color
    val error: Color,             // Error/danger color
    val titleGradient: Brush,     // Gradient for title
    // Nightwire extended fields
    val bubbleSentBorder: Color = Color.Transparent,
    val bubbleReceivedBorder: Color = Color.Transparent,
    val bubblePayment: Color = Color.Transparent,
    val bubblePaymentBorder: Color = Color.Transparent,
    val accentSecondary: Color = Color.Transparent,
    val textTertiary: Color = Color.Gray,
    val bgInput: Color = Color.DarkGray,
    val bgElevated: Color = Color.DarkGray,
    val borderDefault: Color = Color.Transparent,
    val borderActive: Color = Color.Transparent,
    val destroyRed: Color = Color.Red,
    val warning: Color = Color.Yellow,
    val success: Color = Color.Green,
    val accentPrimaryGlow: Color = Color.Transparent,
    val bgHover: Color = Color.DarkGray,
    val textOnAccent: Color = Color.White,
    val isLight: Boolean = false,
)

/**
 * Default ZCHAT brand colors (current theme)
 */
val DefaultChatColors = ChatColors(
    primary = Color(0xFF00D9FF),           // Cyan
    secondary = Color(0xFF00E676),          // Green
    background = Color(0xFF0D1B2A),         // Navy
    backgroundLight = Color(0xFF1B2838),    // Navy Light
    surface = Color(0xFF1B2838),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFB0B0B0),
    outgoingBubble = Color(0xFF00D9FF),
    incomingBubble = Color(0xFF1B2838),
    fabBackground = Color(0xFF00D9FF),
    fabForeground = Color(0xFF0D1B2A),
    divider = Color(0xFF2A3F54),
    error = Color(0xFFFF5252),
    titleGradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFF00D9FF), Color(0xFF00E676))
    )
)

/**
 * Light theme colors (minimalist)
 */
val LightChatColors = ChatColors(
    primary = Color(0xFF0088CC),
    secondary = Color(0xFF00AA66),
    background = Color(0xFFF5F5F5),
    backgroundLight = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF1A1A1A),
    textSecondary = Color(0xFF666666),
    outgoingBubble = Color(0xFF0088CC),
    incomingBubble = Color(0xFFE8E8E8),
    fabBackground = Color(0xFF0088CC),
    fabForeground = Color(0xFFFFFFFF),
    divider = Color(0xFFE0E0E0),
    error = Color(0xFFD32F2F),
    titleGradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFF0088CC), Color(0xFF00AA66))
    ),
    // Extended fields MUST be specified for a LIGHT palette — otherwise they fall back to the dark
    // data-class defaults (bgInput/bgElevated/bgHover = DarkGray, textTertiary = Gray), which rendered
    // the composer + elevated surfaces DARK-on-light (dark-on-dark text) across the whole chat UI.
    textTertiary = Color(0xFF8A8A8A),
    bgInput = Color(0xFFEFEFEF),
    bgElevated = Color(0xFFFFFFFF),
    bgHover = Color(0xFFF0F0F0),
    borderDefault = Color(0xFFE0E0E0),
    borderActive = Color(0xFF0088CC),
    destroyRed = Color(0xFFD32F2F),
    warning = Color(0xFFB26A00),
    success = Color(0xFF1B8A4C),
    textOnAccent = Color(0xFFFFFFFF),
    accentPrimaryGlow = Color(0xFF0088CC).copy(alpha = 0.2f),
    isLight = true,
)

/**
 * Dark theme colors (minimalist)
 */
val DarkChatColors = ChatColors(
    primary = Color(0xFF00D9FF),
    secondary = Color(0xFF00E676),
    background = Color(0xFF121212),
    backgroundLight = Color(0xFF1E1E1E),
    surface = Color(0xFF1E1E1E),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFB0B0B0),
    outgoingBubble = Color(0xFF00D9FF),
    incomingBubble = Color(0xFF2A2A2A),
    fabBackground = Color(0xFF00D9FF),
    fabForeground = Color(0xFF121212),
    divider = Color(0xFF333333),
    error = Color(0xFFFF5252),
    titleGradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFF00D9FF), Color(0xFF00E676))
    )
)

/**
 * NIGHTWIRE theme colors — Cypherpunk Edition
 * Deep dark backgrounds with cyan/magenta/green accents.
 */
val ZypherpunkChatColors = ChatColors(
    primary = NightwireColors.AccentPrimary,
    secondary = NightwireColors.AccentSuccess,
    background = NightwireColors.BgBase,
    backgroundLight = NightwireColors.BgElevated,
    surface = NightwireColors.BgSurface,
    textPrimary = NightwireColors.TextPrimary,
    textSecondary = NightwireColors.TextSecondary,
    outgoingBubble = NightwireColors.BubbleSent,
    incomingBubble = NightwireColors.BubbleReceived,
    fabBackground = NightwireColors.AccentPrimary,
    fabForeground = NightwireColors.TextOnAccent,
    divider = NightwireColors.BorderDefault,
    error = NightwireColors.ColorDanger,
    titleGradient = Brush.horizontalGradient(
        colors = listOf(NightwireColors.AccentPrimary, NightwireColors.AccentSuccess)
    ),
    // Nightwire extended fields
    bubbleSentBorder = NightwireColors.BubbleSentBorder,
    bubbleReceivedBorder = NightwireColors.BubbleReceivedBorder,
    bubblePayment = NightwireColors.BubblePayment,
    bubblePaymentBorder = NightwireColors.BubblePaymentBorder,
    accentSecondary = NightwireColors.AccentSecondary,
    textTertiary = NightwireColors.TextTertiary,
    bgInput = NightwireColors.BgInput,
    bgElevated = NightwireColors.BgElevated,
    borderDefault = NightwireColors.BorderDefault,
    borderActive = NightwireColors.BorderActive,
    destroyRed = NightwireColors.DestroyRed,
    warning = NightwireColors.ColorWarning,
    success = NightwireColors.AccentSuccess,
    accentPrimaryGlow = NightwireColors.AccentPrimaryGlow,
    bgHover = NightwireColors.BgHover,
    textOnAccent = NightwireColors.TextOnAccent,
    isLight = false,
)

/**
 * NIGHTWIRE LIGHT — Daylight Edition chat colors.
 * Bone paper background, teal-cyan/garnet/forest-green accents.
 */
val NightwireLightChatColors = ChatColors(
    primary = NightwireLightColors.AccentPrimary,
    secondary = NightwireLightColors.AccentSuccess,
    background = NightwireLightColors.BgBase,
    backgroundLight = NightwireLightColors.BgElevated,
    surface = NightwireLightColors.BgSurface,
    textPrimary = NightwireLightColors.TextPrimary,
    textSecondary = NightwireLightColors.TextSecondary,
    outgoingBubble = NightwireLightColors.BubbleSent,
    incomingBubble = NightwireLightColors.BubbleReceived,
    fabBackground = NightwireLightColors.AccentPrimary,
    fabForeground = NightwireLightColors.TextOnAccent,
    divider = NightwireLightColors.BorderDefault,
    error = NightwireLightColors.ColorDanger,
    titleGradient = Brush.horizontalGradient(
        colors = listOf(NightwireLightColors.AccentPrimary, NightwireLightColors.AccentSuccess)
    ),
    bubbleSentBorder = NightwireLightColors.BubbleSentBorder,
    bubbleReceivedBorder = NightwireLightColors.BubbleReceivedBorder,
    bubblePayment = NightwireLightColors.BubblePayment,
    bubblePaymentBorder = NightwireLightColors.BubblePaymentBorder,
    accentSecondary = NightwireLightColors.AccentSecondary,
    textTertiary = NightwireLightColors.TextTertiary,
    bgInput = NightwireLightColors.BgInput,
    bgElevated = NightwireLightColors.BgElevated,
    borderDefault = NightwireLightColors.BorderDefault,
    borderActive = NightwireLightColors.BorderActive,
    destroyRed = NightwireLightColors.DestroyRed,
    warning = NightwireLightColors.ColorWarning,
    success = NightwireLightColors.AccentSuccess,
    accentPrimaryGlow = NightwireLightColors.AccentPrimaryGlow,
    bgHover = NightwireLightColors.BgHover,
    textOnAccent = NightwireLightColors.TextOnAccent,
    isLight = true,
)

/**
 * Composition local for chat colors
 */
val LocalChatColors = compositionLocalOf { DefaultChatColors }

/**
 * Shared function to get chat colors based on active ZashiColors theme.
 * Dynamically determines the correct chat color set by checking the
 * current theme's background color.
 */
@Composable
fun chatColors(): ChatColors {
    val bgColor = ZashiColors.Surfaces.bgPrimary
    return when (bgColor) {
        NightwireColors.BgBase -> ZypherpunkChatColors           // Nightwire dark (0xFF080B12)
        NightwireLightColors.BgBase -> NightwireLightChatColors  // Nightwire daylight (0xFFF4F0E6)
        Color(0xFFF5F5F5), Color(0xFFFFFFFF) -> LightChatColors
        Color(0xFF121212) -> DarkChatColors
        else -> DarkChatColors  // Fallback to dark
    }
}

/**
 * Theme-aware avatar color picker. Reads the active palette and dispatches to the
 * dark or light Nightwire avatar set so colors match the surface lightness.
 */
@Composable
fun avatarColorForAddress(address: String): Color {
    val bgColor = ZashiColors.Surfaces.bgPrimary
    return if (bgColor == NightwireLightColors.BgBase) {
        NightwireLightColors.avatarColorForAddress(address)
    } else {
        NightwireColors.avatarColorForAddress(address)
    }
}
