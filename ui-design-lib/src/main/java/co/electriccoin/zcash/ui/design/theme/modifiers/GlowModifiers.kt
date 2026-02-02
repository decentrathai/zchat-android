@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.design.theme.modifiers

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.colors.CyberpunkBase

/**
 * Cyberpunk neon glow modifiers for UI elements
 * Creates authentic neon light effects using blur masks
 */

/**
 * Adds a cyan neon glow effect around the composable
 * Use for primary actions, highlights, and positive indicators
 *
 * @param radius The blur radius for the glow effect
 * @param alpha The opacity of the glow (0.0 to 1.0)
 * @param cornerRadius The corner radius for rounded elements
 */
fun Modifier.cyanGlow(
    radius: Dp = 16.dp,
    alpha: Float = 0.4f,
    cornerRadius: Dp = 12.dp
): Modifier = this.drawBehind {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            style = PaintingStyle.Stroke
            strokeWidth = radius.toPx() / 2
            color = CyberpunkBase.Cyan.copy(alpha = alpha)
        }

        paint.asFrameworkPaint().apply {
            maskFilter = BlurMaskFilter(
                radius.toPx(),
                BlurMaskFilter.Blur.NORMAL
            )
        }

        val cornerRadiusPx = cornerRadius.toPx()
        canvas.drawRoundRect(
            left = 0f,
            top = 0f,
            right = size.width,
            bottom = size.height,
            radiusX = cornerRadiusPx,
            radiusY = cornerRadiusPx,
            paint = paint
        )
    }
}

/**
 * Adds a magenta neon glow effect around the composable
 * Use for warnings, destructive actions, and secondary accents
 *
 * @param radius The blur radius for the glow effect
 * @param alpha The opacity of the glow (0.0 to 1.0)
 * @param cornerRadius The corner radius for rounded elements
 */
fun Modifier.magentaGlow(
    radius: Dp = 16.dp,
    alpha: Float = 0.4f,
    cornerRadius: Dp = 12.dp
): Modifier = this.drawBehind {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            style = PaintingStyle.Stroke
            strokeWidth = radius.toPx() / 2
            color = CyberpunkBase.Magenta.copy(alpha = alpha)
        }

        paint.asFrameworkPaint().apply {
            maskFilter = BlurMaskFilter(
                radius.toPx(),
                BlurMaskFilter.Blur.NORMAL
            )
        }

        val cornerRadiusPx = cornerRadius.toPx()
        canvas.drawRoundRect(
            left = 0f,
            top = 0f,
            right = size.width,
            bottom = size.height,
            radiusX = cornerRadiusPx,
            radiusY = cornerRadiusPx,
            paint = paint
        )
    }
}

/**
 * Adds a custom colored neon glow effect
 *
 * @param color The glow color
 * @param radius The blur radius for the glow effect
 * @param alpha The opacity of the glow (0.0 to 1.0)
 * @param cornerRadius The corner radius for rounded elements
 */
fun Modifier.neonGlow(
    color: Color,
    radius: Dp = 16.dp,
    alpha: Float = 0.4f,
    cornerRadius: Dp = 12.dp
): Modifier = this.drawBehind {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            style = PaintingStyle.Stroke
            strokeWidth = radius.toPx() / 2
            this.color = color.copy(alpha = alpha)
        }

        paint.asFrameworkPaint().apply {
            maskFilter = BlurMaskFilter(
                radius.toPx(),
                BlurMaskFilter.Blur.NORMAL
            )
        }

        val cornerRadiusPx = cornerRadius.toPx()
        canvas.drawRoundRect(
            left = 0f,
            top = 0f,
            right = size.width,
            bottom = size.height,
            radiusX = cornerRadiusPx,
            radiusY = cornerRadiusPx,
            paint = paint
        )
    }
}

/**
 * Adds an inner shadow glow effect (softer, more subtle)
 * Useful for text or smaller elements
 *
 * @param color The glow color
 * @param blurRadius The blur radius
 * @param spread How much the glow spreads outward
 * @param cornerRadius The corner radius for rounded elements
 */
fun Modifier.softGlow(
    color: Color = CyberpunkBase.Cyan,
    blurRadius: Dp = 8.dp,
    spread: Dp = 2.dp,
    cornerRadius: Dp = 12.dp
): Modifier = this.drawBehind {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            style = PaintingStyle.Fill
            this.color = color.copy(alpha = 0.3f)
        }

        paint.asFrameworkPaint().apply {
            maskFilter = BlurMaskFilter(
                blurRadius.toPx(),
                BlurMaskFilter.Blur.NORMAL
            )
        }

        val cornerRadiusPx = cornerRadius.toPx()
        canvas.drawRoundRect(
            left = -spread.toPx(),
            top = -spread.toPx(),
            right = size.width + spread.toPx(),
            bottom = size.height + spread.toPx(),
            radiusX = cornerRadiusPx,
            radiusY = cornerRadiusPx,
            paint = paint
        )
    }
}
