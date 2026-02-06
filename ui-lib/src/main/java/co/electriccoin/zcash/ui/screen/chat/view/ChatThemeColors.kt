package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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
    val titleGradient: Brush      // Gradient for title
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
 * Cyberpunk theme colors
 */
val CyberpunkChatColors = ChatColors(
    primary = Color(0xFF00FFFF),            // Neon Cyan
    secondary = Color(0xFFFF00FF),           // Neon Magenta
    background = Color(0xFF1A0A2E),          // Deep Purple
    backgroundLight = Color(0xFF2A1A4E),     // Lighter Purple
    surface = Color(0xFF251540),
    textPrimary = Color(0xFFE0E0FF),
    textSecondary = Color(0xFFA0A0C0),
    outgoingBubble = Color(0xFF00DDDD),      // Cyan bubble
    incomingBubble = Color(0xFF880066),      // Magenta bubble
    fabBackground = Color(0xFF00FFFF),
    fabForeground = Color(0xFF1A0A2E),
    divider = Color(0xFF3A2560),
    error = Color(0xFFFF00FF),
    titleGradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFF00FFFF), Color(0xFFFF00FF))
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
    )
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
 * DEEP CYBER theme colors - Full cyberpunk with intense neon glow
 */
val DeepCyberChatColors = ChatColors(
    primary = Color(0xFF00FFFF),            // Full bright Cyan
    secondary = Color(0xFFFF00FF),           // Full bright Magenta
    background = Color(0xFF050510),          // Near-black
    backgroundLight = Color(0xFF12102A),     // Dark purple surface
    surface = Color(0xFF0D0B1A),
    textPrimary = Color(0xFFEEEEFF),         // Bright text
    textSecondary = Color(0xFFAAB0CC),
    outgoingBubble = Color(0xFF00DDDD),      // Cyan bubble
    incomingBubble = Color(0xFF2A1A4E),      // Dark purple bubble
    fabBackground = Color(0xFF00FFFF),
    fabForeground = Color(0xFF050510),
    divider = Color(0xFF1E1438),
    error = Color(0xFFFF00FF),
    titleGradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFF00FFFF), Color(0xFFFF00FF))
    )
)

/**
 * Composition local for chat colors
 */
val LocalChatColors = compositionLocalOf { DefaultChatColors }

/**
 * Shared function to get chat colors based on ZashiColors.
 * This function can be used across all chat screens.
 */
@Composable
fun chatColors(): ChatColors {
    return CyberpunkChatColors
}
