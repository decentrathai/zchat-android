@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.design.component.cyberpunk

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.colors.CyberpunkBase
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

/**
 * A glassmorphism card component for the cyberpunk theme
 * Creates a frosted glass effect with subtle cyan tint and border
 *
 * @param modifier Modifier for the card
 * @param hazeState The HazeState for blur coordination (should be shared with parent's haze modifier)
 * @param cornerRadius Corner radius for the card
 * @param borderWidth Width of the glowing border
 * @param borderAlpha Opacity of the border (0.0 to 1.0)
 * @param tintAlpha Opacity of the cyan tint overlay
 * @param blurRadius The blur radius for the glass effect
 * @param contentPadding Internal padding for the content
 * @param content The content to display inside the card
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    hazeState: HazeState = remember { HazeState() },
    cornerRadius: Dp = 16.dp,
    borderWidth: Dp = 1.dp,
    borderAlpha: Float = 0.2f,
    tintAlpha: Float = 0.12f,
    blurRadius: Dp = 20.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .hazeChild(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = CyberpunkBase.bgSecondary.copy(alpha = 0.4f),
                    tint = HazeTint(CyberpunkBase.Cyan.copy(alpha = tintAlpha)),
                    blurRadius = blurRadius
                )
            )
            .clip(shape)
            .border(
                width = borderWidth,
                color = CyberpunkBase.Cyan.copy(alpha = borderAlpha),
                shape = shape
            )
            .padding(contentPadding),
        content = content
    )
}

/**
 * A glassmorphism card with magenta accent instead of cyan
 * Use for warnings, destructive actions, or secondary emphasis
 */
@Composable
fun GlassCardMagenta(
    modifier: Modifier = Modifier,
    hazeState: HazeState = remember { HazeState() },
    cornerRadius: Dp = 16.dp,
    borderWidth: Dp = 1.dp,
    borderAlpha: Float = 0.2f,
    tintAlpha: Float = 0.12f,
    blurRadius: Dp = 20.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .hazeChild(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = CyberpunkBase.bgSecondary.copy(alpha = 0.4f),
                    tint = HazeTint(CyberpunkBase.Magenta.copy(alpha = tintAlpha)),
                    blurRadius = blurRadius
                )
            )
            .clip(shape)
            .border(
                width = borderWidth,
                color = CyberpunkBase.Magenta.copy(alpha = borderAlpha),
                shape = shape
            )
            .padding(contentPadding),
        content = content
    )
}

/**
 * A simple glass surface without Haze blur (for use when blur is not needed)
 * Provides a semi-transparent card with border glow
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    borderWidth: Dp = 1.dp,
    borderColor: Color = CyberpunkBase.Cyan.copy(alpha = 0.2f),
    backgroundColor: Color = CyberpunkBase.bgSecondary.copy(alpha = 0.6f),
    contentPadding: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = shape
            )
            .padding(contentPadding),
        content = content
    )
}
