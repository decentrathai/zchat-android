package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1685, QA2 Spanish review (GPT answer in qa2/es-review-w1/gpt_answer.md, orchestrator decisions).
 *
 * Part A: Spanish-only rewrites of 17 lines (exact text, placeholders kept).
 * Part B: English and Spanish together: the restore error says the user can lose access (not that funds are erased),
 * the group cost note says the paid transaction applies only to members the relays cannot reach, the Open warning
 * says "every message you send", and the shield subtitle shown right after the broadcast says it is still being
 * shielded (TransactionProgressVM shows it on SubmitResult.Success, before any confirmation).
 */
class SpanishReviewFixesW137Test {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val resRoot: File get() = File(repoRoot, "ui-lib/src/main/res/ui")

    private val en: Map<String, String> by lazy { allStrings("values") }
    private val es: Map<String, String> by lazy { allStrings("values-es") }
    private val enPlurals: Map<String, Map<String, String>> by lazy { allPlurals("values") }
    private val esPlurals: Map<String, Map<String, String>> by lazy { allPlurals("values-es") }

    /** Part A: exact Spanish text per key (placeholders kept exactly). */
    private val partA: Map<String, String> =
        mapOf(
            "authentication_address_book_subtitle" to
                "Para abrir la libreta de direcciones, usa el método de desbloqueo de tu teléfono.",
            "call_duration_a11y" to "Duración de la llamada. Minutos: %1\$d. Segundos: %2\$d.",
            "call_route_selected" to "%1\$s, en uso",
            "chat_list_message_requests_hint" to
                "Toca Revisar para aceptar o rechazar. Los mensajes son gratis y viajan cifrados por los relays " +
                "de Nostr.",
            "chat_menu_reconnect_subtitle" to
                "Gratis si los relays llegan a tu contacto. Si no, se envía una transacción de enlace en la " +
                "cadena que cuesta al menos 0,00017 ZEC. Si ya permitiste las transacciones de enlace, se paga " +
                "sin otra pregunta; si no, no se paga nada hasta que aceptes. " +
                "Puede que tu contacto tenga que actualizar ZChat.",
            "chat_privacy_chip_nostr" to
                "Cifrado de extremo a extremo; envío por los relays de Nostr, fuera de la cadena",
            "chat_privacy_chip_tunnel_setup" to
                "La configuración se paga en la cadena; después, los mensajes van por los relays de Nostr",
            "group_remove_member_body" to
                "¿Quitar a %1\$s del grupo? Los demás miembros recibirán una nueva clave de grupo para que %1\$s ya " +
                "no pueda leer los mensajes nuevos. Enviar la clave por los relays es gratis; si solo se puede " +
                "enviar a algún miembro por la cadena, ZChat te mostrará el precio antes.",
            "new_chat_free_desc" to
                "Mensaje directo con cifrado de extremo a extremo, enviado por los relays de Nostr: los textos no " +
                "cuestan ZEC ni crean una transacción en la cadena.",
            "new_chat_mode_blurb_open" to
                "Gratis desde el primer mensaje: mensaje directo con cifrado de extremo a extremo, enviado por los " +
                "relays de Nostr; los textos y las llamadas no cuestan ZEC. El destinatario acepta tu solicitud " +
                "una vez.",
            "home_info_transparent_message" to
                "Esto creará una transacción de protección dentro de tu billetera que consolidará tus fondos " +
                "transparentes y protegidos. Podrás gastarlos cuando se confirme, en unos 10 bloques. " +
                "(Comisión típica: %1\$s %2\$s)",
            "onboarding_identity_subtitle" to
                "Comparte tu código de invitación. Open es gratis; en Tunnel pagas la configuración por " +
                "contacto y en Shielded pagas por mensaje.",
            "swap_change_mode_content_description" to "Cambiar el sentido del intercambio",
            "transaction_detail_waiting_body" to
                "Suele minarse en minutos. Si al llegar al bloque %1\$s (en unos %2\$d min) no se ha minado, ya no " +
                "podrá confirmarse y será seguro enviarla de nuevo. Hasta entonces, si la envías de nuevo, podrías " +
                "pagar dos veces.",
        )

