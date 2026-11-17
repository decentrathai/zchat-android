package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.QueuedAutomaticSetup
import co.electriccoin.zcash.ui.screen.chat.model.automaticSetupTransactions
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import org.junit.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 unit U4-newchat (FIX_LIST batch B2; owner W-136/W-137: every QA2 finding is fixed in 2.13.0). The New Chat
 * screen, the mode picker, the mode info dialogs and the onboarding identity screen promise less or more than the
 * code does, in English and Spanish:
 *
 * - Q2-23 (ZC-1622): the Tunnel cue under the chips is long enough to push "Send Message" off the screen; it becomes
 *   one short sentence with the code's count ("2–3") and the floor of one transaction.
 * - Q2-29 (ZC-983): Tunnel is priced four ways; the picker row says "One paid handshake" and the info dialogs "up to
 *   three". Both now give the code's count, and the dialogs say the exact number is shown before paying.
 * - Q2-31 (ZC-1600): the key-exchange notice before an attachment is titled "Photos" only.
 * - Q2-32 (ZC-1600): "NOSTR" in capitals beside "Nostr relays"; title-case headings beside sentence-case tabs.
 * - Q2-36 (ZC-1631): on an empty New Chat the Tunnel chip is highlighted, the app bar repeats the scan entry and the
 *   disabled "Send Message" label is hard to read.
 * - Q2-51 (ZC-1593): "no ZEC, ever", "no ZEC", "no ZEC needed" for Open (its attachments pay a key exchange) and
 *   "no relay involved" for Shielded (relays carry key setup and calls).
 * - Q2-84 (ZC-1660): a custom amount above the available balance gets no warning and "Done" stays enabled.
 * - Q2-87 (ZC-014): from Send Us Feedback, New Chat gives no instruction and no cost word.
 * - Q2-90 (ZC-1597): the cue under each chip is 10.sp and the Spanish "needs code" cue fills its chip edge to edge.
 * - Q2-91 (ZC-1664): the card says "Amount: 0.00002 ZEC" while the dialog ticks 0.00001 (the ZChat fee is folded in).
 * - Q2-103 (ZC-1594): onboarding says Tunnel messages are free; Tunnel setup pays.
 * - Q2-104 (ZC-1595): "Encrypted & delivered to the blockchain" right after submit, before any confirmation.
 * - Q2-105 (ZC-1600): Reconnect "May cost a small ZEC fee". Review follow-up: the first fix promised "the price is shown
 *   before anything is paid", but a Reconnect under the standing handshake consent sends the on-chain handshake without a
 *   price question; the line now names the real rule.
 *
 * Review follow-up (W-137): the Shielded warning no longer says relays carry its key setup; "Done" in the amount dialog
 * cannot be bypassed by dismissing the dialog; the dead Feedback flag and line (Q2-87) are removed (the entry route is
 * a wave-2 item); onboarding no longer calls Tunnel's setup "one-time" (it is paid once per contact).
 *
 * Counts and figures come from the code (automaticSetupTransactions, onChainSendMinimumCost,
 * CreateChunkedMessageProposalUseCase.PLATFORM_FEE_MIN_ZATOSHI), never typed here (skill money-path). Spanish lines are
 * agent drafts (app-texts rule 9) and use the reviewed terms of references/es.md («en la cadena», «intercambio de
 * claves»). Texts and source shape only: no test here drives a send.
 */
class NewChatAndModeTextsSayWhatPaysTest {
    private val en = ChatResourceText.strings("values").mapValues { it.value.replace("\\n", "\n") }
    private val es = ChatResourceText.strings("values-es").mapValues { it.value.replace("\\n", "\n") }

    private val composeView: String by lazy { ChatResourceText.source(COMPOSE_VIEW) }

    // ---- what the code counts and charges ----

    private val keyExchange = listOf(QueuedAutomaticSetup(AutomaticSetupKind.KEX, "peer"))
    private val handshakeLeast = automaticSetupTransactions(keyExchange, emptyList()) { false }
    private val handshakeMost = automaticSetupTransactions(keyExchange, emptyList())

