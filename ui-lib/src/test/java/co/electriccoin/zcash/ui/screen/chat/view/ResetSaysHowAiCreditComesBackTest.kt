@file:Suppress("ktlint:standard:max-line-length")

package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AI-KEY-T (W-52, W-53; AI-SIGNIN-KEY-SPEC.md revision 2b, sections 5.3, 5.4, 6.6, 7.2 and 7.3): what the Reset screen, the
 * Full Wallet Reset dialog and the Destroy PIN set-up say about AI credit, in English and Spanish.
 *
 * WHY IT FAILS TODAY: the four further values of the warning and of the note, and the seven destroy_pin_ai_* keys do not
 * exist; ZC-718's one warning says "kept" in every state; destroy_stays_ai has one sentence.
 */
class ResetSaysHowAiCreditComesBackTest {
    private fun warnings(base: String): List<String> = listOf(base) + AI_PARTS.map { "${base}_$it" }

    private fun forBothTexts(block: (file: String, base: String) -> Unit) {
        block(DW, WARNING)
        block(STR, NOTE)
    }

    @Test
    fun `all five values end with the sentence about handing the phone on and keep the clause about a kept copy`() =
        forBothTexts { file, base ->
            for (key in warnings(base)) {
                val en = text(file, "values", key)
                val es = text(file, "values-es", key)
                assertTrue("$key EN: $en", en.endsWith(HAND_ON_EN))
                assertTrue("$key ES: $es", es.endsWith(HAND_ON_ES))
                assertTrue("$key EN: $en", "that copy stays" in en)
                assertTrue("$key ES: $es", "esa copia se queda" in es)
                assertNotEquals(key, en, es)
                for (value in listOf(en, es)) assertFalse("$key has a placeholder: $value", '%' in value)
            }
        }

    @Test
    fun `the plain one - no sentence about AI credit or a key`() =
        forBothTexts { file, base ->
            val en = text(file, "values", base)
            val es = text(file, "values-es", base)
            for (word in listOf("AI credit", "key", "kept for")) assertFalse("$base EN says \"$word\": $en", word in en)
            for (word in listOf("saldo de IA", "clave")) assertFalse("$base ES says «$word»: $es", word in es)
        }

    @Test
    fun `the kept one - kept for this phrase only, another phone cannot yet, never deleted, ends with its end`() =
        forBothTexts { file, base ->
            val key = "${base}_ai_kept"
            val en = text(file, "values", key)
            val es = text(file, "values-es", key)
            for (word in listOf("kept", "this recovery phrase only", "on another phone", "may not open that account yet")) {
                assertTrue("$key EN lacks \"$word\": $en", word in en)
            }
            for (word in listOf("conservada", "solo para esta frase de recuperación", "en otro teléfono", "todavía no abra")) {
                assertTrue("$key ES lacks «$word»: $es", word in es)
            }
            assertFalse("$key EN: $en", "to use the credit again" in en)
            assertKeyNeverDeleted(key, en, "key", listOf("delete"))
            assertKeyNeverDeleted(key, es, "clave", listOf("elimin", "borra"))
            assertEndsItsAiPart(key, en, es)
        }

    @Test
    fun `the parked one - may hold paid credit, not used, ends with its end`() =
        forBothTexts { file, base ->
            val key = "${base}_ai_kept_parked"
            val en = text(file, "values", key)
            val es = text(file, "values-es", key)
            for (word in listOf("may hold paid credit", "not used", "this recovery phrase only")) {
                assertTrue("$key EN lacks \"$word\": $en", word in en)
            }
            for (word in listOf("puede tener saldo pagado", "no se usa", "solo para esta frase de recuperación")) {
                assertTrue("$key ES lacks «$word»: $es", word in es)
            }
            assertEndsItsAiPart(key, en, es)
        }

