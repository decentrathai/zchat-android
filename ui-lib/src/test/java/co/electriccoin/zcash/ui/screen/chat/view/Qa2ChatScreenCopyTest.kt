package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 (owner W-136/W-137, 10 Nov 2026), unit U7 chat screen: the wording the second QA round asked for, English and
 * Spanish in step (resource-text pattern of HonestCopySecondPassTest / SpanishLeftoversAndPlaceholdersTest).
 *
 * Every Spanish line is a DRAFT for the reviewer round (app-texts rule 9). Where a text carries a figure it carries
 * a %n$s placeholder: the figure comes from the quote at the call site, never from a constant (money-path rule 1).
 *
 * Rows: Q2-24, Q2-25 (hint), Q2-26, Q2-27, Q2-28, Q2-31, Q2-33, Q2-35, Q2-38, Q2-39, Q2-40, Q2-41, Q2-42, Q2-43,
 * Q2-44, Q2-48, Q2-49, Q2-50, Q2-52, Q2-54, Q2-98 (FIX_LIST.md under qa2/).
 */
class Qa2ChatScreenCopyTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private fun enText(key: String): String = assertNotNull(en[key], "values has no $key")

    private fun esText(key: String): String = assertNotNull(es[key], "values-es has no $key")

    /** The key reads [english] in English and [spanish] in Spanish. */
    private fun assertText(
        key: String,
        english: String,
        spanish: String,
    ) {
        assertEquals(english, enText(key), "$key (English)")
        assertEquals(spanish, esText(key), "$key (Spanish draft)")
    }

    /** The key exists in both languages, the Spanish one translated. */
    private fun assertTranslated(key: String) {
        assertNotEquals(enText(key), esText(key), "$key is not translated")
    }

    // ---- Q2-24 message requests sheet ----

    @Test
    fun q2_24_the_requests_sheet_says_the_address_is_the_senders_claim_and_to_read_the_message() {
        assertText(
            "message_requests_body",
            "These people messaged you for free over Nostr relays. The Zcash address is what the sender claims — ZChat " +
                "has not verified it. Read the message and accept only if you know who wrote it.",
            "Estas personas te escribieron gratis por los relays de Nostr. La dirección de Zcash es la que indica el " +
                "remitente; ZChat no la ha verificado. Lee el mensaje y acepta solo si sabes quién lo escribió.",
        )
        assertFalse("recognise" in enText("message_requests_body"), "the sheet still asks to recognise an address")
    }

    // ---- Q2-25 declined row hint (B-3 hint, ZC-1512) ----

    @Test
    fun q2_25_the_declined_row_says_restore_accepts_it_later() {
        assertText(
            "message_requests_declined_hint",
            "Declined — tap Restore to accept later",
            "Rechazada: toca Restaurar para aceptarla después",
        )
    }

    // ---- Q2-26 contact name dialog ----

    @Test
    fun q2_26_the_contact_name_dialog_has_one_name_for_one_thing() {
        assertText("contact_nickname_remove", "Remove nickname", "Quitar apodo")
        assertText("contact_nickname_title", "Contact name", "Nombre del contacto")
        assertText("contact_nickname_prompt", "Nickname (kept only on this phone)", "Apodo (solo en este teléfono)")
    }

    // ---- Q2-27 header ----

    @Test
    fun q2_27_the_nickname_hint_is_short_enough_for_the_header() {
        assertText("chat_nickname_hint", "Set nickname", "Poner apodo")
    }

    @Test
    fun q2_27_the_mode_chip_has_one_case_in_both_languages() {
        for ((key, name) in listOf("shielded" to "Shielded", "tunnel" to "Tunnel", "open" to "Open")) {
            assertEquals(name, enText("chat_header_mode_$key"), "chat_header_mode_$key (English)")
            assertEquals(name, esText("chat_header_mode_$key"), "chat_header_mode_$key (Spanish)")
        }
    }

    // ---- Q2-28 lock ----

    @Test
    fun q2_28_the_lock_is_read_as_encrypted_not_closed() {
        assertText(
            "chat_a11y_encryption_closed",
            "End-to-end encrypted",
            "Cifrado de extremo a extremo",
        )
        assertText(
            "chat_a11y_encryption_pending",
            "Encryption pending — waiting for your contact's key",
            "Cifrado pendiente: esperando la clave de tu contacto",
        )
    }

    @Test
    fun q2_28_the_open_chat_lock_dialog_does_not_send_the_user_to_a_safety_number_it_does_not_have() {
        assertText(
            "chat_menu_e2e_subtitle_open",
            "End-to-end encrypted over Nostr relays. Open chats have no safety number to compare; to verify your " +
                "contact's key, switch to Shielded or Tunnel.",
            "Cifrado de extremo a extremo; se envía por los relays de Nostr. Los chats Open no tienen número de seguridad que " +
                "comparar; para verificar la clave de tu contacto, cambia a Shielded o Tunnel.",
        )
    }

    @Test
    fun q2_28_the_security_help_line_names_the_chats_that_have_a_safety_number() {
        val row = WHAT_ZCHAT_PROTECTS_ITEMS.first { it.first == "Safety Number" }.second
        assertFalse("in any end-to-end encrypted chat" in row, "Open chats have no safety number: $row")
        assertTrue("Shielded or Tunnel" in row, "the line must name the chats that have one: $row")
        val rowKey = en.entries.first { it.value == row }.key
        val spanish = esText(rowKey)
        assertTrue("Shielded" in spanish && "Tunnel" in spanish, "the Spanish line must name them too: $spanish")
    }

    // ---- Q2-31 attachments ----

    @Test
    fun q2_31_the_attachment_notice_title_covers_every_attachment() {
        assertText(
            "chat_attachment_needs_kex_title",
            "Attachments need a key exchange",
            "Los adjuntos necesitan un intercambio de claves",
        )
    }

    @Test
    fun q2_31_the_attachment_rows_of_an_open_or_tunnel_chat_say_the_first_one_pays_a_key_exchange() {
        assertText(
            "chat_attachment_kex_row_note",
            "(encrypted; the first attachment needs a paid key exchange)",
            "(cifrado; el primer adjunto requiere un intercambio de claves que se paga)",
        )
    }

    // ---- Q2-33 mute marker ----

    @Test
    fun q2_33_a_muted_chat_has_a_label_in_the_top_bar() {
        assertText("chat_header_muted", "Muted", "Silenciado")
    }

    // ---- Q2-34 My code ----

    @Test
    fun q2_34_the_address_tab_names_what_it_is_for_and_the_info_box_says_messages_are_shielded() {
        assertText("my_code_tab_address", "Receive ZEC", "Recibir ZEC")
        assertText(
            "my_code_shielded_info_share",
            "Share this address to receive ZEC. Messages sent to it are Shielded (on-chain, paid by the sender). For free " +
                "chats, share your Free chat code.",
            "Comparte esta dirección para recibir ZEC. Los mensajes enviados aquí son Shielded (en cadena, los paga el " +
                "remitente). Para chats gratis, comparte tu código de chat gratis.",
        )
    }

    // ---- Q2-35 camera scanner ----

    @Test
    fun q2_35_the_contact_code_scanner_has_a_hint() {
        assertText(
            "scan_contact_hint",
            "Point the camera at your contact's ZCHAT code",
            "Apunta la cámara al código ZCHAT de tu contacto",
        )
    }

    // ---- Q2-38 unreadable message ----

    @Test
    fun q2_38_the_unreadable_message_promises_no_repair() {
        assertText(
            "chat_decrypt_placeholder_reestablishing",
            "🔐 Can't read this message yet. Your contact's ZChat encrypted it with keys this phone doesn't have.",
            "🔐 Aún no se puede leer este mensaje. El ZChat de tu contacto lo cifró con claves que este teléfono no tiene.",
        )
        assertFalse("re-establishing" in enText("chat_decrypt_placeholder_reestablishing"))
        assertFalse("restableciendo" in esText("chat_decrypt_placeholder_reestablishing"))
    }

    // ---- Q2-39 / Q2-40 / Q2-42 / Q2-44 / Q2-48 money wording: the figure is a placeholder ----

    @Test
    fun q2_39_the_collapsed_key_exchange_banner_carries_the_price_of_accept() {
        for (key in listOf("chat_open_tunnel_offer_short")) {
            assertTrue("%1\$s" in enText(key), "$key (English) must carry the quoted figure")
            assertTrue("%1\$s" in esText(key), "$key (Spanish) must carry the quoted figure")
            assertTrue("Accept" in enText(key), "$key (English) names the button it prices")
            assertTrue("ceptar" in esText(key), "$key (Spanish) names the button it prices")
        }
        assertText(
            "chat_open_tunnel_offer_short",
            "Tunnel chat offered — Accept costs about %1\$s ZEC",
            "Chat Tunnel ofrecido: aceptar cuesta unos %1\$s ZEC",
        )
    }

    @Test
    fun q2_40_not_enough_spendable_zec_is_split_from_zec_that_is_still_confirming() {
        assertText(
            "chat_open_tunnel_offer_needs_funds",
            "Not enough spendable ZEC right now: about %1\$s ZEC is needed, and ZEC that is still confirming doesn't " +
                "count. This chat stays Open until then.",
            "Ahora no hay suficiente ZEC disponible: hacen falta unos %1\$s ZEC y el ZEC que aún se confirma no cuenta. " +
                "Este chat sigue en Open hasta entonces.",
        )
        val confirming = enText("chat_open_tunnel_offer_confirming_funds")
        assertTrue("confirming" in confirming && "%1\$s" in confirming, confirming)
        assertFalse(confirming.startsWith("You need"), "never 'you need X' while the shown balance is larger: $confirming")
        val confirmingEs = esText("chat_open_tunnel_offer_confirming_funds")
        assertTrue("confirma" in confirmingEs && "%1\$s" in confirmingEs, confirmingEs)
        assertFalse(confirmingEs.startsWith("Necesitas"), confirmingEs)
    }

    @Test
    fun q2_42_the_priced_send_button_says_send_and_the_least_it_costs() {
        assertText("chat_send_cost_label", "Send · at least %1\$s ZEC", "Enviar · al menos %1\$s ZEC")
    }

    @Test
    fun q2_44_the_pay_button_carries_the_quoted_amount() {
        assertText("paid_action_confirm", "Pay at least %1\$s ZEC", "Pagar al menos %1\$s ZEC")
        // An exact quote says exact ("never at least", PaidActionConfirmDialog SR-A S-A3): its button names no floor.
        assertText("paid_action_confirm_exact", "Pay %1\$s ZEC", "Pagar %1\$s ZEC")
    }

    // Q2-48 moved to lane U9 (W-137): its string and the ChatViewModel argument land together there.

    // ---- Q2-41 generic name in system notes ----

    @Test
    fun q2_41_a_contact_without_a_nickname_is_your_contact_in_system_notes() {
        assertText("chat_contact_fallback_name", "Your contact", "Tu contacto")
    }

    @Test
    fun q2_41_money_confirmations_still_name_the_recipient_by_address() {
        // Rule 6 (money-path): the recipient stays verifiable by eye. The pay-request and welcome confirmations keep
        // the %2$s recipient argument; only system notes get the generic name.
        for (key in listOf("pay_request_confirm_body", "welcome_zec_confirm_body")) {
            assertTrue("%2\$s" in enText(key), "$key (English) lost its recipient argument")
            assertTrue("%2\$s" in esText(key), "$key (Spanish) lost its recipient argument")
        }
    }

    // ---- Q2-43 toast ----

    @Test
    fun q2_43_the_on_chain_send_toast_does_not_claim_more_than_the_network_took() {
        val english = "Sent to the Zcash network — confirming"
        assertEquals(english, ChatSendCopy.MESSAGE_SENT)
        assertText("chat_send_message_sent", english, "Enviado a la red Zcash: confirmando")
    }

    // ---- Q2-49 mode switch pointers ----

    @Test
    fun q2_49_the_time_lock_hint_points_to_the_mode_under_the_name() {
        assertText(
            "chat_feature_time_lock_shielded_only",
            "Shielded chats only — tap the mode under the contact's name to switch",
            "Solo en chats Shielded: toca el modo bajo el nombre del contacto para cambiarlo",
        )
    }

    @Test
    fun q2_49_no_text_sends_the_user_to_the_overflow_menu_for_a_mode_switch() {
        for (key in listOf("upgrade_no_key_2", "chat_mode_note_open_body", "chat_feature_time_lock_shielded_only")) {
            val english = enText(key)
            assertFalse("⋮" in english, "$key (English) still points to the ⋮ menu: $english")
            assertTrue("mode under the contact's name" in english, "$key (English): $english")
            val spanish = esText(key)
            assertFalse("⋮" in spanish, "$key (Spanish) still points to the ⋮ menu: $spanish")
            assertTrue("modo bajo el nombre del contacto" in spanish, "$key (Spanish): $spanish")
        }
    }

    // ---- Q2-50 request payment memo (pure function) ----

    @Test
    fun q2_50_the_detail_of_a_request_payment_does_not_show_the_raw_memo() {
        // U7 review follow-up (Normal 4): the label is a resource now, so the English line no longer lives in Kotlin and
        // this pin reads the source, not a one-argument call (the function gets a resources seam). The fulfilled request is
        // named, the raw ZREQ_FULFILL payload is never echoed.
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/MemoDisplayText.kt")
        val branch = source.substringAfter("startsWith(\"ZREQ_FULFILL|\")", "").substringBefore("\n")
        assertTrue(branch.isNotBlank(), "the ZREQ_FULFILL branch is gone from memoDisplayText")
        assertTrue("->" in branch, "the ZREQ_FULFILL branch does not map to a label: $branch")
        assertFalse(Regex("""->\s*(memo|m)\s*$""").containsMatchIn(branch), "the ZREQ_FULFILL branch shows the raw memo")
    }

    @Test
    fun q2_50_the_paid_request_label_is_a_resource_not_a_kotlin_literal() {
        // Normal 4: a Spanish phone showed "💰 Paid a payment request" because memo_display_paid_request, though pinned EN+ES
        // above, was read by no screen.
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/MemoDisplayText.kt")
        assertFalse("Paid a payment request" in source, "MemoDisplayText.kt still holds the English literal")
        val allMain =
            File(ChatResourceText.repoRoot, "ui-lib/src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .joinToString("\n") { it.readText() }
        assertTrue(
            "R.string.memo_display_paid_request" in allMain || "StringResource.memo_display_paid_request" in allMain,
            "no Kotlin source reads memo_display_paid_request: the Spanish line never reaches a screen",
        )
    }

    @Test
    fun q2_50_the_detail_label_has_a_spanish_resource() {
        assertText("memo_display_paid_request", "💰 Paid a payment request", "💰 Solicitud de pago pagada")
    }

    // ---- Q2-52 info dialogs ----

    @Test
    fun q2_52_the_tunnel_and_open_info_dialogs_are_three_short_paragraphs_with_lead_ins() {
        val leadIns =
            mapOf(
                "values" to listOf("What it costs", "What relays can see", "How to go back"),
                "values-es" to listOf("Lo que cuesta", "Lo que ven los relays", "Cómo volver"),
            )
        for ((dir, expected) in leadIns) {
            val texts = if (dir == "values") en else es
            for (key in listOf("chat_mode_note_open_body", "chat_mode_note_tunnel_body")) {
                val paragraphs = assertNotNull(texts[key], "$dir has no $key").replace("\\n", "\n").split("\n\n")
                assertEquals(3, paragraphs.size, "$dir/$key must be three paragraphs, not one wall of text")
                expected.forEachIndexed { i, leadIn ->
                    assertTrue(
                        paragraphs[i].startsWith(leadIn),
                        "$dir/$key paragraph ${i + 1} must start with \"$leadIn\": ${paragraphs[i]}",
                    )
                }
                paragraphs.forEach { assertTrue(it.length <= 480, "$dir/$key paragraph is not short (${it.length}): $it") }
            }
        }
    }

    // ---- Q2-54 request card ----

    @Test
    fun q2_54_the_pay_button_of_a_request_says_fees_come_on_top() {
        assertText("chat_request_pay_button", "Pay %1\$s ZEC + fees", "Pagar %1\$s ZEC + comisiones")
    }

    @Test
    fun q2_54_the_paid_receipt_names_the_amount_and_the_time() {
        assertText("chat_request_paid_receipt", "Paid %1\$s ZEC at %2\$s", "Pagado %1\$s ZEC a las %2\$s")
    }

    // ---- Q2-98 offline ----

    @Test
    fun q2_98_the_chat_header_says_offline_while_the_wallet_is_offline() {
        // U7 review follow-up (nit 12, app-texts rule 5): the separator is part of the one resource, no " · " + text in Kotlin.
        assertEquals("· Offline", headerOfflineText(enText("chat_header_offline")), "chat_header_offline (English)")
        assertEquals("· Sin conexión", headerOfflineText(esText("chat_header_offline")), "chat_header_offline (Spanish draft)")
    }

    /** The resource as the screen shows it, minus an optional quote pair or \u0020 that keeps a leading space. */
    private fun headerOfflineText(raw: String): String =
        raw
            .replace("\\u0020", " ")
            .trim()
            .trim('"')
            .trim()

    // ---- U7 review follow-up: invite disclaimer names the renamed tab ----

    @Test
    fun the_invite_disclaimer_names_the_receive_zec_tab() {
        // Normal 5: the tab reads "Receive ZEC" / "Recibir ZEC" (my_code_tab_address); the disclaimer pointed to the old name.
        assertEquals("Receive ZEC", enText("my_code_tab_address"))
        assertEquals("Recibir ZEC", esText("my_code_tab_address"))
        val english = enText("my_code_invite_disclaimer")
        val spanish = esText("my_code_invite_disclaimer")
        assertTrue("Receive ZEC" in english, "the English disclaimer does not name the Receive ZEC tab: $english")
        assertFalse("Shielded address tab" in english, "the English disclaimer names the old tab: $english")
        assertTrue("Recibir ZEC" in spanish, "the Spanish disclaimer does not name the Recibir ZEC tab: $spanish")
        assertFalse("Dirección Shielded" in spanish, "the Spanish disclaimer names the old tab: $spanish")
    }

    @Test
    fun every_new_key_is_translated() {
        for (key in listOf(
            "message_requests_declined_hint",
            "chat_menu_e2e_subtitle_open",
            "chat_attachment_kex_row_note",
            "chat_header_muted",
            "scan_contact_hint",
            "chat_open_tunnel_offer_short",
            "chat_open_tunnel_offer_confirming_funds",
            "paid_action_confirm_exact",
            "chat_contact_fallback_name",
            "memo_display_paid_request",
            "chat_request_pay_button",
            "chat_request_paid_receipt",
            "chat_header_offline",
        )) {
            assertTranslated(key)
        }
    }
}
