package co.electriccoin.zcash.ui.screen.balances

import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.extension.toZecStringFull
import org.junit.Test
import java.io.File
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R6-3 (round 5 report §5 item 5) — the wallet's headline balance needs a restart after a language change,
 * and right after one it is garbled: `Ƶ0,0 087079` for 0.00087079 ZEC, with the Spanish comma AND the big/small
 * digit split in the wrong place, while every other number on the same screen had already switched.
 *
 * The mechanism, in two places that disagree about the language:
 *  - `BalanceWidget` formats the amount with `toZecStringFull()`, i.e. `Locale.getDefault()` — the process
 *    default, which a language change handled in place (the activity keeps `locale` in configChanges) does
 *    not refresh and which no Compose state tracks;
 *  - `StyledBalance` then cuts that string into the big and the small digits at the decimal separator of
 *    `rememberDesiredFormatLocale()` — the language Compose sees NOW.
 * After Spanish → English the string still has a comma, the cut looks for a point, does not find it, and cuts
 * at a fixed offset from "nothing": `0,0` big, `0087079` small.
 *
 * Wanted: the headline balance (and every `StyledBalance` fed from `toZecStringFull`) formats with the same
 * Compose-observed locale that splits it, so it follows the language like the other numbers. Display only —
 * no amount, fee, parse or stored value changes.
 */
class HeadlineBalanceFollowsLanguageTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private fun src(path: String) = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/$path").readText()

    /** The phone's string, rebuilt from the two locales: the split rule copied from StyledBalance. */
    @Test
    fun diagnosis_a_spanish_string_cut_at_the_english_separator_is_the_garbled_headline() {
        val stale = Zatoshi(87_079L).toZecStringFull(Locale.forLanguageTag("es-ES"))
        assertEquals("0,00087079", stale, "fixture: Spanish formats 0.00087079 ZEC with a comma")
        val separator = DecimalFormatSymbols(Locale.US).monetaryDecimalSeparator
        val cut = stale.indexOf(separator).let { if (it + 4 < stale.length) it + 4 else stale.length }
        assertEquals("0,0" to "0087079", stale.substring(0, cut) to stale.substring(cut), "the phone's Ƶ0,0 087079")
    }

    @Test
    fun the_headline_balance_is_formatted_with_the_locale_that_splits_it() {
        assertFormatsWithComposeLocale("screen/balances/BalanceWidget.kt", "the wallet's headline balance")
    }

    @Test
    fun the_review_screen_amounts_are_formatted_with_the_locale_that_splits_them() {
        assertFormatsWithComposeLocale(
            "screen/reviewtransaction/ReviewTransactionView.kt",
            "the amount on the send-review screen"
        )
    }

    private fun assertFormatsWithComposeLocale(
        path: String,
        what: String
    ) {
        val file = src(path)
        assertFalse(
            "toZecStringFull()" in file,
            "$what is formatted with Locale.getDefault() (toZecStringFull()) but split by StyledBalance with " +
                "the Compose locale, so after a language change it keeps the old separators and is cut in " +
                "the wrong place until the app restarts"
        )
        assertTrue(
            "rememberDesiredFormatLocale()" in file,
            "$what reads no Compose-observed locale, so a language change never re-formats it"
        )
    }
}