    /** New Chat in Tunnel, and a switch to Tunnel with no finished handshake: one transaction, then the handshake. */
    private val least = 1 + handshakeLeast
    private val most = 1 + handshakeMost

    private val floorEn: String = zec(onChainSendMinimumCost().value)
    private val floorEs: String = floorEn.replace('.', ',')
    private val zchatFeeEn: String = zec(CreateChunkedMessageProposalUseCase.PLATFORM_FEE_MIN_ZATOSHI)
    private val zchatFeeEs: String = zchatFeeEn.replace('.', ',')

    private val wordsEn = mapOf(2 to "two", 3 to "three")
    private val wordsEs = mapOf(2 to "dos", 3 to "tres")

    @Test
    fun control_the_code_counts_two_or_three_payments_and_the_figures_the_texts_name() {
        assertEquals(2, least)
        assertEquals(3, most)
        assertEquals("0.00017", floorEn)
        assertEquals("0.00001", zchatFeeEn)
    }

    // ---- Q2-23: the short Tunnel cue ----

    @Test
    fun q2_23_the_tunnel_cue_is_one_short_sentence_with_the_codes_count_and_floor() {
        val english =
            "$least–$most on-chain payments (first message, then handshake), each at least $floorEn ZEC. " +
                "Then messages and calls are free over Nostr relays."
        val spanish =
            "$least–$most pagos en la cadena (primer mensaje y luego el intercambio de claves), cada uno de al menos " +
                "$floorEs ZEC. Después, mensajes y llamadas gratis por los relays de Nostr."
        assertEquals(english, en["new_chat_mode_blurb_tunnel"], "English Tunnel cue")
        assertEquals(spanish, es["new_chat_mode_blurb_tunnel"], "Spanish Tunnel cue")
        assertEquals(english, ConversationMode.TUNNEL.composeBlurb(), "composeBlurb() must stay equal to the resource")
    }

    // ---- Q2-29: one Tunnel price everywhere ----

    @Test
    fun q2_29_the_picker_row_gives_the_codes_count_not_one_paid_handshake() {
        val english = "$least–$most on-chain payments, then free messages + voice through Nostr relays."
        val spanish = "$least–$most pagos en la cadena y luego mensajes y voz gratis por los relays de Nostr."
        assertEquals(english, en["chat_mode_picker_desc_tunnel"], "English picker row")
        assertEquals(spanish, es["chat_mode_picker_desc_tunnel"], "Spanish picker row")
        assertEquals(english, modeBlurb(ConversationMode.TUNNEL).first, "modeBlurb() must stay equal to the resource")
    }

    @Test
    fun q2_29_the_info_dialogs_say_two_or_three_and_that_the_exact_number_is_shown_first() {
        val english =
            "switching to Tunnel costs ${wordsEn[least]} or ${wordsEn[most]} on-chain payments, each at least $floorEn ZEC; " +
                "the exact number is shown before you pay"
        val spanish =
            "cambiar a Tunnel cuesta ${wordsEs[least]} o ${wordsEs[most]} pagos en la cadena, cada uno de al menos " +
                "$floorEs ZEC; el número exacto se muestra antes de pagar"
        val englishTexts =
            mapOf(
                "MODE_INTRO_TUNNEL_BODY" to MODE_INTRO_TUNNEL_BODY,
                "chat_mode_intro_tunnel_body" to text(en, "chat_mode_intro_tunnel_body"),
                "chat_mode_note_tunnel_body" to text(en, "chat_mode_note_tunnel_body"),
            )
        for ((name, body) in englishTexts) {
            assertFalse("up to three" in body, "$name still says \"up to three\": $body")
            assertTrue(english in body, "$name must say \"$english\": $body")
        }
        for (key in listOf("chat_mode_intro_tunnel_body", "chat_mode_note_tunnel_body")) {
            val body = text(es, key)
            assertFalse("hasta tres" in body, "Spanish $key still says «hasta tres»: $body")
            assertTrue(spanish in body, "Spanish $key must say «$spanish»: $body")
        }
    }

    // ---- Q2-31: the attachment notice's title ----

