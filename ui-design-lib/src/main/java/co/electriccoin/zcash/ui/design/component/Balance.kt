package co.electriccoin.zcash.ui.design.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import java.text.DecimalFormatSymbols
import java.util.Locale

@Preview
@Composable
private fun StyledBalancePreview() =
    ZcashTheme(forceDarkMode = false) {
        BlankSurface {
            Column {
                StyledBalance(
                    balanceParts = ZecAmountTriple(main = "1,234.56789012"),
                    isHideBalances = false,
                    modifier = Modifier
                )
            }
        }
    }

@Preview
@Composable
private fun HiddenStyledBalancePreview() =
    ZcashTheme(forceDarkMode = false) {
        BlankSurface {
            Column {
                StyledBalance(
                    balanceParts = ZecAmountTriple(main = "1,234.56789012"),
                    isHideBalances = true,
                    modifier = Modifier
                )
            }
        }
    }

/**
 * This accepts string with balance and displays it in the UI component styled according to the design
 * requirements. The function displays the balance within two parts.
 *
 * @param balanceParts [ZecAmountTriple] class that holds balance parts
 * @param isHideBalances Flag referring about the balance being hidden or not
 * @param hiddenBalancePlaceholder String holding the placeholder for the hidden balance
 * @param textStyle Styles for the integer and floating part of the balance
 * @param textColor Optional color to modify the default font color from [textStyle]
 * @param modifier Modifier to modify the Text UI element as needed
 */
@Suppress("LongParameterList")
@Composable
fun StyledBalance(
    balanceParts: ZecAmountTriple,
    modifier: Modifier = Modifier,
    isHideBalances: Boolean = false,
    showDust: Boolean = true,
    hiddenBalancePlaceholder: String = stringResource(id = R.string.hide_balance_placeholder),
    textColor: Color = Color.Unspecified,
    textStyle: BalanceTextStyle = StyledBalanceDefaults.textStyles(),
) {
    val locale = rememberDesiredFormatLocale()
    val parts =
        if (isHideBalances) {
            hiddenBalancePlaceholder to ""
        } else {
            splitBalance(balanceParts, locale).let { if (showDust) it else it.first to "" }
        }

    // ZC-1716: the amount the user reads and confirms is always whole on screen. It shrinks to the width it gets
    // (integer and decimals together, by one factor) down to a legible floor, and wraps instead of hiding digits
    // if even the floor does not fit. Never a marquee, never an ellipsis.
    BoxWithConstraints(modifier = modifier) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val baseStyle = LocalTextStyle.current
        val maxWidth = constraints.maxWidth
        val fit =
            remember(parts, textStyle, maxWidth, density, baseStyle) {
                fitBalance(measurer, parts, textStyle, baseStyle, maxWidth)
            }
        val text = balanceText(parts, textStyle, fit.scale)

        SelectionContainer {
            if (!fit.wraps) {
                Text(
                    text = text,
                    color = textColor,
                    style = baseStyle,
                    maxLines = 1,
                    softWrap = false
                )
            } else {
                // ZC-1722: even the floor does not fit, so the integer part and the decimals go on their own lines
                // (the break is at the decimal separator, never inside the digits). The lines are pieces of the
                // same text, so a copied amount is exactly the amount.
                val separator = DecimalFormatSymbols(locale).monetaryDecimalSeparator
                Column {
                    var from = 0
                    balanceWrapLines(parts, separator).forEach { line ->
                        Text(
                            text = text.subSequence(from, from + line.length),
                            color = textColor,
                            style = baseStyle,
                            maxLines = Int.MAX_VALUE,
                            softWrap = true
                        )
                        from += line.length
                    }
                }
            }
        }
    }
}

/** ZC-1721: the size chosen for the amount; [wraps] = true only when even the floor does not fit one line. */
data class BalanceScale(
    val scale: Float,
    val wraps: Boolean
)

/**
 * ZC-1721: the largest scale in [min(floor, 1), 1] at which the amount fits one line of [maxWidth] px.
 *
 * [measure] gives the width in px of the whole amount (integer and decimals together, one factor) at a scale.
 * An unbounded width ([Constraints.Infinity]) keeps the design size; if even the floor does not fit (or the
 * width is zero or negative), the result is the floor with [BalanceScale.wraps] set.
 */
fun chooseBalanceScale(
    measure: (Float) -> Int,
    maxWidth: Int,
    floor: Float
): BalanceScale {
    val min = floor.coerceAtMost(1f)
    if (maxWidth == Constraints.Infinity) return BalanceScale(1f, false)
    if (maxWidth <= 0) return BalanceScale(min, true)
    val full = measure(1f)
    if (full <= maxWidth) return BalanceScale(1f, false)
    if (min >= 1f || measure(min) > maxWidth) return BalanceScale(min, true)

    // fits at [fits], not at [tooBig]: a proportional first guess, then halving the gap.
    var fits = min
    var tooBig = 1f
    val guess = (maxWidth.toFloat() / full).coerceIn(min, 1f)
    if (measure(guess) <= maxWidth) fits = guess else tooBig = guess
    repeat(MAX_FIT_STEPS) {
        val mid = (fits + tooBig) / 2f
        if (measure(mid) <= maxWidth) fits = mid else tooBig = mid
    }
    return BalanceScale(fits, false)
}