    @Test
    fun `the linked one - the recovery phrase opens it again, the unreadable one - this reset deletes a key in it`() =
        forBothTexts { file, base ->
            val linkedEn = text(file, "values", "${base}_ai_linked")
            val linkedEs = text(file, "values-es", "${base}_ai_linked")
            for (word in listOf("recovery phrase", "opens it again")) assertTrue("linked EN: $linkedEn", word in linkedEn)
            for (word in listOf("frase de recuperación", "se vuelve a abrir")) assertTrue("linked ES: $linkedEs", word in linkedEs)
            assertFalse("linked EN promises a link: $linkedEn", "will link" in linkedEn)
            val unreadableEn = text(file, "values", "${base}_ai_unreadable")
            val unreadableEs = text(file, "values-es", "${base}_ai_unreadable")
            assertTrue("unreadable EN: $unreadableEn", "this reset deletes that key" in unreadableEn)
            assertTrue("unreadable ES: $unreadableEs", "este restablecimiento elimina esa clave" in unreadableEs)
        }

    @Test
    fun `destroy_stays_ai - only if linked, and names the Destroy PIN screen`() {
        val en = text(STR, "values", "destroy_stays_ai")
        val es = text(STR, "values-es", "destroy_stays_ai")
        for (word in listOf("only if", "Destroy PIN")) assertTrue("EN: $en", word in en)
        for (word in listOf("solo si", "PIN de destrucción")) assertTrue("ES: $es", word in es)
    }

    @Test
    fun `the seven Destroy PIN keys, exactly as spec 7_3`() {
        for ((key, pair) in PIN_TEXTS) {
            assertEquals(key, pair.first, text(STR, "values", key))
            assertEquals(key, pair.second, text(STR, "values-es", key))
        }
    }

    @Test
    fun `the Destroy PIN lines - what they may and must not say`() {
        for (key in listOf("destroy_pin_ai_not_yet", "destroy_pin_ai_cannot")) {
            assertTrue(key, "may hold paid credit" in text(STR, "values", key))
            assertTrue(key, "puede tener saldo pagado" in text(STR, "values-es", key))
        }
        assertFalse("paid" in text(STR, "values", "destroy_pin_ai_safe").lowercase())
        assertFalse("pagad" in text(STR, "values-es", "destroy_pin_ai_safe").lowercase())
        val failed = (text(STR, "values", LINK_FAILED) + text(STR, "values-es", LINK_FAILED)).lowercase()
        for (word in listOf("connection", "internet", "network", "conexión")) {
            assertFalse("advice about the connection: $failed", word in failed)
        }
    }

    @Test
    fun `no string of this feature says what must not be said`() {
        val forbidden = listOf("by itself", "automatically", "por sí solo", "automáticamente", "lost", "gone for good", "se pierde")
        val feature =
            (
                listOf("values", "values-es").flatMap { dir ->
                    strings(AI_STRINGS, dir).filterKeys { key -> FEATURE_AI_KEYS.any { key.startsWith(it) } }.values
                } + featureResetAndDestroyTexts()
            )
        assertTrue("fixture: no text", feature.isNotEmpty())
        for (value in feature) {
            for (word in forbidden) {
                val whole = Regex("""(?<!\p{L})${Regex.escape(word)}(?!\p{L})""", RegexOption.IGNORE_CASE)
                assertFalse("says \"$word\": $value", whole.containsMatchIn(value))
            }
        }
    }

    @Test
    fun `support stands in none of the Reset and Destroy strings - there the phrase or the kept key is the way`() {
        for (value in featureResetAndDestroyTexts()) {
            assertFalse("says support: $value", "support" in value.lowercase() || "soporte" in value.lowercase())
        }
    }

    private fun featureResetAndDestroyTexts(): List<String> =
        listOf("values", "values-es").flatMap { dir ->
            warnings(WARNING).map { text(DW, dir, it) } +
                warnings(NOTE).map { text(STR, dir, it) } +
                PIN_TEXTS.keys.map { text(STR, dir, it) } +
                text(STR, dir, "destroy_stays_ai")
        }

    private fun assertEndsItsAiPart(
        key: String,
        en: String,
        es: String,
    ) {
        val enPart = en.removeSuffix(HAND_ON_EN).trim()
        val esPart = es.removeSuffix(HAND_ON_ES).trim()
        assertTrue("$key EN does not name its end: $en", enPart.endsWith("until Destroy All or an uninstall removes it."))
        assertTrue("$key ES does not name its end: $es", esPart.endsWith("hasta que Destruir todo o una desinstalación la quiten."))
    }