    @Test
    fun q2_31_the_key_exchange_notice_is_titled_attachments_not_photos() {
        assertEquals("Attachments need a key exchange", en["chat_attachment_needs_kex_title"])
        assertEquals("Los adjuntos necesitan un intercambio de claves", es["chat_attachment_needs_kex_title"])
    }

    // ---- Q2-32: one spelling of Nostr, sentence-case headings ----

    @Test
    fun q2_32_no_batch_text_writes_nostr_in_capitals() {
        val keys =
            en.keys.filter { it.startsWith("new_chat_") } +
                listOf(
                    "chat_composer_free_nostr",
                    "chat_privacy_chip_nostr",
                    "chat_privacy_chip_tunnel_setup",
                    "chat_list_message_requests_hint",
                    "chat_stranded_relays_keep_working",
                )
        val found =
            keys.flatMap { key ->
                listOf("values" to en, "values-es" to es).mapNotNull { (dir, strings) ->
                    strings[key]?.takeIf { "NOSTR" in it }?.let { "$dir $key: $it" }
                }
            }
        assertTrue(found.isEmpty(), "texts that still write NOSTR in capitals:\n" + found.joinToString("\n"))
        assertEquals("Free / Nostr", en["new_chat_mode_tag_open"])
        assertEquals("Gratis / Nostr", es["new_chat_mode_tag_open"])
    }

    @Test
    fun q2_32_the_relay_lines_name_the_nostr_relays() {
        for (key in listOf("chat_composer_free_nostr", "chat_list_message_requests_hint", "chat_stranded_relays_keep_working")) {
            assertTrue("Nostr relays" in text(en, key), "English $key must say \"Nostr relays\": ${en[key]}")
            assertTrue("relays de Nostr" in text(es, key), "Spanish $key must say «relays de Nostr»: ${es[key]}")
        }
    }

    @Test
    fun q2_32_my_code_headings_are_sentence_case_like_their_tab() {
        assertEquals("Shielded address", en["my_code_heading_shielded"])
        assertEquals("Transparent address", en["my_code_heading_transparent"])
    }

    // ---- Q2-36: the empty New Chat ----

    @Test
    fun q2_36_no_mode_chip_is_highlighted_before_a_recipient_exists() {
        val selector = function(composeView, "private fun ConversationModeSelector(")
        val line = selector.lines().firstOrNull { "val isSelected" in it }
        assertNotNull(line, "fixture: the chips' isSelected in ConversationModeSelector")
        assertTrue(
            "enabled" in line,
            "a chip is highlighted while the form has no recipient (enabled = state.isValidAddress is not asked): $line",
        )
    }

    @Test
    fun q2_36_the_app_bar_no_longer_repeats_the_scan_entry() {
        val start = composeView.indexOf("actions = {")
        if (start < 0) return
        val end = composeView.indexOf("colors = TopAppBarDefaults", start)
        assertTrue(end > start, "fixture: the end of the app bar's actions")
        val actions = composeView.substring(start, end)
        assertFalse("onScanQrClick" in actions, "the app bar still has a Scan icon beside the two start buttons: $actions")
    }

    @Test
    fun q2_36_the_disabled_send_label_is_readable() {
        val send = composeView.indexOf("R.string.new_chat_send_message")
        assertTrue(send >= 0, "fixture: the Send Message button")
        val disabled = composeView.lastIndexOf("disabledContentColor", send)
        assertTrue(disabled >= 0, "fixture: the Send button's disabled content colour")
        val line = composeView.substring(disabled, composeView.indexOf('\n', disabled))
        val alpha = firstGroup(Regex("""alpha\s*=\s*([0-9.]+)f"""), line)?.toFloat() ?: 1f
        assertTrue(alpha >= DISABLED_LABEL_MIN_ALPHA, "the disabled Send label is at alpha $alpha: $line")
    }

    // ---- Q2-51: what Open and Shielded really cost and touch ----

