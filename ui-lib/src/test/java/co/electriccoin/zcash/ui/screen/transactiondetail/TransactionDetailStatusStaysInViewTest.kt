package co.electriccoin.zcash.ui.screen.transactiondetail

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.KotlinUiLiterals
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ES-A with W-11 item (ii) (fix-all 2.13.0, train T4c; T4 phone step RESULT.md observation 3, Seeker 400 dp, es-ES):
 * on the expired TEX leg-2 detail the red recovery notice ("Tus ZEC volvieron a tu billetera …", about 12 lines in
 * Spanish) sits in the pinned bottom bar with the buttons. The bar takes most of the screen, so the details box above
 * it shows "Enviado a", the ID and the fee, and the Status row ("Estado: Caducada — nunca se confirmó") is below the
 * fold: it is reachable only by scrolling the details box (shots r1-s-T3b2-3-01 vs -02).
 *
 * What it must be: the pinned bar holds the buttons only; the notice is part of the scrolling content, after the
 * details, so the details box keeps its room and the Status row stays in view. JVM unit tests here have no Compose
 * layout (no Robolectric in ui-lib), so this pins the structure; the 400 dp bounds are measured on the phone.
 */
class TransactionDetailStatusStaysInViewTest {
    private val path = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/transactiondetail/TransactionDetailView.kt"

    @Test
    fun the_pinned_bottom_bar_holds_the_buttons_only() {
        val lexed = KotlinUiLiterals.lex(ChatResourceText.source(path))
        val bar = KotlinUiLiterals.block(lexed, "private fun BottomBar(")
        val code = lexed.blanked.substring(bar.first, bar.last + 1)
        assertFalse("TransactionErrorFooter(" in code, "the recovery notice is still pinned in the bottom bar above the buttons")
        assertFalse("errorFooter" in code, "the bottom bar still reads the error footer")
    }

    @Test
    fun the_notice_scrolls_after_the_details() {
        val lexed = KotlinUiLiterals.lex(ChatResourceText.source(path))
        val view = KotlinUiLiterals.block(lexed, "fun TransactionDetailView(")
        val code = lexed.blanked.substring(view.first, view.last + 1)
        val scroll = code.indexOf(".verticalScroll(scrollState)")
        val info = code.indexOf("when (state.info)")
        val footer = code.indexOf("TransactionErrorFooter(")
        val bottomBar = code.indexOf("BottomBar(")
        assertTrue(scroll >= 0 && info > scroll, "fixture: the details scroll")
        assertTrue(footer > info, "the recovery notice must be shown after the details, in the scrolling content")
        assertTrue(bottomBar < 0 || footer < bottomBar, "the recovery notice must come before the pinned bottom bar")
    }
}