    @Test
    fun part_a_spanish_lines_read_as_reviewed() {
        val wrong =
            partA.mapNotNull { (key, expected) ->
                val actual = es[key]
                if (actual == expected) null else "$key\n  want: $expected\n  have: $actual"
            }
        assertTrue(wrong.isEmpty(), "Spanish lines not yet rewritten (${wrong.size}):\n" + wrong.joinToString("\n"))
    }

    /** The verb is "introduce" (Spain-neutral) or the file's own "Ingresa"; never "Enviar todo necesita". */
    @Test
    fun part_a_send_all_needs_recipient_is_an_instruction() {
        val key = "send_all_reason_needs_recipient"
        val text = assertNotNull(es[key], "Spanish $key missing")
        val ok =
            Regex(
                "^Para usar «Enviar todo», primero (introduce|ingresa) el destinatario: " +
                    "la comisión de red depende de él\\.$",
            ).matches(text)
        assertTrue(ok, "send_all_reason_needs_recipient is not the reviewed instruction: $text")
    }

    @Test
    fun part_a_every_rewritten_line_keeps_the_english_placeholders() {
        val problems = mutableListOf<String>()
        (partA.keys + "send_all_reason_needs_recipient").forEach { key ->
            val e = placeholders(assertNotNull(en[key], "English $key missing"))
            val s = placeholders(assertNotNull(es[key], "Spanish $key missing"))
            if (e != s) problems += "$key en=$e es=$s"
        }
        assertTrue(problems.isEmpty(), "placeholder sets differ:\n" + problems.joinToString("\n"))
    }

    @Test
    fun part_a_wallet_is_billetera_in_the_shield_info_text() {
        val text = assertNotNull(es["home_info_transparent_message"])
        assertTrue("billetera" in text, "home_info_transparent_message must say «billetera»: $text")
        assertFalse(Regex("\\bwallet\\b", RegexOption.IGNORE_CASE).containsMatchIn(text), "untranslated wallet: $text")
    }

    @Test
    fun b1_restore_error_says_access_can_be_lost_not_that_funds_are_erased() {
        val e = assertNotNull(en["restore_bd_error_different_wallet_on_device"])
        val s = assertNotNull(es["restore_bd_error_different_wallet_on_device"])
        assertTrue("lose access to" in e, "EN must say the user can lose access to the funds: $e")
        assertFalse("any funds sent to it" in e, "EN still says the funds are erased: $e")
        assertTrue("perder el acceso a los fondos" in s, "ES must say the user can lose access to the funds: $s")
        assertFalse("los fondos enviados a ella" in s, "ES still says the funds are erased: $s")
    }

    @Test
    fun b2_group_cost_note_limits_the_paid_transaction_to_members_the_relays_cannot_reach() {
        val enNote = assertNotNull(enPlurals["create_group_cost_note"], "EN create_group_cost_note missing")
        val esNote = assertNotNull(esPlurals["create_group_cost_note"], "ES create_group_cost_note missing")
        assertEquals(setOf("one", "other"), enNote.keys, "EN plural forms")
        assertEquals(setOf("one", "many", "other"), esNote.keys, "ES plural forms")

        assertEquals(
            "Each message to the other member costs at least %2\$s ZEC if it is sent as an on-chain transaction. " +
                "It is free when it can be sent over the relays.",
            enNote["one"],
        )
        val enMany =
            "Each message needs one on-chain transaction for every other member the relays cannot reach: at least " +
                "%2\$s ZEC per transaction. If all %1\$d other members need that route, the total is at least %3\$s " +
                "ZEC. Sending over the relays is free."
        assertEquals(enMany, enNote["other"])

        assertEquals(
            "Cada mensaje al otro miembro cuesta al menos %2\$s ZEC si se envía en una transacción en la cadena. " +
                "Es gratis si se puede enviar por los relays.",
            esNote["one"],
        )
        val esMany =
            "Cada mensaje requiere una transacción en la cadena por cada uno de los demás miembros a los que no se " +
                "pueda llegar por los relays: al menos %2\$s ZEC por transacción. Si los otros %1\$d miembros " +
                "necesitan esa vía, el total es de al menos %3\$s ZEC. El envío por los relays es gratis."
        assertEquals(esMany, esNote["many"])
        assertEquals(esMany, esNote["other"])
    }