    @Test
    fun q2_51_the_picker_says_texts_and_calls_cost_no_zec_not_no_zec() {
        val english =
            "Free through Nostr relays from message #1 — texts and calls cost no ZEC. Needs the contact's chat key."
        val spanish =
            "Gratis por los relays de Nostr desde el mensaje n.º 1: los textos y las llamadas no cuestan ZEC. " +
                "Necesita la clave de chat del contacto."
        assertEquals(english, en["chat_mode_picker_desc_open"], "English Open picker row")
        assertEquals(spanish, es["chat_mode_picker_desc_open"], "Spanish Open picker row")
        assertEquals(english, modeBlurb(ConversationMode.OPEN).first, "modeBlurb() must stay equal to the resource")
    }

    @Test
    fun q2_51_the_new_chat_texts_promise_no_zec_only_for_texts_and_calls_and_no_relay_free_shielded() {
        val keys =
            listOf(
                "new_chat_free_desc",
                "new_chat_mode_blurb_open",
                "new_chat_open_needs_code_body",
                "new_chat_open_warning_body",
                "new_chat_code_free_hint",
            )
        val bareNoZec = Regex("""(?<!cost )\bno ZEC""", RegexOption.IGNORE_CASE)
        val bannedEn = listOf("no ZEC, ever", "no ZEC needed", "no relay involved")
        val bannedEs = listOf("sin ZEC", "sin necesidad de ZEC", "sin ningún relay")
        val found = mutableListOf<String>()
        for (key in keys) {
            val english = text(en, key)
            val spanish = text(es, key)
            bannedEn.filter { english.contains(it, ignoreCase = true) }.forEach { found += "values $key: \"$it\"" }
            bareNoZec.findAll(english).forEach { found += "values $key: bare \"${it.value}\" (say what costs no ZEC)" }
            bannedEs.filter { spanish.contains(it, ignoreCase = true) }.forEach { found += "values-es $key: «$it»" }
        }
        assertTrue(found.isEmpty(), "New Chat texts that promise more than the code does:\n" + found.joinToString("\n"))
    }

    @Test
    fun q2_51_the_open_warning_does_not_say_relays_carry_the_shielded_key_setup() {
        val english = text(en, "new_chat_open_warning_body")
        val spanish = text(es, "new_chat_open_warning_body")
        assertFalse("key setup" in english, "Shielded key setup is on-chain, not over relays: $english")
        assertFalse("configuración de claves" in spanish, "Shielded key setup is on-chain, not over relays: $spanish")
        assertTrue(
            english.endsWith(
                "Use Shielded if you want every message you send on-chain. Calls are still set up over the relays " +
                    "once your contact's chat key is known.",
            ),
            "English Shielded sentence: $english",
        )
        assertTrue(
            spanish.endsWith(
                "Usa Shielded si quieres que cada mensaje que envíes vaya en la cadena. Las llamadas se siguen " +
                    "preparando por los relays cuando ya se conoce la clave de chat de tu contacto.",
            ),
            "Spanish Shielded sentence: $spanish",
        )
    }

    // ---- Q2-84: an amount above the available balance ----

    @Test
    fun q2_84_the_over_balance_warning_is_a_resource_with_the_available_amount() {
        // %1$s is availableBalanceDisplay, which already ends in " ZEC" (formatZatoshi), so no "ZEC" after it.
        assertEquals("More than your available balance (%1\$s)", en["new_chat_amount_over_balance"])
        assertEquals("Supera tu saldo disponible (%1\$s)", es["new_chat_amount_over_balance"])
    }

    @Test
    fun q2_84_new_chat_shows_the_warning_under_the_field_and_on_the_card_and_holds_done() {
        val shown = Regex("""R\.string\.new_chat_amount_over_balance""").findAll(composeView).count()
        assertTrue(shown >= 2, "the over-balance warning is shown $shown times; wanted under the field and on the card")
        val dialog = function(composeView, "private fun AmountSelectionDialog(")
        val confirm = dialog.indexOf("confirmButton")
        assertTrue(confirm >= 0, "fixture: the amount dialog's confirm button")
        val done = dialog.substring(confirm, dialog.indexOf("R.string.new_chat_done", confirm))
        assertTrue("enabled = !isAmountOverBalance" in done, "\"Done\" stays enabled over the balance: $done")
    }

