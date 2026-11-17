package co.electriccoin.zcash.ui.screen.chat.view

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import org.junit.Test
import java.util.Locale
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 8 R8-fixup F8 (REVIEW_R8 F8, device LEDGER row 4) — the confirm before paying a payment request says
 * "Send 0.0001 ZEC? This sends 0.0001 ZEC from your wallet…", but the wallet pays the amount plus the platform
 * output plus the ZIP 317 fee: 26,000 zatoshi for a 10,000-zatoshi request, 2.6 times the figure the user agreed to.
 * The dialog is also hard-coded English with a US decimal point, outside the Round 8 P9 localisation.
 *
 * Wanted:
 * - the dialog's text comes from string resources in English and Spanish (`pay_request_confirm_title`,
 *   `pay_request_confirm_body`, `pay_request_confirm_button`) with the same placeholders in both;
 * - the body names what the payment takes from the wallet in all (the amount, the platform fee and the network
 *   fee): D-6 (fix-all 2.13.0, owner W-10) replaces the floor (`PaidActionCopy.payRequestTotal`) with the exact figure of
 *   the payment's quote (`onQuotePayment`, `PaymentQuote.Exact.total`, "exactly");
 * - the figures are written for the phone's language (`PaidActionCopy.zec` / `zecDisplay`), never `Locale.US`.
 *
 * Reads the resource files and the view's source, as the other copy tests do.
 */
class PayRequestConfirmShowsTotalTest {
    private val keys = listOf("pay_request_confirm_title", "pay_request_confirm_body", "pay_request_confirm_button")

    /** The pay-request confirm block of ChatDetailView (from `pendingPayRequest?.let` to the next dialog). */
    private val block: String by lazy {
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
        val start = source.indexOf("pendingPayRequest?.let")
        check(start >= 0) { "no pendingPayRequest?.let block in ChatDetailView" }
        val end = source.indexOf("showWelcomeZecConfirm", start).takeIf { it > start } ?: (start + 4_000)
        source.substring(start, end)
    }

    @Test
    fun `the confirm text is in english and spanish resources with the same placeholders`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for (key in keys) {
            val english = assertNotNull(en[key], "no English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            val placeholders = Regex("""%\d\$[sd]""")
            assertTrue(
                placeholders.findAll(english).map { it.value }.toSet() == placeholders.findAll(spanish).map { it.value }.toSet(),
                "$key: English and Spanish use different placeholders: \"$english\" / \"$spanish\"",
            )
        }
        val body = en.getValue("pay_request_confirm_body")
        assertTrue("%3\$s" in body, "the body does not show the total (%3\$s): $body")
        assertTrue("exactly" in body, "the total is not the exact figure (D-6): $body")
        val esBody = es.getValue("pay_request_confirm_body")
        assertTrue("exactamente" in esBody, "the Spanish total is not the exact figure (D-6): $esBody")
        for (english in listOf("This sends", "from your wallet", "can't be undone", "Send ")) {
            assertTrue(english !in esBody, "the Spanish body is still English (\"$english\"): $esBody")
        }
    }

    @Test
    fun `the dialog shows the total the wallet pays, not only the requested amount`() {
        assertTrue(
            "onQuotePayment(" in block && Regex("""PaidActionCopy\.zec\(\s*\w+\.total""").containsMatchIn(block),
            "the pay-request confirm shows no exact total from the payment's quote:\n$block",
        )
        for (key in keys) assertTrue("R.string.$key" in block, "the pay-request confirm does not use $key")
    }

    @Test
    fun `the dialog has no hard-coded english and no US-only figures`() {
        for (literal in listOf("\"Send \$payZec ZEC?\"", "\"This sends \$payZec", "Locale.US", "Text(\"Cancel\")")) {
            assertTrue(literal !in block, "the pay-request confirm still has $literal:\n$block")
        }
    }

    /** Control: the floor for a 10,000-zatoshi request is at least the 26,000 zatoshi the device paid. */
    @Test
    fun `control - the one-memo payment floor covers the amount, the platform fee and the network fee`() {
        val total = onChainSendMinimumCost(1, Zatoshi(10_000L))
        assertTrue(total.value >= 26_000L, "floor ${total.value} is below what the wallet paid (26,000)")
        // The Spanish figure uses a decimal comma.
        assertTrue("," in PaidActionCopy.zec(total, Locale.forLanguageTag("es-ES")))
    }
}
