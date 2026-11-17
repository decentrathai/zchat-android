package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.crypto.RestoreKexPolicy
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 7 (GPT-6 Q15, rounds 3-6 Spanish review, merged with the Grok list): the Spanish terms the reviewers
 * retired, and the one held-send notice that was still English on a Spanish phone.
 *
 * - The ordinary noun is «billetera», never «cartera»; the secret is «frase de recuperación», never «frase semilla».
 * - «on-chain» is «en la cadena»; the Nostr relays are «relays», not «relés»; a handshake is an «intercambio de
 *   claves», not a «saludo».
 * - The tab is still labelled "Wallet" in the app, so no Spanish line may send the user to a «pestaña Monedero».
 * - The send the history settlement holds (RestoreKexPolicy.STILL_READING_HISTORY) is reported as English text,
 *   so it needs an English resource with the same text and a Spanish one, like every other send notice.
 * - Mode names stay Shielded / Tunnel / Open, as the picker shows them.
 */
class SpanishReviewedTermsTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val resRoot: File get() = File(repoRoot, "ui-lib/src/main/res/ui")

    private val retiredTerms = listOf("cartera", "on-chain", "relés", "saludo", "frase semilla", "monedero")

    @Test
    fun no_retired_spanish_term_is_left_in_any_spanish_resource() {
        val spanishFiles =
            repoRoot
                .walkTopDown()
                .onEnter { it.name !in setOf("build", ".gradle", ".git", "node_modules", ".idea") }
                .filter { it.isFile && it.extension == "xml" && it.parentFile?.name == "values-es" }
                .filter { "/src/main/res/" in it.path.replace(File.separatorChar, '/') }
                .toList()
        assertTrue(spanishFiles.size > 10, "fixture: the Spanish resource files were found: ${spanishFiles.size}")

        val found =
            spanishFiles.flatMap { file ->
                val text = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
                text.lines().mapIndexedNotNull { i, line ->
                    val hits = retiredTerms.filter { line.contains(it, ignoreCase = true) }
                    if (hits.isEmpty()) null else "${file.relativeTo(repoRoot)}:${i + 1} $hits"
                }
            }
        assertTrue(found.isEmpty(), "retired Spanish terms are still shown:\n" + found.joinToString("\n"))
    }

    @Test
    fun the_reviewed_spanish_lines_read_as_reviewed() {
        val es = allStrings("values-es")
        val expected =
            mapOf(
                "chat_mode_picker_desc_vault" to
                    "Cada mensaje va en la cadena y cuesta al menos 0,00017 ZEC. El modo más privado.",
                "chat_mode_label_vault" to "Shielded (en la cadena)",
                "chat_mode_label_tunnel" to "Tunnel (un intercambio de claves seguro y luego gratis por relays)",
                "chat_mode_label_open" to "Open (gratis, por relays)",
                "chat_privacy_chip_stranded" to
                    "Los fondos están en el pool Orchard sellado — mira «Saldo antiguo» en la pestaña Wallet",
                "chat_unverified_check_safety_number" to "Comprobar el número de seguridad",
                "move_old_balance_consent_handshakes" to
                    "Intercambios de claves de chat pendientes: %1\$d, al menos %2\$s en total. Después del movimiento, " +
                    "ZCHAT te volverá a preguntar antes de pagarlos con los fondos movidos.",
                "identity_reset_step_seed" to "1. Guardado una copia de tu frase de recuperación",
                "destroy_locked_minutes" to "Demasiados intentos fallidos. Vuelve a intentarlo dentro de %1\$d min.",
                "destroy_locked_seconds" to "Demasiados intentos fallidos. Vuelve a intentarlo dentro de %1\$d s.",
            )
        for ((name, value) in expected) {
            assertEquals(value, es[name], "values-es $name")
        }
    }

    @Test
    fun the_held_send_notice_has_an_english_and_a_spanish_resource() {
        val notice = RestoreKexPolicy.STILL_READING_HISTORY
        assertEquals(
            notice,
            notice.toZchatUserMessage("FALLBACK"),
            "the chat error toast passes the notice through the mapper unchanged, so the translation can find it",
        )
        val id = assertNotNull(ChatSendCopy.localizedResource(notice), "no translation for: $notice")
        val name = resourceName(id)
        assertEquals(notice, allStrings("values")[name], "English resource $name must be the exact text the app reports")
        val spanish = assertNotNull(allStrings("values-es")[name], "Spanish resource $name missing")
        assertNotEquals(notice, spanish, "Spanish resource $name is not translated")
        assertTrue("historial" in spanish && "No se envió nada" in spanish, "the Spanish says what the English says: $spanish")
    }

    private fun resourceName(id: Int): String =
        assertNotNull(
            R.string::class.java.fields
                .firstOrNull { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }
                ?.name,
            "no string resource with id $id",
        )

    private fun allStrings(dir: String): Map<String, String> =
        resRoot
            .listFiles()
            .orEmpty()
            .flatMap { module ->
                File(module, dir)
                    .listFiles { f -> f.extension == "xml" }
                    .orEmpty()
                    .toList()
            }.fold(emptyMap()) { acc, f -> acc + parse(f.readText()) }

    private fun parse(xml: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m ->
                m.groupValues[1] to
                    m.groupValues[2]
                        .replace("\\'", "'")
                        .replace("\\\"", "\"")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
            }
}
