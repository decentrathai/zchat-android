package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1685, QA2 text lane t1: the accepted rows of the three text reviews (Grok w2, GPT w1, Grok w3 chunks 1 to 4;
 * triage in the lane's report). Each row is a text that says more or less than the code does, or reads as something
 * else. Texts only; resources are read as XML, so this file compiles before the fix.
 *
 * - call_remote_camera_off: CallOverlay shows it when the call is connected and no remote video frame has arrived,
 *   which is also true before the first frame; it must not name a cause ("camera is off").
 * - chat_menu_reconnect_subtitle: Reconnect pays the owed handshake at once only under the standing consent
 *   (ZC-1729); otherwise it asks. "as you allowed" / "como autorizaste" promised a permission that may not exist.
 * - send_confirmation_success_subtitle_transparent: shown at the broadcast (SubmitResult.Success), before any
 *   confirmation; it must say so, like send_confirmation_success_subtitle does. The "Sent!" title and the
 *   "on its way" sending line stay (ZC-1734, Q2-10).
 * - new_chat_open_warning_body: "gift-wrapped" is jargon nobody can read in either language.
 * - the other rows are wording fixes (relays do not encrypt; "bookmark" is a marcador; "la imagen" agrees with
 *   "la verá"; "their" key exchange is yours).
 * Not here (need Kotlin twins of the resource, open for a code lane): chat_mode_picker_desc_tunnel and
 * new_chat_mode_blurb_tunnel (modeBlurb / composeBlurb must change with them).
 * The Spanish lines are agent drafts for the train's REVIEW.md list (app-texts rule 9).
 */
class Qa2T1TextsSayWhatTheyMeanTest {
    private fun load(dir: String) = ChatResourceText.strings(dir).mapValues { it.value.replace("\\n", "\n") }

    private val en by lazy { load("values") }
    private val es by lazy { load("values-es") }

    private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toSet()

    private fun both(
        key: String,
        english: String?,
        spanish: String?,
    ) {
        if (english != null) assertEquals(english, assertNotNull(en[key], "values has no $key"), "$key (English)")
        if (spanish != null) assertEquals(spanish, assertNotNull(es[key], "values-es has no $key"), "$key (Spanish)")
    }

    @Test
    fun `the remote camera line says what the screen knows, not a cause`() {
        both("call_remote_camera_off", "No picture from your contact", "No llega la imagen de tu contacto")
        assertFalse("camera is off" in en.getValue("call_remote_camera_off").lowercase())
        assertFalse("apagada" in es.getValue("call_remote_camera_off").lowercase())
    }

    @Test
    fun `Reconnect says a paid handshake goes without a question only after the standing permission`() {
        both(
            "chat_menu_reconnect_subtitle",
            "Free if the relays can reach your contact. Otherwise an on-chain handshake transaction of at least " +
                "0.00017 ZEC. If you have allowed handshake transactions, it is paid without another question; " +
                "otherwise nothing is paid until you agree. Your contact may need to update ZChat.",
            "Gratis si los relays llegan a tu contacto. Si no, se envía una transacción de enlace en la cadena " +
                "que cuesta al menos 0,00017 ZEC. Si ya permitiste las transacciones de enlace, se paga sin otra " +
                "pregunta; si no, no se paga nada hasta que aceptes. Puede que tu contacto tenga que actualizar ZChat.",
        )
        assertFalse("as you allowed" in en.getValue("chat_menu_reconnect_subtitle"))
        assertFalse("como autorizaste" in es.getValue("chat_menu_reconnect_subtitle"))
    }

    @Test
    fun `the shield screen shown at the broadcast says the transaction is not confirmed yet`() {
        both(
            "send_confirmation_success_subtitle_transparent",
            "Your ZEC is being shielded. Sent to the Zcash network — not confirmed yet.",
            "Tu ZEC se está protegiendo. Enviado a la red Zcash — aún sin confirmar.",
        )
        // The decided title and sending line stay (ZC-1734; Q2-10).
        both("send_confirmation_success_title_transparent", "Sent!", "¡Enviado!")
        assertTrue(en.getValue("send_confirmation_sending_subtitle").startsWith("Your ZEC is on its way to"))
    }

    @Test
    fun `the Open warning does not use the word gift-wrapped in either language`() {
        for ((name, strings) in listOf("values" to en, "values-es" to es)) {
            val text = strings.getValue("new_chat_open_warning_body")
            assertFalse("gift-wrapped" in text.lowercase(), "$name: new_chat_open_warning_body still says gift-wrapped")
            assertFalse("gift wrapped" in text.lowercase(), "$name: new_chat_open_warning_body still says gift wrapped")
        }
        assertTrue("end-to-end-encrypted DM over Nostr relays" in en.getValue("new_chat_open_warning_body"))
        assertTrue("cifrado de extremo a extremo por los relays de Nostr" in es.getValue("new_chat_open_warning_body"))
    }

    @Test
    fun `relays carry the encryption, they do not do it`() {
        both(
            "chat_menu_e2e_subtitle_open",
            null,
            "Cifrado de extremo a extremo; se envía por los relays de Nostr. Los chats Open no tienen número de " +
                "seguridad que comparar; para verificar la clave de tu contacto, cambia a Shielded o Tunnel.",
        )
        val note = es.getValue("chat_mode_note_open_body")
        assertTrue(
            "mensajes gratis con cifrado de extremo a extremo, enviados por tres relays de Nostr" in note,
            "chat_mode_note_open_body (Spanish) still lets the relays do the encrypting: ${note.take(160)}",
        )
        assertFalse("cifrados de extremo a extremo por tres relays" in note)
    }

    @Test
    fun `the onboarding subtitle says who pays`() {
        both(
            "onboarding_identity_subtitle",
            "Share your invite code. Open is free; in Tunnel you pay a setup per contact, in Shielded per message.",
            null,
        )
        assertFalse("Tunnel pays" in en.getValue("onboarding_identity_subtitle"))
        assertFalse("Shielded pays" in en.getValue("onboarding_identity_subtitle"))
    }

    @Test
    fun `the Tunnel setup chip names the messages as what travels over the relays`() {
        both("chat_privacy_chip_tunnel_setup", "Setup is paid on-chain; then messages go over Nostr relays", null)
    }

    @Test
    fun `the Address Book prompt and the call duration read plainly`() {
        both(
            "authentication_address_book_subtitle",
            "To open the Address Book, use your phone's unlock method.",
            null,
        )
        both("call_duration_a11y", "Call duration. Minutes: %1\$d. Seconds: %2\$d.", null)
        assertEquals(placeholders(en.getValue("call_duration_a11y")), placeholders(es.getValue("call_duration_a11y")))
        assertFalse("minutes %2" in en.getValue("call_duration_a11y"), "TalkBack would say \"1 minutes\"")
    }

    @Test
    fun `the Spanish attachment key exchange lines say whose exchange it is and that it is paid`() {
        both("chat_attachment_kex_row_note", null, "(cifrado; el primer adjunto requiere un intercambio de claves que se paga)")
        val body = es.getValue("chat_attachment_needs_kex_shielded_body")
        assertTrue("y esa clave sale de tu intercambio de claves, que todavía no se ha hecho" in body, body)
        assertTrue("Envía primero un mensaje de texto: eso inicia el intercambio de claves." in body, body)
        assertFalse("sale de su intercambio" in body, body)
    }

    @Test
    fun `the Open to Tunnel offer says that the ZEC has to be spendable`() {
        both(
            "chat_open_tunnel_offer_confirming_funds",
            null,
            "Parte de tu ZEC aún se está confirmando. Podrás aceptar (unos %1\$s ZEC) cuando ese ZEC se pueda gastar. " +
                "Este chat sigue en Open hasta entonces.",
        )
        assertEquals(
            placeholders(en.getValue("chat_open_tunnel_offer_confirming_funds")),
            placeholders(es.getValue("chat_open_tunnel_offer_confirming_funds")),
        )
    }

    @Test
    fun `Spanish wording fixes that keep the English meaning`() {
        both(
            "group_add_member_hint",
            null,
            "Personas con las que has chateado o que guardaste como contactos, que todavía no están en este grupo.",
        )
        both("about_subtitle", null, "Mensajería privada en Zcash.")
        both(
            "chat_stranded_relays_keep_working",
            null,
            "Los chats Open y Tunnel que ya tienes siguen funcionando por los relays de Nostr. A partir de ahora, " +
                "todo lo que recibas o blindes llega a Ironwood automáticamente.",
        )
        both("chat_image_preview_view_once", null, "Ver una vez: la verá una vez y luego desaparece")
        both("memo_display_paid_request", null, "💰 Solicitud de pago pagada")
        // ZC-1797: the memo label keeps the feature's name; pay-to-reveal and secret-answer ZTL memos have no schedule
        both("memo_display_time_locked", null, "🔒 Mensaje con bloqueo temporal")
        both("send_support_donation_address_label", null, "Dirección de donaciones de ZChat (precargada)")
        both("transaction_detail_bookmark_add", null, "Agregar marcador")
        both("transaction_detail_bookmark_remove", null, "Quitar marcador")
    }
}
