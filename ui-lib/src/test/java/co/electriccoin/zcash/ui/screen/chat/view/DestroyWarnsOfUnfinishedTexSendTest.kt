package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZC-886, the Destroy half (register, high; prep of 19 Oct 2026) — a payment to an exchange (TEX) address goes in two
 * steps through a one-time address of this wallet. Destroy All cannot wait or ask when it is triggered (W-52), and today
 * nothing says that a payment still between its two steps is left at that one-time address, where only the recovery
 * phrase reaches it again.
 *
 * Review b of 19 Oct (finding 2, P1): whether a restore from the recovery phrase finds ZEC at that one-time address is
 * not known (the SDK scan was never checked), so the line promises no way back; it says what is known: the ZEC stays at
 * a one-time address of this wallet and ZCHAT cannot bring it back after Destroy All.
 *
 * Wanted: one plain line, EN and ES, on the Destroy confirm (the chat list's final dialog, beside what happens to the
 * wallet, and Emergency Destroy's first screen) and on the Destroy PIN set-up's first step: wait for such a payment to
 * finish first; an unfinished one leaves its ZEC at the one-time address, which only the recovery phrase reaches again.
 * It never says the ZEC is lost or gone.
 */
class DestroyWarnsOfUnfinishedTexSendTest {
    private fun code(path: String): String {
        val file = File(ChatResourceText.repoRoot, path)
        assertTrue("fixture: $path", file.isFile)
        return SourceScan.stripComments(file.readText())
    }

    @Test
    fun `the line exists in English and Spanish and says what is true`() {
        val en = ChatResourceText.strings("values")[KEY] ?: throw AssertionError("no English $KEY")
        val es = ChatResourceText.strings("values-es")[KEY] ?: throw AssertionError("no Spanish $KEY")
        assertNotEquals("$KEY is not translated", en, es)
        for (words in listOf("TEX", "one-time address", "cannot bring", "finish")) {
            assertTrue("the English line does not say \"$words\": $en", words in en)
        }
        for (words in listOf("TEX", "un solo uso", "no puede devolver", "termine")) {
            assertTrue("the Spanish line does not say «$words»: $es", words in es)
        }
        // ZC-886 review b: whether a restore from the recovery phrase finds that ZEC is not known, so no line promises it.
        for (claim in listOf("lost", "gone", "cannot be recovered", "recovery phrase", "reached again")) {
            assertFalse("the English line promises \"$claim\": $en", claim in en.lowercase())
        }
        for (claim in listOf("perdid", "para siempre", "frase de recuperación", "volver a llegar")) {
            assertFalse("the Spanish line promises «$claim»: $es", claim in es.lowercase())
        }
    }

    @Test
    fun `the chat list's final Destroy dialog says it beside what happens to the wallet`() {
        val list = code("$UI/screen/chat/view/ChatListView.kt")
        val at = list.indexOf("R.string.$KEY")
        assertTrue("the chat list's final Destroy dialog does not say it", at >= 0)
        val note = list.indexOf("R.string.chat_destroy_final_wallet_note")
        val settings = list.indexOf("R.string.chat_destroy_final_item_settings")
        assertTrue("the line is not beside what happens to the wallet", at in note..settings)
    }

    @Test
    fun `Emergency Destroy's first screen and the Destroy PIN set-up say it`() {
        assertTrue("Emergency Destroy does not say it", "R.string.$KEY" in code("$UI/screen/enhanceddestroy/view/EnhancedDestroyView.kt"))
        assertTrue("the Destroy PIN set-up does not say it", "R.string.$KEY" in code("$UI/screen/onboarding/view/DestroyPinSetupView.kt"))
    }

    private companion object {
        const val KEY = "destroy_tex_send_not_finished"
        const val UI = "ui-lib/src/main/java/co/electriccoin/zcash/ui"
    }
}
