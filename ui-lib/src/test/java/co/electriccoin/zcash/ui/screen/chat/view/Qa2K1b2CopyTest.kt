package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 lane k1b2 (ZC-1843, ZC-1844; Fable review of k1b, 13 Nov) — the two new send refusals, English and Spanish in step
 * (pattern of Qa2K1CopyTest). Every Spanish line is a DRAFT for the reviewer round (app-texts rule 9).
 *
 * - chat_send_accept_first_setup_paid (= ChatSendCopy.ACCEPT_FIRST_NOT_SENT_SETUP_PAID): the Accept-first refusal at the
 *   seal after this run paid the chat's key exchange. It never says nothing was paid.
 * - chat_send_key_exchange_finishing_not_sent (= ChatSendCopy.KEY_EXCHANGE_FINISHING_NOT_SENT): the user accepted the
 *   contact's new key and our own key exchange for it is out or owed; no Accept can help (ANSWERED_BY_OUR_KEX), so the
 *   text names what is waiting and does not send the user to an Accept.
 */
class Qa2K1b2CopyTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private fun enText(key: String): String = assertNotNull(en[key], "values has no $key")

    private fun esText(key: String): String = assertNotNull(es[key], "values-es has no $key")

    private fun copyConstant(name: String): String =
        try {
            ChatSendCopy::class.java.getField(name).get(null) as String
        } catch (e: NoSuchFieldException) {
            throw AssertionError("ChatSendCopy has no $name", e)
        }

    private fun assertMapped(
        constant: String,
        key: String,
    ) {
        val text = copyConstant(constant)
        assertEquals(enText(key), text, "$constant: the Kotlin text and the English resource differ")
        assertEquals(
            R.string::class.java.getField(key).getInt(null),
            ChatSendCopy.localizedResource(text),
            "$constant: the screen would show the English text on a Spanish phone",
        )
    }

    @Test
    fun zc1843_the_refusal_after_a_paid_setup_says_the_setup_went_out() {
        val key = "chat_send_accept_first_setup_paid"
        assertEquals(
            "Not sent. The setup for this chat went out, but this message did not: your contact started a key exchange " +
                "in this chat and it is waiting for your Accept. A Shielded message sent before you accept could not be " +
                "opened by your contact. Tap Accept first (ZChat shows the price), then tap Retry.",
            enText(key),
        )
        assertEquals(
            "No se envió. La configuración de este chat ya salió, pero este mensaje no: tu contacto inició un " +
                "intercambio de claves en este chat y espera que lo aceptes. Un mensaje Shielded enviado antes de " +
                "aceptar no lo podría abrir tu contacto. Primero toca Aceptar (ZChat muestra el precio) y luego toca " +
                "Reintentar.",
            esText(key),
        )
        assertFalse("nothing was paid" in enText(key).lowercase() || "no se pagó nada" in esText(key).lowercase())
        assertMapped("ACCEPT_FIRST_NOT_SENT_SETUP_PAID", key)
    }

    /**
     * ZC-1860 (QA2 pa3, Fable re-review of k1b2, [test-change-ok: ZC-1860 text]): "Send again once their answer has
     * arrived" was false: the contact's answer does not reopen the gate (answeredByOurKeyExchange holds while our key
     * exchange's paid-setup cooldown runs, up to an hour after it is sent), and after it the Accept-first gate may pay one more answer
     * (automatically under the handshake consent: retryKexAckOnce, ZC-1849). The text says what the code does.
     */
    @Test
    fun zc1844_the_refusal_while_our_key_exchange_finishes_names_what_waits() {
        // ZC-1867 (QA2 pa4, [test-change-ok: ZC-1867 text]): after the hour the Accept-first gate asks for an Accept (or pays
        // the one answer by itself under the handshake consent), so the text names that step after the wait, never now.
        val key = "chat_send_key_exchange_finishing_not_sent"
        assertEquals(
            "Not sent, and this message was not paid for. You accepted this contact's new key and your own key " +
                "exchange for it is sent or still waiting to go. Shielded messages in this chat wait until it finishes, " +
                "up to an hour after it is sent, even if their answer arrives sooner. After that, tap Accept when ZChat " +
                "asks (it shows the price; if you allowed handshake transactions it may pay this one answer by itself), " +
                "then send again.",
            enText(key),
        )
        assertEquals(
            "No se envió y este mensaje no se pagó. Aceptaste la nueva clave de este contacto y tu propio intercambio " +
                "de claves para ella ya se envió o aún espera para salir. Los mensajes Shielded de este chat esperan a " +
                "que termine, hasta una hora después de que se envíe tu intercambio de claves, aunque su respuesta " +
                "llegue antes. Después, toca Aceptar cuando ZChat lo pida (muestra el precio; si permitiste las " +
                "transacciones de enlace, puede pagar esa respuesta por sí solo) y vuelve a enviarlo.",
            esText(key),
        )
        for ((language, text) in listOf("en" to enText(key), "es" to esText(key))) {
            assertFalse(
                "once their answer has arrived" in text || "cuando haya llegado su respuesta" in text,
                "ZC-1860: $key ($language) promises the answer reopens sending; it does not",
            )
        }
        for ((language, text) in listOf("en" to enText(key), "es" to esText(key))) {
            val accept = maxOf(text.indexOf("tap Accept"), text.indexOf("toca Aceptar"))
            val after = maxOf(text.indexOf("After that"), text.indexOf("Después"))
            assertTrue(accept < 0 || (after in 0 until accept), "$key ($language) sends the user to an Accept before the wait ends")
            assertFalse("nothing was paid" in text.lowercase() || "no se pagó nada" in text.lowercase(), "$key ($language)")
        }
        assertMapped("KEY_EXCHANGE_FINISHING_NOT_SENT", key)
    }
}
