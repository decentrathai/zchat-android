package co.electriccoin.zcash.ui.screen.advancedsettings

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 polish lane p4b, ZC-1721 and ZC-1722: behaviour of the two pure functions the amount display (`StyledBalance`)
 * must lift out of its composable. This file is the only one that needs them, and it reaches them by reflection, so
 * the whole test source set still compiles before the fix and the tests fail one by one with a plain message.
 *
 * Required signatures, both public, top-level, in `ui-design-lib/.../design/component/Balance.kt` (class `BalanceKt`),
 * package `co.electriccoin.zcash.ui.design.component`:
 *
 * ```
 * data class BalanceScale(val scale: Float, val wraps: Boolean)
 *
 * /** measure(s) = the width in px of the whole amount (integer and decimals together) drawn at scale s. */
 * fun chooseBalanceScale(measure: (Float) -> Int, maxWidth: Int, floor: Float): BalanceScale
 *
 * /** The amount as the lines the wrap fallback draws; the concatenation of the lines is exactly parts.first + parts.second. */
 * fun balanceWrapLines(parts: Pair<String, String>, decimalSeparator: Char): List<String>
 * ```
 *
 * chooseBalanceScale: one factor for integer and decimals (a single Float). wraps = false means measure(scale) <=
 * maxWidth (one line). wraps = true only when even the floor does not fit; then scale == floor (or 1 when floor >= 1).
 * `Constraints.Infinity` (Int.MAX_VALUE) means unbounded: scale 1, no wrap. scale is always in [min(floor, 1), 1].
 *
 * balanceWrapLines: no character is added, removed or reordered (copying the amount gives the amount). With the
 * decimal separator inside the text, line 1 is everything before it (prefix and integer part) and line 2 starts with
 * the separator and holds all decimals, so no line is one or two stray digits. Without a separator, or with it at the
 * very start, the result is a single line.
 */
class QA2PolishP4bBalanceScaleTest {
    private val balanceClass: Class<*> by lazy {
        try {
            Class.forName("co.electriccoin.zcash.ui.design.component.BalanceKt")
        } catch (e: ClassNotFoundException) {
            fail("BalanceKt is gone: $e")
        }
    }

    private fun choose(
        measure: (Float) -> Int,
        maxWidth: Int,
        floor: Float
    ): Pair<Float, Boolean> {
        val m =
            try {
                balanceClass.getMethod(
                    "chooseBalanceScale",
                    Function1::class.java,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                )
            } catch (e: NoSuchMethodException) {
                fail(
                    "Balance.kt has no public top-level chooseBalanceScale(measure: (Float) -> Int, maxWidth: Int, floor: Float): " +
                        "BalanceScale"
                )
            }
        val result = m.invoke(null, measure, maxWidth, floor)!!
        val scale = result.javaClass.getMethod("getScale").invoke(result) as Float
        val wraps = result.javaClass.getMethod("getWraps").invoke(result) as Boolean
        return scale to wraps
    }

    @Suppress("UNCHECKED_CAST")
    private fun lines(
        first: String,
        second: String,
        separator: Char
    ): List<String> {
        val m =
            try {
                balanceClass.getMethod("balanceWrapLines", Pair::class.java, Char::class.javaPrimitiveType)
            } catch (e: NoSuchMethodException) {
                fail(
                    "Balance.kt has no public top-level balanceWrapLines(parts: Pair<String, String>, decimalSeparator: Char): List<String>"
                )
            }
        return m.invoke(null, first to second, separator) as List<String>
    }

    /** Width grows in proportion to the scale: 1000 px at scale 1. */
    private val linear: (Float) -> Int = { s -> Math.ceil((1000f * s).toDouble()).toInt() }

    // ---------------------------------------------------------------- ZC-1721

    @Test
    fun zc1721_an_amount_that_fits_keeps_the_design_size() {
        assertEquals(1f to false, choose(linear, maxWidth = 1000, floor = 0.6f), "fits exactly at 1.0")
        assertEquals(1f to false, choose(linear, maxWidth = 2400, floor = 0.6f), "fits with room")
    }

    @Test
    fun zc1721_an_amount_that_does_not_fit_shrinks_to_a_size_that_fits() {
        val (scale, wraps) = choose(linear, maxWidth = 700, floor = 0.6f)
        assertTrue(!wraps, "700 px is reachable above the floor, so no wrap")
        assertTrue(scale < 1f && scale >= 0.6f, "scale $scale is between the floor and 1")
        assertTrue(linear(scale) <= 700, "the amount at scale $scale (${linear(scale)} px) fits 700 px")
        assertTrue(scale >= 0.63f, "it does not shrink more than needed (scale $scale, ideal 0.7)")
    }

