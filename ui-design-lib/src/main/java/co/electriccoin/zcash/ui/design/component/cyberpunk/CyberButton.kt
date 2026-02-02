@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.design.component.cyberpunk

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.design.theme.colors.CyberpunkBase
import co.electriccoin.zcash.ui.design.theme.internal.OrbitronFontFamily
import co.electriccoin.zcash.ui.design.theme.modifiers.cyanGlow
import co.electriccoin.zcash.ui.design.theme.modifiers.magentaGlow

/**
 * Button type enum for CyberButton variants
 */
enum class CyberButtonType {
    /** Cyan filled button with glow - primary actions */
    Primary,
    /** Transparent with cyan border - secondary actions */
    Secondary,
    /** Magenta filled button with glow - destructive/warning actions */
    Destructive,
    /** Transparent with no border - ghost/text button */
    Ghost
}

/**
 * A cyberpunk-styled button with neon glow effects
 *
 * @param text The button text
 * @param onClick Click handler
 * @param modifier Modifier for the button
 * @param type The button style variant
 * @param enabled Whether the button is enabled
 * @param height Button height
 * @param cornerRadius Corner radius for the button shape
 * @param glowRadius Radius for the glow effect
 * @param contentPadding Horizontal padding for content
 */
@Composable
fun CyberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    type: CyberButtonType = CyberButtonType.Primary,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    cornerRadius: Dp = 12.dp,
    glowRadius: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp)
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "button_scale"
    )

    val shape = RoundedCornerShape(cornerRadius)

    val backgroundColor = when {
        !enabled -> CyberpunkBase.bgTertiary
        type == CyberButtonType.Primary -> CyberpunkBase.Cyan
        type == CyberButtonType.Destructive -> CyberpunkBase.Magenta
        else -> Color.Transparent
    }

    val textColor = when {
        !enabled -> CyberpunkBase.TextTertiary
        type == CyberButtonType.Primary || type == CyberButtonType.Destructive -> CyberpunkBase.TextInverse
        else -> CyberpunkBase.Cyan
    }

    val borderColor = when {
        !enabled -> CyberpunkBase.TextTertiary.copy(alpha = 0.3f)
        type == CyberButtonType.Secondary -> CyberpunkBase.Cyan.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    val glowModifier = when {
        !enabled -> Modifier
        type == CyberButtonType.Primary -> Modifier.cyanGlow(radius = glowRadius, cornerRadius = cornerRadius)
        type == CyberButtonType.Destructive -> Modifier.magentaGlow(radius = glowRadius, cornerRadius = cornerRadius)
        else -> Modifier
    }

    Box(
        modifier = modifier
            .scale(scale)
            .then(glowModifier)
            .height(height)
            .clip(shape)
            .background(backgroundColor)
            .then(
                if (type == CyberButtonType.Secondary && enabled) {
                    Modifier.border(
                        width = 1.dp,
                        color = borderColor,
                        shape = shape
                    )
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = if (type == CyberButtonType.Destructive) CyberpunkBase.Magenta else CyberpunkBase.Cyan),
                enabled = enabled,
                onClick = onClick
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = OrbitronFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = 1.sp
            ),
            color = textColor
        )
    }
}

/**
 * Full-width variant of CyberButton
 */
@Composable
fun CyberButtonFullWidth(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    type: CyberButtonType = CyberButtonType.Primary,
    enabled: Boolean = true
) {
    CyberButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        type = type,
        enabled = enabled
    )
}

/**
 * Small variant of CyberButton for compact spaces
 */
@Composable
fun CyberButtonSmall(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    type: CyberButtonType = CyberButtonType.Primary,
    enabled: Boolean = true
) {
    CyberButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        type = type,
        enabled = enabled,
        height = 40.dp,
        cornerRadius = 8.dp,
        glowRadius = 12.dp,
        contentPadding = PaddingValues(horizontal = 16.dp)
    )
}
