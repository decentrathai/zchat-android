package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 lane k1 (ZC-1792, ZC-1632, ZC-1793, ZC-1575, ZC-1417; owner W-137) — the new texts, English and Spanish in step
 * (resource-text pattern of Qa2ChatScreenCopyTest / HonestCopySecondPassTest).
 *
 * Every Spanish line is a DRAFT for the reviewer round (app-texts rule 9): neutral Latin American Spanish, "tu contacto",
 * "pide", "añade". Honesty rules: a text says what was and was not paid, never promises a retry the code does not make, and
 * never names Reset or Re-establish for a message they cannot bring back (F3).
 *
 * Keys (all in ui/common/values and values-es):
 * - chat_send_accept_first_not_sent (= ChatSendCopy.ACCEPT_FIRST_NOT_SENT): the F1 refusal, chat_send_strings.xml
 * - chat_decrypt_placeholder_before_key_exchange: F2, a message mined before the chat's first KEXACK, chat_menu_composer_strings.xml
 * - chat_decrypt_placeholder_awaiting_answer: the pre-handshake "yet" bubble with the two cases, chat_menu_composer_strings.xml
 * - chat_send_insufficient_balance_have_need: ZC-1417, %1$s = ZEC that can be spent now, %2$s = ZEC the message needs,
 *   chat_send_strings.xml (holds none of the words the error mapper keys on: "Insufficient balance", "pending", "network")
 */