    @Test
    fun zc1721_a_width_that_needs_a_fixed_overhead_still_ends_fitting() {
        // 200 px that do not scale (a prefix, letter spacing rounding) plus 800 px that do.
        val withOverhead: (Float) -> Int = { s -> 200 + Math.ceil((800f * s).toDouble()).toInt() }
        val (scale, wraps) = choose(withOverhead, maxWidth = 700, floor = 0.5f)
        assertTrue(!wraps)
        assertTrue(withOverhead(scale) <= 700, "scale $scale gives ${withOverhead(scale)} px")
        assertTrue(scale >= 0.5f)
    }

    @Test
    fun zc1721_below_the_floor_the_scale_stops_at_the_floor_and_the_amount_wraps() {
        val (scale, wraps) = choose(linear, maxWidth = 400, floor = 0.6f)
        assertEquals(0.6f, scale, "never smaller than the floor, the digits stay legible")
        assertTrue(wraps, "the floor (600 px) does not fit 400 px, so the amount wraps rather than hides digits")
    }

    @Test
    fun zc1721_a_width_exactly_at_the_floor_fits_without_wrapping() {
        val (scale, wraps) = choose(linear, maxWidth = 600, floor = 0.6f)
        assertTrue(!wraps)
        assertTrue(scale >= 0.6f && linear(scale) <= 600, "scale $scale")
    }

    @Test
    fun zc1721_an_unbounded_width_is_not_shrunk() {
        assertEquals(1f to false, choose(linear, maxWidth = Int.MAX_VALUE, floor = 0.6f))
    }

    @Test
    fun zc1721_a_zero_or_negative_width_ends_at_the_floor_with_a_wrap() {
        assertEquals(0.6f to true, choose(linear, maxWidth = 0, floor = 0.6f))
        assertEquals(0.6f to true, choose(linear, maxWidth = -5, floor = 0.6f))
    }

    @Test
    fun zc1721_a_floor_of_one_never_shrinks() {
        assertEquals(1f to true, choose(linear, maxWidth = 500, floor = 1f))
        assertEquals(1f to false, choose(linear, maxWidth = 1000, floor = 1f))
    }

    @Test
    fun zc1721_the_result_is_one_factor_that_is_never_below_the_floor_nor_above_one() {
        for (width in listOf(50, 300, 599, 600, 601, 800, 999, 1000, 1001, 5000)) {
            val (scale, wraps) = choose(linear, width, 0.6f)
            assertTrue(scale in 0.6f..1f, "width $width: scale $scale")
            if (!wraps) assertTrue(linear(scale) <= width, "width $width: scale $scale does not fit")
        }
    }

    // ---------------------------------------------------------------- ZC-1722

    @Test
    fun zc1722_the_wrapped_amount_breaks_at_the_decimal_separator() {
        assertEquals(listOf("1,234", ".56789012"), lines("1,234.567", "89012", '.'))
        assertEquals(listOf("1.234", ",56789012"), lines("1.234,567", "89012", ','))
        assertEquals(listOf("12345678", ".00000001"), lines("12345678.000", "00001", '.'))
    }

    @Test
    fun zc1722_a_prefix_stays_with_the_integer_line() {
        assertEquals(listOf("≈ 1,234", ".56789012"), lines("≈ 1,234.567", "89012", '.'))
    }

    @Test
    fun zc1722_the_lines_together_are_exactly_the_amount() {
        for (
        (first, second, sep) in
        listOf(
            Triple("1,234.567", "89012", '.'),
            Triple("0.000", "00001", '.'),
            Triple("1.234,567", "89012", ','),
            Triple("1.5", "", '.'),
            Triple("21000000.000", "00000", '.')
        )
        ) {
            val joined = lines(first, second, sep).joinToString("")
            assertEquals(first + second, joined, "no digit, separator or space is added, dropped or moved")
        }
    }

    @Test
    fun zc1722_no_line_is_a_stray_digit_or_two() {
        val out = lines("1,234.567", "89012", '.')
        assertEquals(2, out.size)
        assertTrue(out.all { it.length > 2 || it.any { c -> c == '.' } }, "lines: $out")
        // The decimals stay together on one line.
        assertTrue(out[1].startsWith(".") && out[1].length == 9, "lines: $out")
    }

    @Test
    fun zc1722_without_a_separator_there_is_one_line() {
        assertEquals(listOf("•••••"), lines("•••••", "", '.'))
        assertEquals(listOf("1234"), lines("1234", "", '.'))
    }

    @Test
    fun zc1722_a_separator_at_the_very_start_is_not_split_off_an_empty_line() {
        assertEquals(listOf(".5"), lines(".5", "", '.'))
    }
}
