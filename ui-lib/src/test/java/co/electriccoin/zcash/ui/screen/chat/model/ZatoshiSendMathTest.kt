package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.extension.floor
import cash.z.ecc.sdk.extension.toCanonicalZecString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test

/**
 * MONEY / SEND correctness for the low-level Zatoshi amount math that every on-chain ZCHAT send
 * depends on. These guard against fund loss and over-/under-sending:
 *
 *  - [Zatoshi.floor] must round DOWN to a 5000-zatoshi multiple (the FIAT-send flow floors the ZEC
 *    equivalent so a fluctuating exchange rate can NEVER cause us to over-send more than the user
 *    approved). Flooring must be idempotent and must never increase the amount.
 *  - ZEC -> zatoshi conversion must go through BigDecimal, not Double, so 0.0002 ZEC is EXACTLY
 *    20000 zatoshi with no binary-floating-point drift (19999/20001 would move the wrong amount
 *    on-chain).
 *  - [Zatoshi.toCanonicalZecString] must emit a '.' decimal separator regardless of device locale,
 *    so ZIP-321 `amount` fields are spec-correct even under a comma-decimal locale (ZIP-321
 *    integrity — a "0,0002" amount would produce a malformed/invalid URI).
 *
 * All pure Kotlin/JVM: Zatoshi + convertZecToZatoshi (BigDecimal-based) + convertZatoshiToZecString
 * are SDK math with no Android runtime dependency.
 */
class ZatoshiSendMathTest {

    // ── floor(): rounds DOWN to a 5000-zatoshi multiple ──────────────────────────────────────────

    @Test
    fun `floor rounds a value between multiples down to the lower 5000 boundary`() {
        // 12345 -> 10000 (the multiple of 5000 at or below it), never up to 15000.
        assertEquals(Zatoshi(10_000L), Zatoshi(12_345L).floor())
    }

    @Test
    fun `floor leaves an exact multiple of 5000 unchanged`() {
        assertEquals(Zatoshi(20_000L), Zatoshi(20_000L).floor())
        assertEquals(Zatoshi(0L), Zatoshi(0L).floor())
    }

    @Test
    fun `floor just under a boundary drops to the previous multiple`() {
        // 4999 -> 0, 9999 -> 5000: never rounds up (that would over-send).
        assertEquals(Zatoshi(0L), Zatoshi(4_999L).floor())
        assertEquals(Zatoshi(5_000L), Zatoshi(9_999L).floor())
    }

    @Test
    fun `floor never increases the amount`() {
        listOf(1L, 4_999L, 5_000L, 5_001L, 12_345L, 99_999L, 100_000_000L).forEach { v ->
            val floored = Zatoshi(v).floor().value
            assertTrue("floor($v)=$floored must not exceed $v", floored <= v)
        }
    }

    @Test
    fun `floor is idempotent`() {
        listOf(1L, 4_999L, 7_777L, 12_345L, 20_000L, 999_999L).forEach { v ->
            val once = Zatoshi(v).floor()
            val twice = once.floor()
            assertEquals("floor must be idempotent for $v", once.value, twice.value)
        }
    }

    // ── ZEC -> zatoshi: BigDecimal, no Double drift ──────────────────────────────────────────────

    @Test
    fun `0_0002 ZEC converts to exactly 20000 zatoshi`() {
        // The classic Double-math failure: (0.0002 * 1e8) can produce 19999.999... .
        assertEquals(20_000L, BigDecimal("0.0002").convertZecToZatoshi().value)
    }

    @Test
    fun `whole and fractional ZEC amounts convert without drift`() {
        assertEquals(100_000_000L, BigDecimal("1").convertZecToZatoshi().value)
        assertEquals(1L, BigDecimal("0.00000001").convertZecToZatoshi().value)
        assertEquals(1_000L, BigDecimal("0.00001").convertZecToZatoshi().value)
        // 0.1 is not exactly representable as a Double, but BigDecimal keeps it exact.
        assertEquals(10_000_000L, BigDecimal("0.1").convertZecToZatoshi().value)
    }

    @Test
    fun `BigDecimal path matches an exact integer-scaled reference for tricky amounts`() {
        // Reference: exact scaling with BigInteger, no floating point anywhere.
        listOf("0.0002", "0.00021", "0.29", "1.23456789", "0.07").forEach { zec ->
            val expected = BigDecimal(zec).movePointRight(8).toBigIntegerExact().toLong()
            assertEquals("ZEC $zec", expected, BigDecimal(zec).convertZecToZatoshi().value)
        }
    }

    // ── Canonical ZEC string: '.' decimal separator regardless of locale ─────────────────────────

    @Test
    fun `canonical ZEC string uses a dot under a comma-decimal locale`() {
        val previous = Locale.getDefault()
        try {
            // GERMANY formats decimals with a comma; the canonical string must still emit a dot.
            Locale.setDefault(Locale.GERMANY)
            val canonical = Zatoshi(20_000L).toCanonicalZecString()
            assertTrue(
                "canonical ZEC string must contain '.', was '$canonical'",
                canonical.contains('.')
            )
            assertTrue(
                "canonical ZEC string must NOT contain a comma decimal separator, was '$canonical'",
                !canonical.contains(',')
            )
            // 20000 zatoshi == 0.0002 ZEC — the exact dot-form value, independent of the comma locale.
            assertEquals("0.0002", canonical)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `canonical ZEC string round-trips back to the same zatoshi under a comma locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val canonical = Zatoshi(20_000L).toCanonicalZecString()
            // A dot-decimal string parses straight back through the BigDecimal path.
            assertEquals(20_000L, BigDecimal(canonical).convertZecToZatoshi().value)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