    @Test
    fun q2_84_dismissing_the_amount_dialog_cannot_keep_an_amount_over_the_balance() {
        // Review follow-up: Back or a tap outside runs onDismissRequest, which skipped the disabled "Done".
        val dialog = function(composeView, "private fun AmountSelectionDialog(")
        val line = dialog.lines().firstOrNull { it.trim().startsWith("onDismissRequest") }
        assertNotNull(line, "fixture: the amount dialog's onDismissRequest")
        assertTrue(
            "isAmountOverBalance" in line,
            "dismissing the dialog closes it with an amount over the balance kept: $line",
        )
    }

    @Test
    fun control_q2_84_the_amount_reading_path_is_unchanged() {
        // money-path rule 5: the warning and the disabled Done must not touch how the typed amount is read.
        val dialog = function(composeView, "private fun AmountSelectionDialog(")
        assertTrue("acceptAmountInputResult(state, newText, formatLocale, AmountUnit.ZEC, selectionBefore)" in dialog, dialog)
        assertTrue("onCustomAmountChange(localCustom.settled)" in dialog, dialog)
        assertTrue("localCustom.choose(it, MAX_AMOUNT_DECIMALS)" in dialog, dialog)
    }

    // ---- Q2-87: New Chat opened from Send Us Feedback ----

    @Test
    fun q2_87_the_unwired_feedback_flag_and_line_are_gone_until_the_entry_route_exists() {
        // Review follow-up: nothing sets openedFromFeedback (More -> Send Us Feedback opens the email form, and New Chat
        // is built in AndroidChat, a wave-2 file), so the flag and its text were unreachable. Q2-87 is listed for wave 2.
        val state = ChatResourceText.source(COMPOSE_STATE)
        assertFalse("openedFromFeedback" in state, "ZchatComposeState still has the unwired openedFromFeedback")
        assertFalse("openedFromFeedback" in composeView, "ZchatComposeView still reads the unwired openedFromFeedback")
        assertFalse("new_chat_feedback_hint" in composeView, "New Chat still shows the unreachable Feedback line")
        assertFalse("new_chat_feedback_hint" in en, "English still has the unused new_chat_feedback_hint")
        assertFalse("new_chat_feedback_hint" in es, "Spanish still has the unused new_chat_feedback_hint")
    }

    // ---- Q2-90: the cue size and the short "needs code" ----

    @Test
    fun q2_90_the_needs_code_cue_is_short_in_both_languages() {
        assertEquals("Needs code", en["new_chat_mode_needs_code"])
        assertEquals("Necesita código", es["new_chat_mode_needs_code"])
    }

    @Test
    fun q2_90_the_cue_under_each_chip_is_at_least_12_sp_and_may_wrap() {
        val cue = composeView.indexOf("R.string.new_chat_mode_needs_code")
        assertTrue(cue >= 0, "fixture: the cue under each chip")
        val call = composeView.substring(cue, composeView.indexOf("color =", cue))
        val size = firstGroup(Regex("""fontSize\s*=\s*(\d+)\.sp"""), call)?.toInt()
        assertNotNull(size, "fixture: the cue's fontSize: $call")
        assertTrue(size >= CUE_MIN_SP, "the cue under each chip is $size.sp, below $CUE_MIN_SP.sp")
        assertFalse(Regex("""maxLines\s*=\s*1\b""").containsMatchIn(call), "the cue may not wrap: $call")
    }

    // ---- Q2-91: the card names the ZChat fee apart ----

    @Test
    fun q2_91_the_amount_card_names_the_zchat_fee_apart_from_the_amount() {
        assertEquals("Amount: %1\$s + $zchatFeeEn ZEC ZChat fee", en["new_chat_amount"])
        assertEquals("Monto: %1\$s + $zchatFeeEs ZEC de comisión ZChat", es["new_chat_amount"])
        assertFalse(
            "R.string.new_chat_amount, state.totalAmountDisplay" in composeView,
            "the card still fills %1\$s with the total that already holds the ZChat fee",
        )
    }