    @Test
    fun b3_open_warning_says_every_message_you_send() {
        val e = assertNotNull(en["new_chat_open_warning_body"])
        val s = assertNotNull(es["new_chat_open_warning_body"])
        assertTrue("every message you send on-chain" in e, "EN must say every message you send: $e")
        assertFalse("every message on-chain" in e, "EN still says every message on-chain: $e")
        assertTrue("cada mensaje que envíes vaya en la cadena" in s, "ES must say every message you send: $s")
        assertFalse("que cada mensaje vaya en la cadena" in s, "ES still says every message: $s")
    }

    @Test
    fun b4_shield_subtitle_shown_after_broadcast_does_not_claim_completion() {
        val e = assertNotNull(en["send_confirmation_success_subtitle_transparent"])
        val s = assertNotNull(es["send_confirmation_success_subtitle_transparent"])
        assertTrue("being shielded" in e, "EN must use pending wording: $e")
        assertFalse("has been shielded" in e, "EN still claims completion: $e")
        assertTrue("se está protegiendo" in s, "ES must use pending wording: $s")
        assertFalse("se ha protegido" in s, "ES still claims completion: $s")
    }

    @Test
    fun b_rewritten_lines_keep_the_english_placeholders_in_both_languages() {
        val problems = mutableListOf<String>()
        listOf(
            "restore_bd_error_different_wallet_on_device",
            "new_chat_open_warning_body",
            "send_confirmation_success_subtitle_transparent",
        ).forEach { key ->
            val e = placeholders(assertNotNull(en[key]))
            val s = placeholders(assertNotNull(es[key]))
            if (e != s) problems += "$key en=$e es=$s"
        }
        val enNote = assertNotNull(enPlurals["create_group_cost_note"])
        assertNotNull(esPlurals["create_group_cost_note"]).forEach { (quantity, text) ->
            val e = placeholders(enNote[quantity] ?: assertNotNull(enNote["other"]))
            val s = placeholders(text)
            if (e != s) problems += "create_group_cost_note[$quantity] en=$e es=$s"
        }
        assertTrue(problems.isEmpty(), "placeholder sets differ:\n" + problems.joinToString("\n"))
    }

    private fun placeholders(text: String): Set<String> = Regex("%\\d+\\$[sd]").findAll(text).map { it.value }.toSet()

    private fun resFiles(dir: String): List<File> =
        resRoot
            .listFiles()
            .orEmpty()
            .flatMap { module ->
                File(module, dir)
                    .listFiles { f -> f.extension == "xml" }
                    .orEmpty()
                    .toList()
            }

    private fun allStrings(dir: String): Map<String, String> =
        resFiles(dir).fold(emptyMap()) { acc, f -> acc + parseStrings(stripComments(f.readText())) }

    private fun allPlurals(dir: String): Map<String, Map<String, String>> =
        resFiles(dir).fold(emptyMap()) { acc, f -> acc + parsePlurals(stripComments(f.readText())) }

    private fun stripComments(xml: String): String = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun parseStrings(xml: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m -> m.groupValues[1] to unescape(m.groupValues[2]) }

    private fun parsePlurals(xml: String): Map<String, Map<String, String>> =
        Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m ->
                m.groupValues[1] to
                    Regex("""<item quantity="([^"]+)"[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(m.groupValues[2])
                        .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            }

    private fun unescape(raw: String): String =
        raw
            .replace(Regex("""<xliff:g[^>]*>(.*?)</xliff:g>""", RegexOption.DOT_MATCHES_ALL), "$1")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}