/**
 * ZC-1722: the lines the wrap fallback draws: the prefix and integer part, then the decimal separator with all
 * decimals. No character is added, dropped or moved (the lines together are `parts.first + parts.second`); with no
 * separator, or one at the very start, the amount is one line.
 */
fun balanceWrapLines(
    parts: Pair<String, String>,
    decimalSeparator: Char
): List<String> {
    val text = parts.first + parts.second
    val at = text.indexOf(decimalSeparator)
    return if (at <= 0) listOf(text) else listOf(text.substring(0, at), text.substring(at))
}

/** The largest scale (1 = the given styles, at least the floor) at which the amount fits on one line. */
private fun fitBalance(
    measurer: TextMeasurer,
    parts: Pair<String, String>,
    textStyle: BalanceTextStyle,
    baseStyle: TextStyle,
    maxWidth: Int
): BalanceScale =
    chooseBalanceScale(
        measure = { scale ->
            measurer
                .measure(
                    text = balanceText(parts, textStyle, scale),
                    style = baseStyle,
                    softWrap = false,
                    maxLines = 1
                ).size.width
        },
        maxWidth = maxWidth,
        floor = minBalanceScale(textStyle)
    )

/** The floor: never below [MIN_BALANCE_SCALE] of the design size nor below [MIN_BALANCE_FONT_SIZE_SP]. */
private fun minBalanceScale(textStyle: BalanceTextStyle): Float {
    val size = textStyle.mostSignificantPart.fontSize
    val bySize = if (size.isSpecified && size.value > 0f) MIN_BALANCE_FONT_SIZE_SP / size.value else 0f
    return maxOf(MIN_BALANCE_SCALE, bySize).coerceAtMost(1f)
}

private fun balanceText(
    parts: Pair<String, String>,
    textStyle: BalanceTextStyle,
    scale: Float
): AnnotatedString =
    buildAnnotatedString {
        withStyle(style = textStyle.mostSignificantPart.scaled(scale).toSpanStyle()) {
            append(parts.first)
        }
        if (parts.second.isNotEmpty()) {
            withStyle(style = textStyle.leastSignificantPart.scaled(scale).toSpanStyle()) {
                append(parts.second)
            }
        }
    }

private fun TextStyle.scaled(scale: Float): TextStyle =
    if (scale == 1f) {
        this
    } else {
        copy(
            fontSize = fontSize.scaledBy(scale),
            letterSpacing = letterSpacing.scaledBy(scale),
            lineHeight = lineHeight.scaledBy(scale)
        )
    }

private fun TextUnit.scaledBy(scale: Float): TextUnit = if (isSpecified) this * scale else this

/** ZC-1716: the shrunk amount stays legible. */
private const val MIN_BALANCE_SCALE = 0.6f
private const val MIN_BALANCE_FONT_SIZE_SP = 12f
private const val MAX_FIT_STEPS = 8

private const val CUT_POSITION_OFFSET = 4

private fun splitBalance(balanceStringParts: ZecAmountTriple, locale: Locale): Pair<String, String> {
    Twig.debug { "Balance parts before calculation: $balanceStringParts" }

    val cutPosition =
        balanceStringParts.main
            .indexOf(
                startIndex = 0,
                char = DecimalFormatSymbols(locale).monetaryDecimalSeparator,
                ignoreCase = true
            ).let { separatorPosition ->
                if (separatorPosition + CUT_POSITION_OFFSET < balanceStringParts.main.length) {
                    separatorPosition + CUT_POSITION_OFFSET
                } else {
                    balanceStringParts.main.length
                }
            }

    val firstPart =
        buildString {
            append(balanceStringParts.prefix ?: "")
            append(
                balanceStringParts.main.substring(
                    startIndex = 0,
                    endIndex = cutPosition
                )
            )
            append(balanceStringParts.suffix ?: "")
        }

    val secondPart =
        balanceStringParts.main.substring(
            startIndex = cutPosition
        )

    Twig.debug { "Balance after split: $firstPart|$secondPart" }

    return Pair(firstPart, secondPart)
}

data class ZecAmountTriple(
    val main: String,
    val prefix: String? = null,
    val suffix: String? = null
)

@Immutable
data class BalanceTextStyle(
    val mostSignificantPart: TextStyle,
    val leastSignificantPart: TextStyle
)

object StyledBalanceDefaults {
    @Stable
    @Composable
    fun textStyles(
        mostSignificantPart: TextStyle = ZcashTheme.extendedTypography.balanceWidgetStyles.first,
        leastSignificantPart: TextStyle = ZcashTheme.extendedTypography.balanceWidgetStyles.second,
    ) = BalanceTextStyle(
        mostSignificantPart = mostSignificantPart,
        leastSignificantPart = leastSignificantPart
    )
}
