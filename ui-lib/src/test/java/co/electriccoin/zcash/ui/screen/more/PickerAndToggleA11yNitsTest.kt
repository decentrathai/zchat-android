package co.electriccoin.zcash.ui.screen.more

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 P3 polish, U8 review nits (RowIconsAndPickerSemanticsTest and its fix 54eb7b29d; Q2-05 remainder, Q2-92).
 *
 * 1. The Swap amount field's token/dollar toggle is labelled "Switch amount currency" always, also while it is not
 *    tappable (`isSwapChangeEnabled == false`, a plain Modifier): TalkBack then announces a label for a picture that does
 *    nothing. The label belongs to the enabled toggle only.
 * 2. The Exchange rate and Crash reporting screens draw two `Option` rows (RadioButton-role selectables) in a plain
 *    Column / content slot. Tor and the mode sheet wrap theirs in `Modifier.selectableGroup()`, which is what makes
 *    TalkBack read "1 of 2". These two lack it.
 * 3. The Spanish label says "importe"; the app says "monto" for an amount in Spanish (Q2-91 "Monto: ...", the amount
 *    dialogs): "Cambiar la moneda del monto". The Spanish line is an agent draft (app-texts rule 9).
 *
 * Source and resource checks: Compose, no host in this module. The old pin of the Spanish text in
 * RowIconsAndPickerSemanticsTest moves to the new text with this commit.
 */
class PickerAndToggleA11yNitsTest {
    private val es by lazy { ChatResourceText.strings("values-es") }

    private fun mainSource(path: String): String =
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/$path")

    @Test
    fun `the swap currency toggle carries its label only while it can be tapped`() {
        val src = mainSource("screen/swap/ui/SwapAmountTextField.kt")
        val at = src.indexOf("R.drawable.ic_swap_recipient")
        assertTrue(at >= 0, "SwapAmountTextField has no currency toggle")
        val after = src.substring(at, (at + 900).coerceAtMost(src.length))
        val description =
            Regex("""contentDescription\s*=\s*([\s\S]*?)\n\s*\)""").find(after)?.groupValues?.get(1)
                ?: throw AssertionError("the toggle has no contentDescription argument")
        assertTrue(
            "R.string.swap_amount_currency_toggle_content_description" in description,
            "the toggle has no label at all: $description",
        )
        assertTrue(
            "isSwapChangeEnabled" in description,
            "the toggle is labelled even while it cannot be tapped (isSwapChangeEnabled == false): $description",
        )
        assertTrue("null" in description, "no `null` description for the disabled toggle: $description")
    }

    @Test
    fun `the exchange rate options are one selectable group`() {
        val src = mainSource("screen/exchangerate/settings/ExchangeRateSettingsView.kt")
        val view = src.substringAfter("internal fun ExchangeRateSettingsView(").substringBefore("\n}\n")
        val firstOption = view.indexOf("Option(")
        assertTrue(firstOption >= 0, "ExchangeRateSettingsView draws no Option")
        assertTrue(
            ".selectableGroup()" in view.substring(0, firstOption),
            "the two Exchange rate options are not inside a Modifier.selectableGroup() (TalkBack cannot say \"1 of 2\")",
        )
    }

    @Test
    fun `the crash reporting options are one selectable group`() {
        val src = mainSource("screen/crashreporting/view/CrashReportingOptInView.kt")
        val options = src.substringAfter("fun CrashReportingOptInOptions(").substringBefore("\n}\n")
        val firstOption = options.indexOf("Option(")
        assertTrue(firstOption >= 0, "CrashReportingOptInOptions draws no Option")
        assertTrue(
            ".selectableGroup()" in options.substring(0, firstOption),
            "the two Crash reporting options are not inside a Modifier.selectableGroup() (TalkBack cannot say \"1 of 2\")",
        )
    }

    @Test
    fun `the Spanish swap toggle label says monto, as the rest of the app does`() {
        val label = assertNotNull(es["swap_amount_currency_toggle_content_description"], "values-es has no label")
        assertEquals("Cambiar la moneda del monto", label)
        assertFalse("importe" in label.lowercase(), "the label still says importe: $label")
    }
}
