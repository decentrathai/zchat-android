package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ES-A with W-11 item (iii) (fix-all 2.13.0, train T4c; T4 phone step RESULT.md observation 4, KI-3 (d), Seeker
 * 400 dp, es-ES): with "Split a bill" on, the Request dialog's scroll area ends inside the reason field's examples
 * line ("p. ej., «Cena a medias», «Alquiler», «Entradas de cine»"): its second line is cut in half at the bottom edge.
 * With the split on, the field already holds the reason that goes ("Parte de 0,003 ZEC, dividido entre 3"), and the
 * split card, the counter and the share line take the height the examples line needs.
 *
 * What it must be: the examples line shows while the reason is the user's own to write (split off), where the dialog
 * is a split card shorter and the line fits whole; with the split on it is not shown, as the prefilled reason is the
 * example. JVM unit tests here have no Compose layout, so this pins the structure; the 400 dp bounds are measured on
 * the phone.
 */
class RequestReasonHintShowsInFullTest {
    @Test
    fun the_examples_line_shows_only_while_the_reason_is_the_users_own() {
        val lexed =
            KotlinUiLiterals.lex(
                ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt")
            )
        val dialog = KotlinUiLiterals.block(lexed, "internal fun PaymentRequestComposerDialog(")
        val code = lexed.blanked.substring(dialog.first, dialog.last + 1)
        assertEquals(1, Regex("""R\.string\.request_payment_reason_examples""").findAll(code).count(), "one examples line")
        assertTrue(
            Regex(
                """if\s*\(\s*splitOn\s*\)\s*null\s*else\s*\(?\s*\{\s*Text\(\s*stringResource\(\s*R\.string\.request_payment_reason_examples"""
            ).containsMatchIn(code),
            "with the split on the examples line is cut at the scroll area's edge at 400 dp: show it only with the split off",
        )
    }
}
