package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 7 Spanish batch (GPT-6 and Grok, rounds 3-6, verified list) plus the English leftovers the round-6b HONOR
 * pass saw on a Spanish phone (round6b-20260923/phones/RESULT.md, check f).
 *
 * - Every reviewed Spanish line reads as the verified list says (two rows of the list are deliberately unchanged:
 *   chat_shield_needed_hint keeps «Protege», and What's New W5 is not a resource).
 * - Every Spanish string keeps the same format placeholders as its English, so no translation can crash a
 *   format call or drop a number.
 * - The Message Amount dialog, the "New Chat" button and title, the "Synced" status and the accessibility labels
 *   the pass named come from string resources with the old English text as the English value and a Spanish value;
 *   the literal English text is gone from the Kotlin sources.
 * - Mode names stay Shielded / Tunnel / Open (the reviewers' choice). ZEC amounts keep the point inside Spanish
 *   sentences in this batch (recorded for 2.13.1).
 */
class SpanishLeftoversAndPlaceholdersTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val chatViewDir: File
        get() = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view")

    @Test
    fun every_reviewed_spanish_line_reads_as_the_verified_list() {
        val es = strings("values-es")
        val expected =
            mapOf(
                "chat_mode_picker_desc_vault" to
                    "Cada mensaje va en la cadena y cuesta al menos 0,00017 ZEC. El modo más privado.",
                // QA2 W-137: Q2-29 (ZC-983) the code's count, Q2-51 (ZC-1593) what costs no ZEC. Agent drafts.
                "chat_mode_picker_desc_tunnel" to
                    "2–3 pagos en la cadena y luego mensajes y voz gratis por los relays de Nostr.",
                "chat_mode_picker_desc_open" to
                    "Gratis por los relays de Nostr desde el mensaje n.º 1: los textos y las llamadas no cuestan ZEC. " +
                    "Necesita la clave de chat del contacto.",
                "chat_composer_recording_view_once" to "Una sola escucha %1\$d:%2\$02d",
                "chat_feature_take_photo_desc" to "Toma una foto con la cámara (foto cifrada)",
                "chat_feature_view_once" to "Enviar una foto para ver una sola vez",
                "chat_unverified_check_safety_number" to "Comprobar el número de seguridad",
                // QA2 Q2-32 (ZC-1600): "relays de Nostr", never NOSTR in capitals. Agent draft.
                "chat_privacy_chip_nostr" to
                    "Cifrado de extremo a extremo; envío por los relays de Nostr, fuera de la cadena",
                "chat_privacy_chip_stranded" to
                    "Los fondos están en el pool Orchard sellado — mira «Saldo antiguo» en la pestaña Wallet",
                "chat_privacy_chip_shielded" to "Los mensajes van Shielded en la cadena con Zcash",
                "chat_mode_label_vault" to "Shielded (en la cadena)",
                "chat_mode_label_tunnel" to "Tunnel (un intercambio de claves seguro y luego gratis por relays)",
                "chat_mode_label_open" to "Open (gratis, por relays)",
                "chat_note_you_switched_to_match" to
                    "🔀 Has cambiado este chat al modo %1\$s para que coincida con el de %2\$s.",
                "group_message_cost_hint:one" to
                    "Cada mensaje va a %1\$d miembro en su propia transacción: al menos %2\$s ZEC en la cadena, " +
                    "gratis si se puede contactar con él por los relays.",
                "change_identity_full_reset_contacts" to
                    "Tus contactos no reciben tu nueva dirección. Dásela tú mismo: hasta que la tengan, lo que " +
                    "envíen a tu dirección anterior no llega a esta nueva billetera.",
                "chat_message_cost_body" to
                    "Un mensaje Shielded corto cuesta al menos 0,00017 ZEC: la comisión de la red Zcash, una " +
                    "comisión de ZChat de 0,00001 ZEC y 0,00001 ZEC que se entregan con el mensaje. Los mensajes " +
                    "largos, las fotos y las billeteras con muchas cantidades pequeñas cuestan más.",
                // Unchanged on purpose: the Shield button is «Proteger».
                "chat_shield_needed_hint" to "Protege tus fondos en Ironwood para enviar mensajes.",
                "move_old_balance_consent_handshakes" to
                    "Intercambios de claves de chat pendientes: %1\$d, al menos %2\$s en total. Después del " +
                    "movimiento, ZCHAT te volverá a preguntar antes de pagarlos con los fondos movidos.",
                "destroy_item_wallet" to "Tu billetera y tus claves privadas",
                "destroy_locked_minutes" to "Demasiados intentos fallidos. Vuelve a intentarlo dentro de %1\$d min.",
                "destroy_locked_seconds" to "Demasiados intentos fallidos. Vuelve a intentarlo dentro de %1\$d s.",
                "identity_diversified_desc" to
                    "Genera una dirección nueva a partir de la misma frase de recuperación. Tu saldo en ZEC se " +
                    "conserva.",
                "identity_full_reset_title" to "Restablecer la billetera por completo",
                "identity_full_reset_desc" to
                    "Genera una frase de recuperación totalmente nueva. ¡Antes tienes que transferir tu ZEC! La " +
                    "identidad antigua se borra de forma permanente.",
                "identity_reset_wallet" to "Restablecer la billetera",
                "identity_confirm_reset_title" to "Confirmar el restablecimiento de la billetera",
                "identity_reset_warning" to "Esto borrará de forma PERMANENTE tu billetera actual. Asegúrate de haber:",
                "identity_reset_step_seed" to "1. Guardado una copia de tu frase de recuperación",
                "identity_reset_step_transfer" to "2. Transferido todo tu ZEC a otra billetera",
                "notif_battery_subtitle" to "Excluye a ZCHAT para que la sincronización en segundo plano sea fiable",
                "receive_info_shielded_bullet_3" to
                    "Todas las transacciones enviadas a tus diferentes direcciones protegidas rotativas formarán " +
                    "parte de un mismo saldo de billetera vinculado a tu frase de recuperación.",
                // Round 8 D8 (F34): says when to tap Reintentar instead of «Termina solo; entonces».
                "chat_send_still_reading_history" to
                    "ZCHAT sigue leyendo el historial de este chat en la cadena. No se envió nada y no se pagó " +
                    "nada. Cuando termine, toca Reintentar.",
            )
        val wrong =
            expected
                .filter { (name, value) -> es[name] != value }
                .map { (name, value) -> "$name\n  is:   ${es[name]}\n  want: $value" }
        assertTrue(wrong.isEmpty(), "Spanish lines that do not read as reviewed:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun every_spanish_string_keeps_the_placeholders_of_its_english() {
        val en = strings("values")
        val es = strings("values-es")
        assertTrue(es.size > 500, "fixture: the Spanish strings were found: ${es.size}")
        val problems =
            es.mapNotNull { (key, spanish) ->
                val english = en[key] ?: en[key.substringBefore(':') + ":other"] ?: return@mapNotNull null
                val want = placeholders(english)
                val got = placeholders(spanish)
                if (want == got) null else "$key: English $want, Spanish $got"
            }
        assertTrue(problems.isEmpty(), "Spanish strings whose placeholders differ:\n" + problems.joinToString("\n"))
    }

    @Test
    fun the_leftovers_the_honor_pass_saw_have_english_and_spanish_resources() {
        val en = strings("values")
        val es = strings("values-es")
        for ((name, pair) in LEFTOVERS) {
            assertEquals(pair.first, en[name], "English $name must keep the text the app showed")
            assertEquals(pair.second, es[name], "Spanish $name")
        }
        // Every Spanish line that names the New Chat button names it as the button now reads.
        assertEquals(
            "Pégala en Nuevo chat, abre este chat y toca el modo bajo el nombre del contacto → Open.",
            es["upgrade_no_key_2"],
        )
        val stale = es.filter { (_, v) -> "New Chat" in v }.keys
        assertTrue(stale.isEmpty(), "Spanish strings still naming the English New Chat button: $stale")
    }

    @Test
    fun the_leftover_english_literals_are_gone_from_the_screens() {
        val literals =
            mapOf(
                "ChatListView.kt" to listOf("\"New Chat\"", "\"Synced\"", "\"Syncing...\""),
                "ZchatComposeView.kt" to listOf("\"New Chat\""),
                "ZchatAvatar.kt" to listOf("\"Group\"", "\"My avatar\""),
                "ChatDetailView.kt" to
                    listOf(
                        "\"Message Amount\"",
                        "\"Select the amount to send with each message:\"",
                        "\"Privacy Status\"",
                        "\"Voice call\"",
                        "\"Video call\"",
                        "\"Voice call (exchanges secure keys, then connects)\"",
                        "\"Video call (exchanges secure keys, then connects)\"",
                        "\"Record voice message — long-press for view-once\"",
                        "\"Collapse\"",
                        "\"Expand\"",
                        "\"Details\"",
                        "messageStatusDescription(",
                    ),
            )
        val found =
            literals.flatMap { (file, list) ->
                val code = code(File(chatViewDir, file))
                list.filter { it in code }.map { "$file still holds $it" }
            }
        assertTrue(found.isEmpty(), found.joinToString("\n"))

        // The amount picker's own Cancel button, not the nickname dialog's above it.
        // Read raw: the two markers are comments, which code() drops.
        val detail = File(chatViewDir, "ChatDetailView.kt").readText()
        val start = detail.indexOf("// Amount Picker Dialog")
        val end = detail.indexOf("// Time-Lock Composer Dialog")
        assertTrue(start in 0 until end, "fixture: the amount picker block was found")
        val picker = detail.substring(start, end)
        assertTrue("Text(\"" !in picker, "the amount picker still shows hard-coded text:\n$picker")
    }

    private fun code(file: File): String =
        file
            .readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    /** Placeholders as (argument index, conversion) pairs; unnumbered ones count up from 1. */
    private fun placeholders(text: String): List<String> {
        var next = 1
        return PLACEHOLDER
            .findAll(text)
            .filter { it.value != "%%" }
            .map { m ->
                val index = m.groupValues[1].ifEmpty { (next++).toString() }
                "%$index\$${m.groupValues[2]}"
            }.sorted()
            .toList()
    }

    private fun strings(dir: String): Map<String, String> =
        listOf("ui-lib/src/main/res/ui", "ui-design-lib/src/main/res/ui")
            .map { File(repoRoot, it) }
            .flatMap { root -> root.listFiles().orEmpty().toList() }
            .flatMap { module ->
                File(module, dir)
                    .listFiles { f -> f.extension == "xml" }
                    .orEmpty()
                    .toList()
            }.fold(emptyMap()) { acc, f -> acc + parse(f.readText()) }

    private fun parse(xml: String): Map<String, String> {
        val text = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val out = mutableMapOf<String, String>()
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .forEach { out[it.groupValues[1]] = unescape(it.groupValues[2]) }
        Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .forEach { p ->
                Regex("""<item quantity="(\w+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(p.groupValues[2])
                    .forEach { out[p.groupValues[1] + ":" + it.groupValues[1]] = unescape(it.groupValues[2]) }
            }
        return out
    }

    private fun unescape(s: String): String =
        s
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private companion object {
        val PLACEHOLDER = Regex("""%(?:(\d+)\$)?[-#+0,(]*\d*(?:\.\d+)?([sdfxXc%])""")

        val LEFTOVERS: Map<String, Pair<String, String>> =
            mapOf(
                "chat_new_chat" to ("New Chat" to "Nuevo chat"),
                "chat_list_status_synced" to ("Synced" to "Sincronizado"),
                "chat_list_status_syncing" to ("Syncing..." to "Sincronizando..."),
                "chat_amount_picker_title" to ("Message Amount" to "Monto del mensaje"),
                "chat_amount_picker_body" to
                    ("Select the amount to send with each message:" to "Elige el monto que se envía con cada mensaje:"),
                "chat_a11y_group_avatar" to ("Group" to "Grupo"),
                "chat_a11y_my_avatar" to ("My avatar" to "Mi avatar"),
                "chat_a11y_expand" to ("Expand" to "Desplegar"),
                "chat_a11y_collapse" to ("Collapse" to "Contraer"),
                "chat_a11y_details" to ("Details" to "Detalles"),
                "chat_a11y_privacy_status" to ("Privacy Status" to "Estado de privacidad"),
                "chat_a11y_voice_call" to ("Voice call" to "Llamada de voz"),
                "chat_a11y_voice_call_not_ready" to
                    (
                        "Voice call (exchanges secure keys, then connects)" to
                            "Llamada de voz (intercambia claves seguras y luego conecta)"
                    ),
                "chat_a11y_video_call" to ("Video call" to "Videollamada"),
                "chat_a11y_video_call_not_ready" to
                    (
                        "Video call (exchanges secure keys, then connects)" to
                            "Videollamada (intercambia claves seguras y luego conecta)"
                    ),
                "chat_a11y_record_voice" to
                    (
                        "Record voice message — long-press for view-once" to
                            "Grabar un mensaje de voz — mantén pulsado para una sola escucha"
                    ),
                "chat_a11y_status_sent" to ("Sent to the network" to "Enviado a la red"),
                "chat_a11y_status_on_chain" to ("On the blockchain" to "En la cadena"),
            )
    }
}