    private fun assertKeyNeverDeleted(
        key: String,
        text: String,
        word: String,
        deleted: List<String>,
    ) {
        for (sentence in text.split(Regex("""(?<=[.;:])\s+""")).filter { word in it.lowercase() }) {
            for (bad in deleted) assertFalse("$key: a sentence about the key says it is deleted: $sentence", bad in sentence.lowercase())
        }
    }

    private fun strings(
        file: String,
        dir: String,
    ): Map<String, String> {
        val xml = File(repoRoot, "ui-lib/src/main/res/ui/$file".replace("{dir}", dir))
        assertTrue("missing ${xml.path}", xml.isFile)
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(xml)
                .getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }.associate {
            it.attributes.getNamedItem("name").nodeValue to it.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
    }

    private fun text(
        file: String,
        dir: String,
        key: String,
    ): String {
        val value = strings(file, dir)[key]
        assertNotNull("$dir: no $key in $file", value)
        return value!!
    }

    private val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "gradle.properties").isFile && File(it, "ui-lib").isDirectory }
            ?: error("repo root not found above the test working dir")
    }

    private companion object {
        const val DW = "delete_wallet/{dir}/strings.xml"
        const val STR = "common/{dir}/destroy_identity_strings.xml"
        const val AI_STRINGS = "common/{dir}/ai_strings.xml"
        const val WARNING = "delete_wallet_checkbox_warning_unchecked"
        const val NOTE = "identity_reset_ai_note"
        const val LINK_FAILED = "destroy_pin_ai_link_failed"
        val AI_PARTS = listOf("ai_unreadable", "ai_kept", "ai_kept_parked", "ai_linked")
        const val HAND_ON_EN = "To hand this phone on, use Destroy All."
        const val HAND_ON_ES = "Para entregar este teléfono a otra persona, usa Destruir todo."
        val FEATURE_AI_KEYS =
            listOf(
                "ai_signin_",
                "ai_signup_limit",
                "ai_notice_",
                "ai_link_again",
                "ai_look_for",
                "ai_old_credit",
                "ai_try_to_link",
                "ai_topup_not_linked",
            )
        val PIN_TEXTS =
            mapOf(
                "destroy_pin_ai_unreadable" to
                    (
                        "ZCHAT cannot read this wallet's AI data on this phone. If it holds a key to AI credit, Destroy All deletes it." to
                            "ZCHAT no puede leer los datos de IA de esta billetera en este teléfono. Si contienen una clave de un saldo de IA, Destruir todo la elimina."
                    ),
                "destroy_pin_ai_cannot" to
                    (
                        "This phone holds a key to an AI account that may hold paid credit and that your recovery phrase cannot open. Destroy All deletes that key. The AI tab says what can be done first." to
                            "Este teléfono guarda una clave de una cuenta de IA que puede tener saldo pagado y que tu frase de recuperación no puede abrir. Destruir todo elimina esa clave. La pestaña de IA indica qué se puede hacer antes."
                    ),
                "destroy_pin_ai_not_yet" to
                    (
                        "This wallet's AI account may hold paid credit and may not be linked to your recovery phrase yet. Destroy All deletes this phone's key to it, and the phrase may not open it." to
                            "La cuenta de IA de esta billetera puede tener saldo pagado y puede que todavía no esté vinculada a tu frase de recuperación. Destruir todo elimina la clave de este teléfono para usarla, y puede que la frase no pueda abrirla."
                    ),
                "destroy_pin_ai_link_now" to ("Link now" to "Vincular ahora"),
                LINK_FAILED to
                    (
                        "ZCHAT could not link it now. Try again later." to
                            "ZCHAT no pudo vincularla ahora. Inténtalo de nuevo más tarde."
                    ),
                "destroy_pin_ai_safe" to
                    (
                        "This wallet's AI account is linked to your recovery phrase. After Destroy All, restoring the phrase in ZCHAT opens it again." to
                            "La cuenta de IA de esta billetera está vinculada a tu frase de recuperación. Después de Destruir todo, restaurar la frase en ZCHAT la vuelve a abrir."
                    ),
                "destroy_pin_ai_check_now" to ("Check now" to "Comprobar ahora"),
            )
    }
}
