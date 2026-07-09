package cash.z.ecc.sdk.extension

import cash.z.ecc.android.sdk.ext.convertZatoshiToZecString
import cash.z.ecc.android.sdk.model.Zatoshi
import java.util.Locale
import kotlin.math.floor

private const val DECIMALS_MAX_LONG = 8
private const val DECIMALS_MIN_LONG = 3

private const val DECIMALS_SHORT = 3

private const val MIN_ZATOSHI_FOR_DOTS_SHORT = Zatoshi.ZATOSHI_PER_ZEC / 1000

val Zatoshi.Companion.ZERO: Zatoshi
    get() = Zatoshi(0)

fun Zatoshi.toZecStringFull() =
    convertZatoshiToZecString(
        // SDK 2.5.2 made locale an explicit required parameter. 2.4.3 formatted with
        // Locale.getDefault() internally — pass it to preserve identical behavior.
        locale = Locale.getDefault(),
        maxDecimals = DECIMALS_MAX_LONG,
        minDecimals = DECIMALS_MIN_LONG
    )

/**
 * Same as [toZecStringFull] but formats with an explicit [locale]. Use this when the produced string
 * is later re-parsed with a KNOWN locale (e.g. the ZIP-321 amount round-trip in the Request flow,
 * which parses back with getPreferredLocale). Formatting and parsing with mismatched decimal
 * separators can turn "0.25" into 250, so the two sides must agree on the locale.
 */
fun Zatoshi.toZecStringFull(locale: Locale) =
    convertZatoshiToZecString(
        locale = locale,
        maxDecimals = DECIMALS_MAX_LONG,
        minDecimals = DECIMALS_MIN_LONG
    )

/**
 * Locale-invariant ZEC amount string for machine-readable contexts (e.g. ZIP-321 URIs).
 *
 * Unlike [toZecStringFull], which uses [Locale.getDefault] and therefore emits a localized
 * decimal separator (e.g. "0,00001" in German), this always uses [Locale.ROOT] so the result
 * uses a canonical '.' decimal separator and no grouping — required for spec-correct ZIP-321
 * `amount` fields that must round-trip through any locale.
 */
fun Zatoshi.toCanonicalZecString() =
    convertZatoshiToZecString(
        locale = Locale.ROOT,
        maxDecimals = DECIMALS_MAX_LONG,
        minDecimals = DECIMALS_MIN_LONG
    )

fun Zatoshi.toZecStringAbbreviated(suffix: String): ZecAmountPair {
    val checkedSuffix =
        if (value in 1..<MIN_ZATOSHI_FOR_DOTS_SHORT) {
            suffix
        } else {
            ""
        }
    return convertZatoshiToZecString(
        locale = Locale.getDefault(),
        minDecimals = DECIMALS_SHORT,
        maxDecimals = DECIMALS_SHORT
    ).let { mainPart ->
        ZecAmountPair(
            main = mainPart,
            suffix = checkedSuffix
        )
    }
}

@Suppress("MagicNumber")
fun Zatoshi.floor(): Zatoshi = Zatoshi(floorRoundBy(value.toDouble(), 5000.0).toLong())

data class ZecAmountPair(
    val main: String,
    val suffix: String
)

val Zatoshi.Companion.typicalFee: Zatoshi
    get() = Zatoshi(TYPICAL_FEE)

private const val TYPICAL_FEE = 100000L

private fun floorRoundBy(number: Double, multiple: Double): Double {
    require(multiple != 0.0) { "Multiple cannot be zero" }
    return floor(number / multiple) * multiple
}
