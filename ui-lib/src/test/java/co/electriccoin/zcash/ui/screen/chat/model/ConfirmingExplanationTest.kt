package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RED pin for the "your funds are confirming" refusal being uninformative.
 *
 * The dialog said only "Your Ironwood funds are still confirming. Wait for the confirmation, then try
 * again." Three questions it never answered, all of which the owner asked after hitting it on the HONOR
 * on 2026-09-09: what IS a confirmation and how many are needed, WHICH transaction are we waiting on,
 * and — the sharp one — why does the balance say I have money if I cannot spend it?
 *
 * That last question is a real defect, not a wording problem. The headline balance sums the sealed
 * Orchard pool (which cannot pay another person at all) and value that is still confirming, so it is
 * routinely larger than what can actually be sent. The explanation has to say so out loud.
 *
 * Real numbers from the HONOR at the moment of the refusal: 495,223 zatoshi of own change at 2 of 3
 * confirmations, plus 139,000 zatoshi stranded in Orchard.
 */
class ConfirmingExplanationTest {
    private val honor =
        confirmingExplanation(
            progress = ConfirmationProgress(confirmations = 2, required = TRUSTED_CONFIRMATIONS),
            pending = Zatoshi(495_223),
            txAbbrev = "2d7e4d81",
            isOwnChange = true,
            orchardStranded = Zatoshi(139_000),
        )

    @Test
    fun it_explains_what_a_confirmation_is_and_how_many_are_needed() {
        val all = honor.joinToString(" | ")

        assertTrue(all.contains("2/3 confirmations"), "the countdown must be present: $all")
        assertTrue(
            all.contains("3 confirmations", ignoreCase = true) && all.contains("10", ignoreCase = true),
            "both rules — 3 for your own change, 10 for funds from someone else — must be stated: $all",
        )
    }

    /** Which transaction, and how much of it — so the user can find it in their activity list. */
    @Test
    fun it_names_the_transaction_and_the_amount_being_waited_on() {
        val all = honor.joinToString(" | ")

        assertTrue(all.contains("2d7e4d81"), "the transaction must be identifiable: $all")
        assertTrue(all.contains("0.00495223"), "the pending amount must be shown in ZEC: $all")
    }

    /** The question the owner actually asked: the balance says I have money, so why must I wait? */
    @Test
    fun it_reconciles_the_balance_with_what_can_actually_be_sent() {
        val all = honor.joinToString(" | ")

        assertTrue(all.contains("0.00139"), "the stranded Orchard amount must be named: $all")
        assertTrue(
            all.contains("balance", ignoreCase = true),
            "it must say why the balance looks larger than what is spendable: $all",
        )
    }

    /** Funds received from someone else take 10, and the copy must adapt rather than always say 3. */
    @Test
    fun a_third_party_receipt_is_described_as_ten_confirmations() {
        val lines =
            confirmingExplanation(
                progress = ConfirmationProgress(confirmations = 4, required = UNTRUSTED_CONFIRMATIONS),
                pending = Zatoshi(530_000),
                txAbbrev = "81807248",
                isOwnChange = false,
                orchardStranded = Zatoshi(0),
            )
        val all = lines.joinToString(" | ")

        assertTrue(all.contains("4/10 confirmations"), all)
        assertTrue(all.contains("someone else", ignoreCase = true) || all.contains("received", ignoreCase = true), all)
    }

    /** With no Orchard stranded there is nothing to reconcile, so that line must not appear at all. */
    @Test
    fun the_orchard_line_is_omitted_when_there_is_no_stranded_value() {
        val lines =
            confirmingExplanation(
                progress = ConfirmationProgress(confirmations = 1, required = TRUSTED_CONFIRMATIONS),
                pending = Zatoshi(1_000),
                txAbbrev = null,
                isOwnChange = true,
                orchardStranded = Zatoshi(0),
            )

        assertTrue(lines.none { it.contains("Orchard") }, "no Orchard line when nothing is stranded: $lines")
    }

    /** Degrades safely: unknown progress must still produce a usable explanation, never a crash or "null". */
    @Test
    fun it_still_explains_itself_when_the_countdown_is_unknown() {
        val lines =
            confirmingExplanation(
                progress = null,
                pending = Zatoshi(495_223),
                txAbbrev = null,
                isOwnChange = true,
                orchardStranded = Zatoshi(139_000),
            )

        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { it.contains("null") }, lines.toString())
        assertEquals(lines, lines.filter { it.isNotBlank() }, "no blank lines")
    }
}
