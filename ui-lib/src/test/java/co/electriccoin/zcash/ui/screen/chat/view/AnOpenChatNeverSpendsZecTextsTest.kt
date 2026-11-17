package co.electriccoin.zcash.ui.screen.chat.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T7BH-N2 (register ZC-880; owner rule W-57) — the texts of "an Open chat never spends ZEC" (T7B-HANDSHAKE-SPEC rev 2,
 * section 5, T11 and section 2.24 "Texts"):
 * - T11 `chat_open_no_onchain_step`, EN and ES as designed, shown where an Open chat's Reset, accept or reconnect did its
 *   free part and skipped a paid step; the chat code maps it (ChatSendCopy) so the screen shows the translation;
 * - the paid-reconnect sentence of `chat_key_changed_send_refused_no_key` no longer names Open (an Open chat pays no
 *   reconnect);
 * - honest-copy pin: the mode texts that promise no ZEC ("Open (free, via relays)", "no ZEC") stay; "no ZEC, ever" is
 *   gone since QA2 Q2-51 (an Open chat's first attachment pays a key exchange).
 */
class AnOpenChatNeverSpendsZecTextsTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun t11_says_an_open_chat_paid_nothing_in_english_and_spanish() {
        assertEquals(T11_EN, en["chat_open_no_onchain_step"], "English T11")
        assertEquals(T11_ES, es["chat_open_no_onchain_step"], "Spanish T11")
        val copy = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ChatSendCopy.kt")
        assertTrue("R.string.chat_open_no_onchain_step" in copy, "ChatSendCopy does not map T11, so the screen would show English")
    }

    @Test
    fun the_key_changed_text_no_longer_says_an_open_chat_pays_a_reconnect() {
        val english = assertNotNull(en["chat_key_changed_send_refused_no_key"])
        val spanish = assertNotNull(es["chat_key_changed_send_refused_no_key"])
        assertFalse("Tunnel or Open chat ZChat first asks you to pay" in english, "EN still says an Open chat pays: $english")
        assertFalse("Tunnel u Open, ZChat primero te pide pagar" in spanish, "ES still says an Open chat pays: $spanish")
    }

    @Test
    fun pin_the_open_mode_texts_promise_no_zec() {
        assertEquals("Open (free, via relays)", en["chat_mode_label_open"])
        assertTrue("no ZEC" in en.getValue("chat_mode_picker_desc_open"))
        // QA2 Q2-51 (W-137, ZC-1593): an Open chat's attachments pay a key exchange, so "no ZEC, ever" is gone.
        assertFalse("no ZEC, ever" in en.getValue("new_chat_open_needs_code_body"))
        assertTrue("no ZEC" in en.getValue("new_chat_mode_blurb_open"))
    }

    private companion object {
        const val T11_EN =
            "This is an Open chat, so ZChat paid nothing and sent nothing on-chain. The rest of this step needs a Tunnel or " +
                "Shielded chat; you can switch the mode in this chat's menu."
        const val T11_ES =
            "Este es un chat Open, así que ZChat no pagó nada ni envió nada en la cadena. El resto de este paso necesita un " +
                "chat Tunnel o Shielded; puedes cambiar el modo en el menú de este chat."
    }
}