    // ---- Q2-103: onboarding names what pays ----

    @Test
    fun q2_103_onboarding_says_tunnel_setup_pays() {
        assertEquals(
            "Share your invite code. Open is free; in Tunnel you pay a setup per contact, in Shielded per message.",
            en["onboarding_identity_subtitle"],
        )
        assertEquals(
            // ZC-1709: fits OnboardingSubtitleNamesWhatIsPaidTest.MAX_ES (130 again): the 128-character line.
            "Comparte tu código de invitación. Open es gratis; en Tunnel pagas la configuración por " +
                "contacto y en Shielded pagas por mensaje.",
            es["onboarding_identity_subtitle"],
        )
        // Review follow-up: the setup is 2-3 payments per contact (NewChatTunnelTextsCountEveryOnChainPaymentTest bans
        // "one-time" / "único" in the Tunnel cue for the same reason).
        assertFalse("one-time" in text(en, "onboarding_identity_subtitle"), "setup is paid per contact, not once")
        assertFalse("única" in text(es, "onboarding_identity_subtitle"), "setup is paid per contact, not once")
    }

    // ---- Q2-104: sent, not delivered ----

    @Test
    fun q2_104_the_sent_screen_says_not_confirmed_yet() {
        assertEquals("Encrypted and sent to the Zcash network — not confirmed yet", en["new_chat_sent_subtitle"])
        assertEquals("Cifrado y enviado a la red Zcash: aún sin confirmar", es["new_chat_sent_subtitle"])
    }

    // ---- Q2-105: Reconnect names the real rule ----

    @Test
    fun q2_105_reconnect_says_free_over_relays_else_an_allowed_handshake_with_the_floor() {
        // Review follow-up: Reconnect under the standing handshake consent sends the on-chain handshake without a price
        // question, so the line must not promise one. The floor is the code's (onChainSendMinimumCost).
        assertEquals(
            "Free if the relays can reach your contact. Otherwise an on-chain handshake transaction of at least " +
                "$floorEn ZEC. If you have allowed handshake transactions, it is paid without another question; " +
                "otherwise nothing is paid until you agree. Your contact may need to update ZChat.",
            en["chat_menu_reconnect_subtitle"],
        )
        assertEquals(
            "Gratis si los relays llegan a tu contacto. Si no, se envía una transacción de enlace en la cadena " +
                "que cuesta al menos $floorEs ZEC. Si ya permitiste las transacciones de enlace, se paga sin otra " +
                "pregunta; si no, no se paga nada hasta que aceptes. Puede que tu contacto tenga que actualizar ZChat.",
            es["chat_menu_reconnect_subtitle"],
        )
        assertFalse("shows the price" in text(en, "chat_menu_reconnect_subtitle"), "Reconnect promises a price prompt")
        assertFalse("muestra el precio" in text(es, "chat_menu_reconnect_subtitle"), "Reconnect promete un precio")
    }

    private fun text(
        strings: Map<String, String>,
        key: String,
    ): String = assertNotNull(strings[key], "no $key")

    /** From [signature] to the next top-level declaration. */
    private fun function(
        src: String,
        signature: String,
    ): String {
        val start = src.indexOf(signature)
        assertTrue(start >= 0, "fixture: $signature in $COMPOSE_VIEW")
        val next = Regex("""\n(@Composable|private fun|internal fun|fun |@StringRes)""").find(src, start + signature.length)
        return src.substring(start, next?.range?.first ?: src.length)
    }

    private fun firstGroup(
        regex: Regex,
        text: String,
    ): String? = regex.find(text)?.let { it.groupValues[1] }

    private fun zec(zatoshi: Long): String = BigDecimal(zatoshi).movePointLeft(8).stripTrailingZeros().toPlainString()

    private companion object {
        const val COMPOSE_STATE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ZchatComposeState.kt"
        const val COMPOSE_VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ZchatComposeView.kt"
        const val CUE_MIN_SP = 12
        const val DISABLED_LABEL_MIN_ALPHA = 0.7f
    }
}