class Qa2K1CopyTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private fun enText(key: String): String = assertNotNull(en[key], "values has no $key")

    private fun esText(key: String): String = assertNotNull(es[key], "values-es has no $key")

    private fun assertText(
        key: String,
        english: String,
        spanish: String,
    ) {
        assertEquals(english, enText(key), "$key (English)")
        assertEquals(spanish, esText(key), "$key (Spanish draft)")
    }

    private fun copyConstant(name: String): String {
        val field = ChatSendCopy::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(null) as String
    }

    @Test
    fun f1_the_refusal_says_nothing_was_paid_and_to_accept_first() {
        assertText(
            "chat_send_accept_first_not_sent",
            "Not sent, and nothing was paid. Your contact started a key exchange in this chat and it is waiting for " +
                "your Accept. A Shielded message sent before you accept could not be opened by your contact. Tap " +
                "Accept first (ZChat shows the price), then send again.",
            "No se envió y no se pagó nada. Tu contacto inició un intercambio de claves en este chat y espera que lo " +
                "aceptes. Un mensaje Shielded enviado antes de aceptar no lo podría abrir tu contacto. Primero toca " +
                "Aceptar (ZChat muestra el precio) y luego vuelve a enviar.",
        )
    }

    @Test
    fun f1_the_kotlin_text_is_the_english_resource_and_the_screen_maps_it() {
        val constant =
            try {
                copyConstant("ACCEPT_FIRST_NOT_SENT")
            } catch (e: NoSuchFieldException) {
                throw AssertionError("ChatSendCopy has no ACCEPT_FIRST_NOT_SENT", e)
            }
        assertEquals(enText("chat_send_accept_first_not_sent"), constant, "the Kotlin text and the English resource differ")
        val resource = ChatSendCopy.localizedResource(constant)
        assertNotNull(resource, "the screen would show the English text on a Spanish phone")
        assertEquals(R.string::class.java.getField("chat_send_accept_first_not_sent").getInt(null), resource)
    }

    @Test
    fun f2_a_message_sent_before_the_key_exchange_finished_says_so() {
        assertText(
            "chat_decrypt_placeholder_before_key_exchange",
            "🔐 Can't read this message. It was sent before the key exchange in this chat finished, so this phone has " +
                "no key for it. Ask your contact to send it again.",
            "🔐 No se puede leer este mensaje. Se envió antes de que terminara el intercambio de claves de este chat, " +
                "así que este teléfono no tiene la clave. Pide a tu contacto que lo envíe de nuevo.",
        )
    }

    /**
     * QA2 k1b (GPT review of K1, [test-change-ok: ZC-1632 text]): the bubble says what this phone knows. It has not received
     * the contact's answer; whether the contact answered is not known here (the answer may be on its way).
     *
     * ZC-1829 (QA2 pa3, [test-change-ok: ZC-1829 text]): since P-A (ZC-1792, W-142) a message the contact sealed before
     * answering opens with the root as it stood at its height once the answer is mined, so "it will stay unreadable: ask
     * them to send it again" is false and asks for a paid resend nobody needs. The bubble says it should open once the
     * answer arrives, and only asks for a resend if it still cannot be read then.
     */
    @Test
    fun f2_the_pre_handshake_bubble_names_both_cases() {
        assertText(
            "chat_decrypt_placeholder_awaiting_answer",
            "🔐 Can't read this message yet. This phone has not received your contact's answer to your key exchange " +
                "yet. It should open once their answer arrives. If it still can't be read then, ask them to send it again.",
            "🔐 Aún no se puede leer este mensaje. Este teléfono todavía no ha recibido la respuesta de tu contacto a " +
                "tu intercambio de claves. Debería abrirse cuando llegue su respuesta. Si después sigue sin poder leerse, " +
                "pídele que lo envíe de nuevo.",
        )
        for (text in listOf(enText("chat_decrypt_placeholder_awaiting_answer"), esText("chat_decrypt_placeholder_awaiting_answer"))) {
            assertFalse("has not answered" in text || "no ha respondido" in text, "the bubble claims the contact did not answer")
            assertFalse(
                "stay unreadable" in text || "seguirá sin poder leerse:" in text,
                "ZC-1829: the bubble says a message sent before the answer never opens, but P-A opens it",
            )
        }
    }

    @Test
    fun f2_the_new_bubbles_do_not_offer_a_reset_that_cannot_help() {
        for (key in listOf("chat_decrypt_placeholder_before_key_exchange", "chat_decrypt_placeholder_awaiting_answer")) {
            for ((language, text) in listOf("en" to enText(key), "es" to esText(key))) {
                assertFalse("reset" in text.lowercase(), "$key ($language) names Reset")
                assertFalse("re-establish" in text.lowercase() || "restablec" in text.lowercase(), "$key ($language) names Re-establish")
                assertFalse("restore" in text.lowercase() || "restaur" in text.lowercase(), "$key ($language) blames a wallet restore")
            }
        }
    }

    /**
     * The Reset encryption dialog tells the user to use it "only if messages here show 'its decryption key isn't on this
     * device'". That must stay the genuine-desync bubble and must not be the text of the two new bubbles.
     */
    @Test
    fun f2_the_reset_dialog_still_points_at_the_genuine_desync_text() {
        fun quoted(
            body: String,
            open: Char,
            close: Char,
        ): String {
            val inside = body.substringAfter(open).substringBefore(close)
            return inside.trimEnd('.').replace('’', '\'')
        }

        val enQuoted = quoted(enText("chat_reset_encryption_body"), '“', '”')
        assertTrue(enQuoted.isNotBlank() && "decryption key" in enQuoted, "the English dialog quotes: $enQuoted")
        assertTrue(enQuoted in enText("chat_decrypt_placeholder_key_missing"), "the quoted words are not the genuine-desync bubble")
        for (key in listOf("chat_decrypt_placeholder_before_key_exchange", "chat_decrypt_placeholder_awaiting_answer")) {
            assertFalse(enQuoted in enText(key), "$key carries the words the Reset dialog sends users to Reset for")
        }
        val esQuoted = quoted(esText("chat_reset_encryption_body"), '«', '»')
        assertTrue(esQuoted.isNotBlank() && "clave de descifrado" in esQuoted, "the Spanish dialog quotes: $esQuoted")
        assertTrue(esQuoted in esText("chat_decrypt_placeholder_key_missing"), "the quoted Spanish words are not the genuine-desync bubble")
        for (key in listOf("chat_decrypt_placeholder_before_key_exchange", "chat_decrypt_placeholder_awaiting_answer")) {
            assertFalse(esQuoted in esText(key), "$key (es) carries the words the Reset dialog sends users to Reset for")
        }
    }

    @Test
    fun zc1417_the_shortfall_text_shows_what_the_wallet_has_and_what_the_message_needs() {
        assertText(
            "chat_send_insufficient_balance_have_need",
            "Not enough ZEC that can be spent now: you have %1\$s ZEC and this Shielded message needs %2\$s ZEC. Nothing " +
                "was paid. Add ZEC and try again. Open chats are free, but only with a contact whose chat key you have " +
                "(⋮ → Conversation mode).",
            "No hay suficiente ZEC que se pueda gastar ahora: tienes %1\$s ZEC y este mensaje Shielded necesita %2\$s " +
                "ZEC. No se pagó nada. Añade ZEC e inténtalo de nuevo. Los chats Open son gratis, pero solo con un " +
                "contacto cuya clave de chat tengas (⋮ → Modo de conversación).",
        )
        for (text in listOf(enText("chat_send_insufficient_balance_have_need"), esText("chat_send_insufficient_balance_have_need"))) {
            assertFalse("0.00017" in text || "0,00017" in text, "the figure is the old static floor")
            for (word in listOf("Insufficient balance", "pending", "network")) {
                assertFalse(word in text, "the error mapper keys on \"$word\"")
            }
        }
    }
}
