package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Round 8 P10 (PHONE_TRIAGE ES-5) — the bottom tabs and the Wallet tab's buttons are English on a Spanish phone:
 * "Wallet" and "More" in the tab bar of WalletTabView, ChatListView and AiTabView, and the Wallet tab's header and its
 * Receive / Send / Swap buttons. The Spanish words already exist (home_button_receive / send / swap / more); "Wallet"
 * gets a new tab_wallet ("Billetera").
 */
class SpanishBottomTabsTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val files =
        mapOf(
            "WalletTabView" to "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/wallettab/WalletTabView.kt",
            "ChatListView" to "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt",
            "AiTabView" to "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiTabView.kt",
        )

    @Test
    fun no_tab_bar_says_wallet_or_more_in_english() {
        files.forEach { (name, path) ->
            val src = ChatResourceText.source(path)
            for (literal in listOf("\"Wallet\"", "\"More\"")) {
                assertTrue(literal !in src, "$name still shows $literal")
            }
            assertTrue("R.string.tab_wallet" in src, "$name does not show tab_wallet")
            assertTrue("R.string.home_button_more" in src, "$name does not show home_button_more")
        }
    }

    @Test
    fun the_wallet_tab_buttons_use_the_existing_spanish_words() {
        val src = ChatResourceText.source(files.getValue("WalletTabView"))
        for (literal in listOf("\"Receive\"", "\"Send\"", "\"Swap\"")) {
            assertTrue(literal !in src, "WalletTabView still shows $literal")
        }
        for (key in listOf("home_button_receive", "home_button_send", "home_button_swap")) {
            assertTrue("R.string.$key" in src, "WalletTabView does not show $key")
        }
    }

    @Test
    fun tab_wallet_is_wallet_and_billetera() {
        assertEquals("Wallet", en["tab_wallet"], "English tab_wallet")
        assertEquals("Billetera", es["tab_wallet"], "Spanish tab_wallet")
        // The reused keys are the words the old literals said, with a Spanish translation where the word differs.
        assertEquals("Receive", en["home_button_receive"])
        assertEquals("Send", en["home_button_send"])
        assertEquals("Swap", en["home_button_swap"])
        assertEquals("More", en["home_button_more"])
        for (key in listOf("home_button_receive", "home_button_send", "home_button_more")) {
            assertNotEquals(en[key], es[key], "$key is not translated")
        }
    }
}
