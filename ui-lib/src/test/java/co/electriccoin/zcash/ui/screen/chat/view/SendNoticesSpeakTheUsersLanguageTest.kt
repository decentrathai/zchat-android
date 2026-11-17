package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 8 D8 (REVIEW_7C smaller issue 2, F33, F34; sweep #9) — every notice the chat shows after a send is true and
 * reads in the phone's language.
 *
 * The chat screens show a SendMessageState.Success / Error text through ChatSendCopy.localizedResource: a text the
 * map knows is shown from its resource (English or Spanish), any other text is shown as it is, in English. At
 * 93585cbce ChatViewModel still hands the screen English literals no map entry knows: "Message sent", "Payment
 * request sent", "Reaction sent", "Failed to send reaction", "Reaction not sent — …", the queue's time-out and
 * retry-cap texts, the Reconnect texts, "Incorrect answer", the timer and mode notices, the locked-message and
 * on-chain-only texts, and the "Failed to …" fallbacks of the error mapper. The "Secure session isn't ready" refusal
 * is English only and promises "It'll send once the encrypted connection re-establishes", which nothing does.
 *
 * Pinned here:
 * - a scan of ChatViewModel and GroupViewModel: no string literal is handed to a SendMessageState.Success / Error, to
 *   submittedSendState, to sendRefusedReason or to a `val errorMessage` unless it resolves through
 *   ChatSendCopy.localizedResource (so, in practice, no literal at all: the texts are ChatSendCopy constants);
 * - every new notice has an English resource byte-equal to its constant and a Spanish one, and the texts below are
 *   the reviewed ones (the Spanish is agent-drafted and listed in the GPT-6 / native-speaker brief);
 * - the chat's "Delete Message" menu item and the "Failed" status icon label come from resources;
 * - F34: the Spanish STILL_READING_HISTORY ends «Cuando termine, toca Reintentar.».
 */
class SendNoticesSpeakTheUsersLanguageTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    // ── the scan ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun every_literal_the_view_models_hand_to_a_send_notice_resolves_to_a_resource() {
        val problems =
            VIEW_MODELS.flatMap { path ->
                val source = ChatResourceText.source(path)
                noticeSpans(source).flatMap { span ->
                    stringLiterals(span).mapNotNull { literal ->
                        when {
                            literal.template -> "${path.substringAfterLast('/')}: a template cannot be looked up: \"${literal.text}\""
                            ChatSendCopy.localizedResource(literal.text) == null ->
                                "${path.substringAfterLast('/')}: English only: \"${literal.text}\""
                            else -> null
                        }
                    }
                }
            }
        assertTrue(problems.isEmpty(), "send notices with no resource:\n" + problems.joinToString("\n"))
    }

    @Test
    fun the_scan_finds_the_literals_it_is_meant_to_find() {
        val sample =
            """
            _sendMessageState.value = SendMessageState.Success("Reaction sent")
            _sendMessageState.value = SendMessageState.Error(
                "Couldn't send the locked message yet — either " +
                    "or you need a little more ZEC."
            )
            _sendMessageState.value = SendMessageState.Error(e.toZchatUserMessage("Failed to send reaction"))
            _sendMessageState.value = submittedSendState(outcome, sent = "Message sent", unconfirmed = X)
            throw IllegalStateException(sendRefusedReason(peerAddress, "Secure session isn't ready yet"))
            val errorMessage =
                when {
                    x -> "Insufficient balance. Please add ZEC to your wallet to send messages."
                    else -> e.toZchatUserMessage("Failed to send reply")
                }
            _sendMessageState.value = SendMessageState.Error("Message failed after ${'$'}MAX_QUEUE_RETRIES retries")
            _sendMessageState.value = SendMessageState.Error(ChatSendCopy.PAYMENT_NOT_SENT)
            """.trimIndent()
        val found = noticeSpans(sample).flatMap { stringLiterals(it) }.associate { it.text to it.template }
        assertEquals(
            mapOf(
                "Reaction sent" to false,
                "Couldn't send the locked message yet — either or you need a little more ZEC." to false,
                "Failed to send reaction" to false,
                "Message sent" to false,
                "Secure session isn't ready yet" to false,
                "Insufficient balance. Please add ZEC to your wallet to send messages." to false,
                "Failed to send reply" to false,
                "Message failed after ${'$'}MAX_QUEUE_RETRIES retries" to true,
            ),
            found,
        )
    }

    @Test
    fun the_default_success_label_resolves_to_a_resource() {
        val label = SendMessageState.Success().label
        assertNotNull(ChatSendCopy.localizedResource(label), "SendMessageState.Success() says \"$label\" in English only")
    }

    // ── the new notices, in English and Spanish ──────────────────────────────────────────────────────

    @Test
    fun every_new_notice_constant_has_its_english_and_spanish_resource() {
        val problems =
            NEW_NOTICES.mapNotNull { notice ->
                val text =
                    runCatching {
                        ChatSendCopy::class.java
                            .getDeclaredField(notice.constant)
                            .apply { isAccessible = true }
                            .get(null) as String
                    }.getOrNull() ?: return@mapNotNull "ChatSendCopy.${notice.constant} is missing"
                val id = ChatSendCopy.localizedResource(text) ?: return@mapNotNull "ChatSendCopy.${notice.constant} has no resource"
                val name = ChatResourceText.stringName(id)
                when {
                    name != notice.resource -> "ChatSendCopy.${notice.constant} maps to $name, not ${notice.resource}"
                    text != notice.english -> "ChatSendCopy.${notice.constant} is \"$text\", not the reviewed \"${notice.english}\""
                    en[name] != text -> "English $name is \"${en[name]}\", not the constant \"$text\""
                    es[name] != notice.spanish -> "Spanish $name is \"${es[name]}\", not \"${notice.spanish}\""
                    else -> null
                }
            }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun every_new_screen_string_has_its_english_and_spanish_resource() {
        val problems =
            NEW_SCREEN_STRINGS.mapNotNull { (name, pair) ->
                when {
                    en[name] != pair.first -> "English $name is \"${en[name]}\", not \"${pair.first}\""
                    es[name] != pair.second -> "Spanish $name is \"${es[name]}\", not \"${pair.second}\""
                    else -> null
                }
            }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun the_new_group_plurals_read_in_english_and_spanish() {
        val enPlurals = ChatResourceText.plurals("values")
        val esPlurals = ChatResourceText.plurals("values-es")
        for ((name, expected) in NEW_PLURALS) {
            assertEquals(expected.first, enPlurals[name], "English plurals $name")
            assertEquals(expected.second, esPlurals[name], "Spanish plurals $name")
        }
    }

    @Test
    fun the_retry_cap_notice_names_the_real_retry_count() {
        // QUEUE_RETRIES_EXHAUSTED is a fixed sentence (a toast shows it through its resource with no arguments), so the
        // count in it must be ChatViewModel's MAX_QUEUE_RETRIES.
        val cap =
            co.electriccoin.zcash.ui.screen.chat.viewmodel.ChatViewModel::class.java
                .getDeclaredField("MAX_QUEUE_RETRIES")
                .apply { isAccessible = true }
                .getInt(null)
        assertEquals("Message failed after $cap retries", ChatSendCopy.QUEUE_RETRIES_EXHAUSTED)
    }

    @Test
    fun the_secure_session_refusal_promises_no_automatic_send() {
        val chatViewModel = ChatResourceText.source(CHAT_VIEW_MODEL)
        assertFalse("It'll send once" in chatViewModel, "ChatViewModel still promises the refused message goes by itself")
        for (constant in listOf("SECURE_SESSION_NOT_READY_MESSAGE", "SECURE_SESSION_NOT_READY_REPLY")) {
            val text =
                runCatching {
                    ChatSendCopy::class.java
                        .getDeclaredField(constant)
                        .apply { isAccessible = true }
                        .get(null) as String
                }.getOrNull()
            assertNotNull(text, "ChatSendCopy.$constant is missing")
            for (promise in listOf("It'll send", "will send", "send once")) {
                assertFalse(text.contains(promise, ignoreCase = true), "$constant promises an automatic send: $text")
            }
        }
    }

    // ── the chat screen's menu and status icon ───────────────────────────────────────────────────────

    @Test
    fun the_delete_menu_item_and_the_failed_icon_label_come_from_resources() {
        val detail = ChatResourceText.source(CHAT_DETAIL_VIEW)
        assertFalse("Text(\"Delete Message\")" in detail, "the message menu's Delete item is English only")
        assertFalse("contentDescription = \"Failed\"" in detail, "the Failed status icon's label is English only")
        assertTrue("R.string.chat_message_menu_delete" in detail, "the Delete item must read chat_message_menu_delete")
        assertTrue("R.string.chat_message_status_failed" in detail, "the Failed icon must read chat_message_status_failed")
    }

    // ── F34 ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_spanish_still_reading_history_notice_ends_with_tap_retry_when_it_finishes() {
        assertEquals(
            "ZCHAT sigue leyendo el historial de este chat en la cadena. No se envió nada y no se pagó nada. " +
                "Cuando termine, toca Reintentar.",
            es["chat_send_still_reading_history"],
        )
    }

    // ── the scanner ──────────────────────────────────────────────────────────────────────────────────

    private data class Literal(
        val text: String,
        val template: Boolean,
    )

    /**
     * The source text of every argument list handed to a notice: `SendMessageState.Success(…)`,
     * `SendMessageState.Error(…)`, `submittedSendState(…)` and `sendRefusedReason(…)` calls (not their
     * declarations), and the expression of every `val errorMessage =`.
     */
    private fun noticeSpans(source: String): List<String> {
        val spans = mutableListOf<String>()
        val calls = Regex("""(?<!fun )\b(SendMessageState\.Success|SendMessageState\.Error|submittedSendState|sendRefusedReason)\(""")
        for (m in calls.findAll(source)) {
            balancedFrom(source, m.range.last)?.let(spans::add)
        }
        for (m in Regex("""\bval errorMessage\s*=\s*""").findAll(source)) {
            val start = m.range.last + 1
            val rest = source.substring(start)
            if (rest.startsWith("when")) {
                val brace = source.indexOf('{', start)
                balancedFrom(source, brace, '{', '}')?.let(spans::add)
            } else {
                spans += rest.substringBefore('\n')
            }
        }
        return spans
    }

    /** The text from [open] (an opening bracket) to its matching closing one, skipping strings and comments. */
    private fun balancedFrom(
        source: String,
        open: Int,
        openChar: Char = '(',
        closeChar: Char = ')',
    ): String? {
        var depth = 0
        var i = open
        while (i < source.length) {
            val c = source[i]
            when {
                c == '"' -> i = endOfString(source, i)
                c == '/' && source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                c == openChar -> depth++
                c == closeChar -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        return null
    }

    /** The index of the closing quote of the string literal that opens at [start]. */
    private fun endOfString(
        source: String,
        start: Int,
    ): Int {
        var i = start + 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i++
                '"' -> return i
                '$' ->
                    if (source.getOrNull(i + 1) == '{') {
                        var depth = 0
                        while (i < source.length) {
                            if (source[i] == '{') depth++
                            if (source[i] == '}') {
                                depth--
                                if (depth == 0) break
                            }
                            i++
                        }
                    }
            }
            i++
        }
        return source.length
    }

    /** Every string literal in [span], with `"a" + "b"` pieces joined into one text. */
    private fun stringLiterals(span: String): List<Literal> {
        val pieces = mutableListOf<Pair<IntRange, Literal>>()
        var i = 0
        while (i < span.length) {
            val c = span[i]
            if (c == '/' && span.startsWith("//", i)) {
                i = span.indexOf('\n', i).let { if (it < 0) span.length else it }
                continue
            }
            if (c == '"') {
                val end = endOfString(span, i)
                val raw = span.substring(i + 1, end)
                pieces += (i..end) to Literal(unescape(raw), isTemplate(raw))
                i = end + 1
                continue
            }
            i++
        }
        val joined = mutableListOf<Literal>()
        var previousEnd = -1
        for ((range, literal) in pieces) {
            val between = if (previousEnd >= 0) span.substring(previousEnd + 1, range.first) else null
            if (between != null && between.isNotBlank() && between.trim() == "+" && joined.isNotEmpty()) {
                val last = joined.removeAt(joined.lastIndex)
                joined += Literal(last.text + literal.text, last.template || literal.template)
            } else {
                joined += literal
            }
            previousEnd = range.last
        }
        return joined
    }

    private fun isTemplate(raw: String): Boolean = Regex("""(?<!\\)\$[{A-Za-z_]""").containsMatchIn(raw)

    private fun unescape(raw: String): String =
        raw
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\$", "$")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")

    private data class Notice(
        val constant: String,
        val resource: String,
        val english: String,
        val spanish: String,
    )

    private companion object {
        const val CHAT_VIEW_MODEL = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
        const val GROUP_VIEW_MODEL = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/GroupViewModel.kt"
        const val CHAT_DETAIL_VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
        val VIEW_MODELS = listOf(CHAT_VIEW_MODEL, GROUP_VIEW_MODEL)

        /** Every notice D8 adds: its ChatSendCopy constant, resource, English (the constant) and reviewed Spanish. */
        val NEW_NOTICES =
            listOf(
                // F3
                Notice(
                    "GROUP_KEYSTONE_REFUSED",
                    "chat_send_group_keystone_refused",
                    // K0 (fix-all 2.13.0): "in this version" (Keystone chat signing is closed in 2.13.0).
                    "Group messages can't be sent from a Keystone account in this version. Nothing was sent or paid.",
                    "Los mensajes de grupo no se pueden enviar desde una cuenta Keystone en esta versión. No se envió " +
                        "ni se pagó nada.",
                ),
                // F31
                Notice(
                    "CONTROL_STILL_READING_HISTORY",
                    "chat_send_control_still_reading_history",
                    "ZCHAT is still reading this chat's history from the blockchain. Nothing was sent and nothing was " +
                        "paid. Once it finishes, set it again.",
                    "ZCHAT sigue leyendo el historial de este chat en la cadena. No se envió nada y no se pagó nada. " +
                        "Cuando termine, vuelve a configurarlo.",
                ),
                Notice(
                    "MODE_STILL_READING_HISTORY",
                    "chat_send_mode_still_reading_history",
                    "Mode changed on this device only. ZCHAT is still reading this chat's history from the blockchain, " +
                        "so your contact wasn't told. Nothing was sent and nothing was paid.",
                    "El modo cambió solo en este dispositivo. ZCHAT sigue leyendo el historial de este chat en la " +
                        "cadena, así que tu contacto no fue avisado. No se envió nada y no se pagó nada.",
                ),
                // F33
                Notice(
                    "SECURE_SESSION_NOT_READY_MESSAGE",
                    "chat_send_secure_session_not_ready_message",
                    "Secure session isn't ready yet — message not sent (it won't be sent unencrypted). Tap Retry once " +
                        "the secure connection is back.",
                    "La sesión segura aún no está lista: el mensaje no se envió (no se enviará sin cifrar). Toca " +
                        "Reintentar cuando vuelva la conexión segura.",
                ),
                Notice(
                    "SECURE_SESSION_NOT_READY_REPLY",
                    "chat_send_secure_session_not_ready_reply",
                    "Secure session isn't ready yet — reply not sent (it won't be sent unencrypted). Tap Retry once " +
                        "the secure connection is back.",
                    "La sesión segura aún no está lista: la respuesta no se envió (no se enviará sin cifrar). Toca " +
                        "Reintentar cuando vuelva la conexión segura.",
                ),
                // Smaller issue 2 (the listed toasts)
                Notice(
                    "MESSAGE_SENT",
                    "chat_send_message_sent",
                    "Sent to the Zcash network — confirming",
                    "Enviado a la red Zcash: confirmando",
                ),
                Notice("PAYMENT_REQUEST_SENT", "chat_send_payment_request_sent", "Payment request sent", "Solicitud de pago enviada"),
                Notice("REACTION_SENT", "chat_send_reaction_sent", "Reaction sent", "Reacción enviada"),
                Notice("REACTION_FAILED", "chat_send_reaction_failed", "Failed to send reaction", "No se pudo enviar la reacción"),
                Notice(
                    "REACTION_NOT_CONNECTED",
                    "chat_send_reaction_not_connected",
                    "Reaction not sent — this chat isn't connected yet",
                    "Reacción no enviada: este chat aún no está conectado",
                ),
                Notice(
                    "QUEUE_TIMED_OUT",
                    "chat_send_queue_timed_out",
                    "Message send timed out waiting for block confirmation",
                    "Se agotó el tiempo de espera de la confirmación del bloque para el mensaje",
                ),
                Notice(
                    "QUEUE_RETRIES_EXHAUSTED",
                    "chat_send_queue_retries_exhausted",
                    "Message failed after 4 retries",
                    "El mensaje falló tras 4 reintentos",
                ),
                Notice(
                    "RECONNECTED_DETAILS_RESENT",
                    "chat_send_reconnected_details_resent",
                    "Reconnected — re-sent your details (allow ~2 min)",
                    "Reconectado: se reenviaron tus datos (espera unos 2 min)",
                ),
                Notice(
                    "KEY_RESENT",
                    "chat_send_key_resent",
                    "Re-sent your key — allow ~2 min to reconnect",
                    "Clave reenviada: espera unos 2 min para reconectar",
                ),
                Notice("INCORRECT_ANSWER", "chat_send_incorrect_answer", "Incorrect answer", "Respuesta incorrecta"),
                // The rest of the literals the scan finds (agent-drafted Spanish; in the native-speaker brief).
                Notice(
                    "RECONNECT_FAILED",
                    "chat_send_reconnect_failed",
                    "Couldn't reconnect — please try again.",
                    "No se pudo reconectar. Inténtalo de nuevo.",
                ),
                Notice(
                    "TIMER_NOT_DELIVERED",
                    "chat_send_timer_not_delivered",
                    "Timer set on this device only — couldn't reach your contact right now, so their timer didn't " +
                        "change. Set it again once you're both online.",
                    "Temporizador configurado solo en este dispositivo: no se pudo llegar a tu contacto ahora mismo, " +
                        "así que su temporizador no cambió. Vuelve a configurarlo cuando los dos estén en línea.",
                ),
                Notice(
                    "TIMER_NEEDS_KEY_EXCHANGE",
                    "chat_send_timer_needs_key_exchange",
                    "Finish the encrypted key exchange first, then set the timer.",
                    "Primero termina el intercambio de claves cifrado y luego configura el temporizador.",
                ),
                Notice(
                    "TIMER_SYNC_FAILED",
                    "chat_send_timer_sync_failed",
                    "Couldn't sync the disappearing-message timer — try again.",
                    "No se pudo sincronizar el temporizador de mensajes temporales. Inténtalo de nuevo.",
                ),
                Notice(
                    "MODE_NOT_DELIVERED",
                    "chat_send_mode_not_delivered",
                    "Mode changed on this device — couldn't reach your contact right now; it'll sync automatically " +
                        "once you're both online.",
                    "El modo cambió en este dispositivo: no se pudo llegar a tu contacto ahora mismo; se sincronizará " +
                        "automáticamente cuando los dos estén en línea.",
                ),
                Notice(
                    "MODE_RELAY_UNREACHABLE",
                    "chat_send_mode_relay_unreachable",
                    "Mode changed on this device — couldn't notify your contact (relay unreachable); it'll sync " +
                        "automatically when you're both online.",
                    "El modo cambió en este dispositivo: no se pudo avisar a tu contacto (relay inaccesible); se " +
                        "sincronizará automáticamente cuando los dos estén en línea.",
                ),
                Notice(
                    "MODE_NEEDS_KEY_EXCHANGE",
                    "chat_send_mode_needs_key_exchange",
                    "Mode changed on this device only — the encrypted key exchange isn't finished, so your contact " +
                        "wasn't told.",
                    "El modo cambió solo en este dispositivo: el intercambio de claves cifrado no ha terminado, así " +
                        "que tu contacto no fue avisado.",
                ),
                Notice(
                    "MODE_SYNC_FAILED",
                    "chat_send_mode_sync_failed",
                    "Mode changed on this device only — couldn't tell your contact.",
                    "El modo cambió solo en este dispositivo: no se pudo avisar a tu contacto.",
                ),
                Notice(
                    "ON_CHAIN_FEATURE_ONLY",
                    "chat_send_on_chain_feature_only",
                    "This works only in Shielded (on-chain) chats. This chat stays free — nothing was sent or charged.",
                    "Esto solo funciona en chats Shielded (en la cadena). Este chat sigue siendo gratis: no se envió " +
                        "ni se cobró nada.",
                ),
                Notice(
                    "KEY_CHANGED_VERIFY",
                    "chat_send_key_changed_verify",
                    // ZC-250: the short cue points to the red warning, which names what accepting the key costs.
                    "Not sent: this contact's security key changed. See the red warning at the top of the chat.",
                    "No enviado: la clave de seguridad de este contacto cambió. Mira el aviso rojo arriba en el chat.",
                ),
                Notice(
                    "LOCKED_MESSAGE_NOT_SENT",
                    "chat_send_locked_message_not_sent",
                    "Couldn't send the locked message — nothing was sent. A previous on-chain message may still be " +
                        "confirming, or you may need a little more ZEC. Wait a moment and send it again, or add ZEC.",
                    "No se pudo enviar el mensaje bloqueado: no se envió nada. Puede que un mensaje anterior en la " +
                        "cadena aún se esté confirmando o que necesites un poco más de ZEC. Espera un momento y " +
                        "vuelve a enviarlo, o añade ZEC.",
                ),
                Notice(
                    "SCHEDULED_FAILED",
                    "chat_send_scheduled_failed",
                    "Failed to send scheduled message",
                    "No se pudo enviar el mensaje programado",
                ),
                Notice(
                    "BLOCK_LOCKED_FAILED",
                    "chat_send_block_locked_failed",
                    "Failed to send block-locked message",
                    "No se pudo enviar el mensaje bloqueado hasta un bloque",
                ),
                Notice(
                    "PAYMENT_LOCKED_FAILED",
                    "chat_send_payment_locked_failed",
                    "Failed to send payment-locked message",
                    "No se pudo enviar el mensaje bloqueado con pago",
                ),
                Notice(
                    "CONDITIONAL_FAILED",
                    "chat_send_conditional_failed",
                    "Failed to send conditional message",
                    "No se pudo enviar el mensaje condicional",
                ),
                Notice("UNLOCK_FAILED", "chat_send_unlock_failed", "Failed to unlock message", "No se pudo desbloquear el mensaje"),
                Notice("ANSWER_FAILED", "chat_send_answer_failed", "Failed to submit answer", "No se pudo enviar la respuesta secreta"),
                Notice("PAYMENT_FAILED", "chat_send_payment_failed", "Failed to send payment", "No se pudo enviar el pago"),
                Notice(
                    "PAYMENT_REQUEST_FAILED",
                    "chat_send_payment_request_failed",
                    "Failed to send payment request",
                    "No se pudo enviar la solicitud de pago",
                ),
                Notice("MESSAGE_FAILED", "chat_send_message_failed", "Failed to send message", "No se pudo enviar el mensaje"),
                Notice("REPLY_FAILED", "chat_send_reply_failed", "Failed to send reply", "No se pudo enviar la respuesta"),
                Notice(
                    "INSUFFICIENT_BALANCE_SHORT",
                    "chat_send_insufficient_balance_short",
                    "Insufficient balance. Please add ZEC to your wallet to send messages.",
                    "Saldo insuficiente. Añade ZEC a tu billetera para enviar mensajes.",
                ),
            )

        /** The chat screen's new resources that are not send notices. */
        val NEW_SCREEN_STRINGS =
            mapOf(
                "chat_message_menu_delete" to ("Delete Message" to "Eliminar mensaje"),
                "chat_message_status_failed" to ("Failed" to "Error"),
                "chat_delete_message_body_relay" to
                    (
                        "This removes the message from your chat on this phone. It went over the free relay network, " +
                            "so there is no Zcash transaction for it." to
                            "Esto quita el mensaje de tu chat en este teléfono. Se envió por la red gratuita de relays, " +
                            "así que no hay ninguna transacción de Zcash para él."
                    ),
                "chat_delete_message_body_maybe_sent" to
                    (
                        "This removes the message from your chat. It may have been sent; if it was, its transaction " +
                            "stays on the Zcash blockchain and can reappear if you restore from your recovery phrase." to
                            "Esto quita el mensaje de tu chat. Puede que se haya enviado; si fue así, su transacción " +
                            "queda en la cadena de bloques de Zcash y puede volver a aparecer si restauras desde tu " +
                            "frase de recuperación."
                    ),
            )

        /** F3 — the group banners' count lines when a refusal is named (quantity to text). */
        val NEW_PLURALS =
            mapOf(
                "group_invite_refused" to
                    (
                        mapOf("one" to "Couldn't invite %1\$d member", "other" to "Couldn't invite %1\$d members") to
                            mapOf(
                                "one" to "No se pudo invitar a %1\$d miembro",
                                "many" to "No se pudo invitar a %1\$d miembros",
                                "other" to "No se pudo invitar a %1\$d miembros",
                            )
                    ),
                "group_removal_incomplete_refused" to
                    (
                        mapOf(
                            "one" to "%1\$d member hasn't received the new group key yet",
                            "other" to "%1\$d members haven't received the new group key yet",
                        ) to
                            mapOf(
                                "one" to "%1\$d miembro aún no ha recibido la nueva clave del grupo",
                                "many" to "%1\$d miembros aún no han recibido la nueva clave del grupo",
                                "other" to "%1\$d miembros aún no han recibido la nueva clave del grupo",
                            )
                    ),
            )
    }
}
