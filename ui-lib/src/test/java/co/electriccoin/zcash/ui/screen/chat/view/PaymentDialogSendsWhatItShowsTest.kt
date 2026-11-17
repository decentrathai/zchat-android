package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KI-3 (fix-all 2.13.0, train T4) — the Send Payment dialog (ChatDialogs.kt PaymentDialog) has a "Split Payment /
 * Divide total among people" card with a people counter and "Each person pays: <total/N> ZEC", but Send pays the full
 * typed amount to this one contact (`onSendPayment(amountZec, memo)`), and the button says "Send <total> ZEC". A user
 * who types a 1 ZEC bill and splits it by 4 reads 0.25 and pays 1.
 *
 * Wanted: the dialog shows no per-person figure at all; the only amount it passes to onSendPayment is the typed one,
 * and that is the figure its button names. Splitting a bill moves to Request Payment (RequestComposerOffersSplitTest).
 *
 * Reads the view's source, as the other copy tests of this package do.
 */
class PaymentDialogSendsWhatItShowsTest {
    /** The source of PaymentDialog, from its declaration to the time-lock dialog's banner. */
    private val dialog: String by lazy {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt")
        val start = src.indexOf("internal fun PaymentDialog(")
        val end = src.indexOf("// TIME-LOCK COMPOSER DIALOG")
        assertTrue(start >= 0 && end > start, "fixture: PaymentDialog and the time-lock banner in ChatDialogs.kt")
        src.substring(start, end)
    }

    @Test
    fun the_dialog_shows_no_split_and_no_per_person_figure() {
        val left =
            listOf(
                "splitEnabled",
                "splitCount",
                "perPersonAmount",
                "perPersonUsd",
                "send_payment_dialog_split_",
                "send_payment_dialog_each_pays",
                "send_payment_dialog_people",
            ).filter { it in dialog }
        assertTrue(left.isEmpty(), "the Send Payment dialog still shows a split of the amount it does not send: $left")
    }

    @Test
    fun send_pays_the_typed_amount_its_button_names() {
        val calls = Regex("""\bonSendPayment\(""").findAll(dialog).count()
        assertEquals(1, calls, "PaymentDialog must call onSendPayment exactly once")
        // D-6 (fix-all 2.13.0, owner W-10): Send also hands on the exact quote of that amount and memo.
        assertTrue(Regex("""onSendPayment\(amountZec, memo, \w+\.quote\)""").containsMatchIn(dialog), "Send does not pay the typed amount")
        assertTrue("val amountZec = amountText.toDoubleOrNull() ?: 0.0" in dialog, "amountZec is no longer the typed amount")
        assertTrue(
            "stringResource(R.string.send_payment_dialog_send_amount, zecFormat.format(amountZec))" in dialog,
            "the Send button does not name the amount Send pays",
        )
        // Every ZEC figure the dialog formats is the typed amount, the balance, what can go to others, or the fees.
        val formatted =
            Regex(
                """zecFormat\.format\(([^()]*(?:\([^()]*\))?[^()]*)\)"""
            ).findAll(dialog).map { it.groupValues[1].trim() }.toList()
        val allowed = listOf("amountZec", "balanceZec", "feesZec", "peerSendable.value.toDouble() / ZATOSHI_PER_ZEC")
        val other = formatted.filter { it !in allowed }
        assertTrue(other.isEmpty(), "PaymentDialog shows a ZEC figure that is not the amount it sends: $other")
    }
}
