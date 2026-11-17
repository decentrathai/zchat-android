package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-535 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §3.9) — the key-change reconnect's texts say more than the code
 * does once the question counts only the on-chain messages that are due.
 *
 * Before: paid_action_key_change_reconnect named both messages with no word about the one-hour rule, so a question for 1
 * message read as if both were sent; chat_key_changed_send_refused promised a reconnect of exactly "2 on-chain
 * messages"; and nothing told the user that a contact on ZChat 2.12.4 or older sees these messages only after updating.
 *
 * Wanted (app-texts: say only what the code does; English and Spanish, no placeholders):
 * - paid_action_key_change_reconnect is true for one message or two: whichever of the two went out in the last hour is
 *   not sent again;
 * - paid_action_key_change_reconnect_older is the same whole sentence set plus: this contact is on ZChat 2.12.4 or
 *   older, they won't see these on-chain messages until they update, nothing is lost;
 * - chat_key_changed_send_refused says "up to 2 on-chain messages" / "hasta 2 mensajes en la cadena".
 */
class KeyChangeReconnectQuestionTextsTest {
    private val en: Map<String, String> by lazy { ChatResourceText.strings("values") }
    private val es: Map<String, String> by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun the_reconnect_texts_are_in_english_and_spanish_without_placeholders() {
        for (key in KEYS) {
            val e = en[key]
            val s = es[key]
            assertNotNull(e, "no English $key")
            assertNotNull(s, "no Spanish $key")
            assertFalse("%" in e, "$key takes no argument: $e")
            assertFalse("%" in s, "$key takes no argument: $s")
        }
    }

    @Test
    fun the_question_is_true_for_one_message_or_two() {
        val e = en.getValue("paid_action_key_change_reconnect")
        val s = es.getValue("paid_action_key_change_reconnect")
        assertFalse(e == OLD_EN, "the English question still reads as if both messages were always sent")
        assertFalse(s == OLD_ES, "the Spanish question still reads as if both messages were always sent")
        assertEquals(
            "Reconnecting with this contact after their key changed: your key exchange, then your relay identity. " +
                "Whichever of the two already went out in the last hour is not sent again.",
            e,
        )
        assertTrue("una hora" in s || "última hora" in s, "the Spanish question does not name the hour: $s")
        assertTrue("no se vuelve a enviar" in s, "the Spanish question does not say it is not sent again: $s")
        assertTrue("intercambio de claves" in s && "identidad de relay" in s, s)
    }

    @Test
    fun the_older_contact_question_is_the_same_text_plus_what_an_older_contact_sees() {
        val plainEn = en.getValue("paid_action_key_change_reconnect")
        val plainEs = es.getValue("paid_action_key_change_reconnect")
        val e = en["paid_action_key_change_reconnect_older"]
        val s = es["paid_action_key_change_reconnect_older"]
        assertNotNull(e, "no English older-contact question")
        assertNotNull(s, "no Spanish older-contact question")
        assertEquals(
            "$plainEn This contact is on ZChat 2.12.4 or older: they won\'t see these on-chain messages until they update. " +
                "Nothing is lost.",
            e,
        )
        assertTrue(s.startsWith(plainEs), "the Spanish older-contact question is not the plain one plus a sentence: $s")
        for (words in listOf("ZChat 2.12.4 o anterior", "en la cadena", "hasta que actualice", "No se pierde nada")) {
            assertTrue(words in s, "the Spanish older-contact question does not say \"$words\": $s")
        }
        assertFalse("Vault" in e || "Vault" in s, "a text for a 2.13 user names the modes as the picker shows them")
    }

    @Test
    fun the_refusal_notice_promises_up_to_two_messages() {
        val e = en.getValue("chat_key_changed_send_refused")
        val s = es.getValue("chat_key_changed_send_refused")
        assertTrue("(up to 2 on-chain messages)" in e, "the English notice still promises exactly 2 messages: $e")
        assertTrue("(hasta 2 mensajes en la cadena)" in s, "the Spanish notice still promises exactly 2 messages: $s")
        assertTrue("asks" in e && "price" in e, "the English notice must still say it asks first and shows the price: $e")
        assertTrue("te pide" in s && "precio" in s, "the Spanish notice must still say it asks first and shows the price: $s")
    }

    private companion object {
        const val OLD_EN = "Reconnecting with this contact after their key changed: your key exchange, then your relay identity."
        const val OLD_ES =
            "Volver a conectar con este contacto tras el cambio de su clave: tu intercambio de claves y luego tu identidad de relay."
        val KEYS =
            listOf(
                "paid_action_key_change_reconnect",
                "paid_action_key_change_reconnect_older",
                "chat_key_changed_send_refused",
            )
    }
}
