package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * RED pin for money amounts rendered in scientific notation.
 *
 * Every dialog that quotes a ZEC amount built its string as `zatoshi.value / 100_000_000.0`. Kotlin's
 * `Double.toString` switches to scientific notation below 1e-3, so the handshake-consent dialog — whose
 * entire purpose is to quote the bill honestly before ZChat spends the user's money — asked them to
 * approve "about 1.7E-4 ZEC". The branch already carries a RED pin proving that same number had been
 * under-quoted by 1,000 zatoshi; printing the corrected figure in a format nobody can read gives the
 * fix back.
 *
 * 17,000 zatoshi is one automatic handshake (1,000 carrier + 1,000 platform fee + 15,000 ZIP 317).
 *
 * Round 8 P9 (ES-2): zecDisplay takes the phone's language; these English figures use Locale.US. Since P9 the figure
 * is the balance formatter's, so a whole-thousands amount carries its grouping ("21,000,000", as the balances show it).
 */
class ZecDisplayTest {
    @Test
    fun a_handshake_bill_is_readable_not_scientific() {
        val shown = zecDisplay(Zatoshi(17_000), Locale.US)

        assertEquals("0.00017", shown)
        assertFalse(shown.contains("E", ignoreCase = true), "a money quote must never be scientific: $shown")
    }

    @Test
    fun small_amounts_that_doubles_render_exponentially_are_plain_decimals() {
        // Every one of these is below 1e-3 ZEC, i.e. exactly where Double.toString gives up.
        assertEquals("0.00001", zecDisplay(Zatoshi(1_000), Locale.US))
        assertEquals("0.0000001", zecDisplay(Zatoshi(10), Locale.US))
        assertEquals("0.00000001", zecDisplay(Zatoshi(1), Locale.US))
        assertEquals("0.00034", zecDisplay(Zatoshi(34_000), Locale.US))
    }

    @Test
    fun ordinary_amounts_keep_their_familiar_form() {
        assertEquals("0", zecDisplay(Zatoshi(0), Locale.US))
        assertEquals("0.00135", zecDisplay(Zatoshi(135_000), Locale.US))
        assertEquals("0.0053", zecDisplay(Zatoshi(530_000), Locale.US))
        assertEquals("0.00811", zecDisplay(Zatoshi(811_000), Locale.US))
        assertEquals("1", zecDisplay(Zatoshi(100_000_000), Locale.US))
        assertEquals("12.5", zecDisplay(Zatoshi(1_250_000_000), Locale.US))
    }

    /** No rounding: a quote the user approves must be the amount that leaves the wallet. */
    @Test
    fun the_full_eight_decimals_survive() {
        assertEquals("0.12345678", zecDisplay(Zatoshi(12_345_678), Locale.US))
        assertEquals("21,000,000", zecDisplay(Zatoshi(2_100_000_000_000_000), Locale.US))
    }
}
